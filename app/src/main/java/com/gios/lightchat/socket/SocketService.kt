package com.gios.lightchat.socket

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.gios.lightchat.AlertText
import com.gios.lightchat.Contacts
import com.gios.lightchat.CatchUp
import com.gios.lightchat.DeliveryWorker
import com.gios.lightchat.HeadsUp
import com.gios.lightchat.LoginCodes
import com.gios.lightchat.Notifications
import com.gios.lightchat.PendingAlerts
import com.gios.lightchat.PollAlarm
import com.gios.lightchat.ReadReceipt
import com.gios.lightchat.ReadVerdict
import com.gios.lightchat.SenderFilter
import com.gios.lightchat.ReadStatusEvent
import com.gios.lightchat.RoomIdentity
import com.gios.lightchat.TypingEvent
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.api.Store
import com.gios.lightchat.db.MessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Foreground service holding the one live Socket.IO connection to the BlueBubbles
 * server. This is the OpenBubbles replacement's core: with no Google Play push on
 * the device, instant delivery means keeping a socket open ourselves — but it's a
 * single lightweight connection, not a whole Flutter runtime.
 *
 * On `new-message`/`updated-message` it parses the payload (shared with
 * [BlueBubblesApi]), pushes it onto [SocketBus] for the live UI, and — when the
 * app isn't foreground — raises a notification.
 */
class SocketService : Service() {

    private var socket: Socket? = null
    private var api: BlueBubblesApi? = null
    private var wakeReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkCatchUp = 0L

    /** Cancelled in [onDestroy]; used for the read-verification round trip. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.SERVICE_ID,
            Notifications.foregroundNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        connect()
        startWatchdog()
        watchForWake()
        watchForNetwork()
        // Armed here as well as at boot and on launch: whichever runs first, the
        // asleep-phone poll exists.
        PollAlarm.schedule(this)
        // The chain-of-last-resort, on a different system service to the alarm so it
        // doesn't share its failure modes. Cheap to ask for repeatedly (KEEP).
        DeliveryWorker.ensure(this)
    }

    /**
     * Catch up the moment the phone is picked up, if the poll looks like it stopped.
     *
     * Every layer above this can fail silently and leave the app quiet for hours; what the
     * user then does is turn the screen on. That's the cheapest possible moment to repair
     * things — the CPU is running, the radio is up, and any delay is invisible because they
     * haven't got to the app yet. `ACTION_SCREEN_ON` can only be registered at runtime (it
     * has been manifest-exempt since Android 3), which is fine: this is the layer for a
     * live process whose scheduling broke, not for a dead one.
     *
     * Gated on [PollAlarm.looksStalled] so a phone being used normally doesn't re-pull the
     * list on every unlock — the socket is already doing that job when it's healthy.
     */
    private fun watchForWake() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!PollAlarm.looksStalled(context)) return
                Log.w(TAG, "poll looks stalled; catching up on wake")
                PollAlarm.schedule(context)
                scope.launch { CatchUp.run(context) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            // Both, because the interesting one depends on whether a lock is set: with a
            // PIN the useful signal is the unlock, without one the screen coming on is all
            // there is. Duplicates are harmless — the stall gate rejects the second.
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess { wakeReceiver = receiver }
            .onFailure { Log.w(TAG, "couldn't watch for wake: $it") }
    }

    /**
     * Catch up when a usable network appears.
     *
     * The server is only reachable through the Tailscale tunnel, and the tunnel comes and
     * goes independently of everything else here — it drops with the screen, reconnects on
     * its own schedule, and a socket "reconnect" attempted without it just fails. Anything
     * that arrived while the tunnel was down is invisible to the socket, which has no idea
     * it missed anything, so the reconnect alone isn't enough: re-pull as well.
     */
    private fun watchForNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val live = socket
                if (live == null || !live.connected()) runCatching { live?.connect() }
                catchUpOpportunistically()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "couldn't watch the network: $it") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * The safety net under the live socket.
     *
     * With no Google push, instant delivery is a socket we hold open ourselves — and a
     * socket is not a guarantee. It can be silently wedged (connected as far as we know,
     * no events arriving), the Tailscale tunnel can drop while the screen is off, Doze can
     * freeze us, and the process can be killed outright. Any of those and a message simply
     * never arrives, with nothing to notice: this is the "sometimes it doesn't notify me"
     * failure.
     *
     * So every [WATCHDOG_MS] we reconnect if the socket says it's down, and re-pull the
     * list either way, alerting for anything past the watermark. Cheap — one REST call.
     *
     * This loop only ticks while the phone is *awake*. It's a `delay`, i.e. a JVM timer,
     * and once the screen is off and the device drops into Doze the CPU suspends and our
     * network is cut — a foreground service keeps the process alive, not awake.
     * [PollAlarm] is what covers the asleep case, and `MainActivity.onStart` covers the
     * process being killed outright. Three layers, because none is sufficient alone.
     */
    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_MS)
                val live = socket
                if (live == null || !live.connected()) {
                    Log.w(TAG, "socket down; reconnecting")
                    runCatching { live?.connect() }
                }
                CatchUp.run(this@SocketService)
            }
        }
    }


    /**
     * A catch-up triggered by something that can happen repeatedly for one underlying event
     * — the tunnel coming back announces itself as both a new default network and a socket
     * reconnect, several times over, while it settles. Rate-limited so that's one round
     * trip rather than a dozen.
     */
    private fun catchUpOpportunistically() {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastNetworkCatchUp < NETWORK_CATCHUP_MIN_GAP_MS) return
            lastNetworkCatchUp = now
        }
        scope.launch { CatchUp.run(this@SocketService) }
    }

    private fun connect() {
        val password = Store.password(this) ?: run { stopSelf(); return }
        val baseUrl = Store.baseUrl(this) ?: run { stopSelf(); return }
        val opts = IO.Options().apply {
            transports = arrayOf("websocket") // server upgrades to ws anyway; skip polling
            query = "password=" + URLEncoder.encode(password, "UTF-8")
            reconnection = true
        }
        val s = runCatching { IO.socket(baseUrl, opts) }.getOrNull() ?: run { stopSelf(); return }
        socket = s
        s.on(Socket.EVENT_CONNECT, Emitter.Listener {
            Log.d(TAG, "socket connected")
            // A reconnect means we were off the air for some length of time; find out what
            // arrived while we were. Throttled with the network callback, since a flapping
            // tunnel fires both, repeatedly, for the same outage.
            catchUpOpportunistically()
        })
        s.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener { Log.w(TAG, "connect error: ${it.firstOrNull()}") })
        s.on("new-message", Emitter.Listener { onMessage(it, isNew = true) })
        s.on("updated-message", Emitter.Listener { onMessage(it, isNew = false) })
        // A send the Mac accepted but couldn't deliver (e.g. not an iMessage
        // address) — the payload is the message with its `error` set; merging it
        // flips the bubble to "Not delivered" instead of failing silently.
        s.on("message-send-error", Emitter.Listener { onMessage(it, isNew = false) })
        s.on("typing-indicator", Emitter.Listener { onTyping(it) })
        // The chat was read somewhere on the account (Mac, iPhone, or our own
        // markRead) — the server's chat.db poller reports it, no Private API needed.
        // Clears the list's unread marker and the chat's now-stale notification.
        s.on("chat-read-status-changed", Emitter.Listener { onReadStatus(it) })
        // Group-system changes arrive as their own event types but carry the same
        // serialized message payload (embedded chats included), so they route
        // through the normal message path — the thread shows them as event rows
        // and the list bumps. They never notify (see onMessage's isGroupEvent gate).
        for (event in listOf("group-name-change", "participant-added", "participant-removed", "participant-left")) {
            s.on(event, Emitter.Listener { onMessage(it, isNew = true) })
        }
        s.connect()
    }

    /**
     * A `chat-read-status-changed` event — `{ chatGuid, read }`. Bridged to the ViewModel
     * for the unread marker, and when read, dismisses the chat's alert so something
     * already read on another device doesn't linger here.
     *
     * The event carries no timestamp, and the server's chat.db poller will happily report
     * `read: true` describing the state *before* a message that has just arrived — a chat
     * you had open on this phone (so we marked it read) is the common way to get one. Left
     * alone, that stale event dismisses the notification for the reply you were waiting
     * for, seconds after it was posted. So a read is verified before it's acted on: ask
     * the server for the chat's newest message and only dismiss if that message really
     * does carry a `dateRead`.
     */
    private fun onReadStatus(args: Array<out Any?>?) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val guid = data.optString("chatGuid").takeIf { it.isNotBlank() } ?: return
        val read = data.optBoolean("read", false)
        SocketBus.readStatus.tryEmit(ReadStatusEvent(guid, read))
        if (!read) return
        // Off the socket thread: this makes a REST call.
        scope.launch {
            if (!newestIsRead(guid)) {
                Log.d(TAG, "stale read for $guid — newest message still unread, keeping the alert")
                return@launch
            }
            Notifications.clearChat(this@SocketService, listOf(guid))
            HeadsUp.cancel(guid)
        }
    }

    /**
     * Whether [chatGuid]'s newest incoming message has actually been read. False on any
     * failure: keeping an alert we can't justify dismissing is the safe direction, and
     * [ReadReceipt] now says so in a word rather than in a boolean whose safe value
     * depends on which way round the caller phrased its question.
     */
    private suspend fun newestIsRead(chatGuid: String): Boolean = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext false
        val verdict = ReadReceipt.verdict(
            runCatching { client.messages(chatGuid, limit = ReadReceipt.VERIFY_LIMIT) }.getOrNull(),
        )
        verdict == ReadVerdict.READ
    }

    /** Built on demand from the stored setup, and only for [newestIsRead] — the socket
     *  itself needs no client. */
    private fun client(): BlueBubblesApi? {
        api?.let { return it }
        val url = Store.baseUrl(this) ?: return null
        val password = Store.password(this) ?: return null
        return BlueBubblesApi(url, password).also { api = it }
    }

    /** A `typing-indicator` event — `{ display, guid }` — bridged to the ViewModel.
     *  No notification; it only matters for the open thread. */
    private fun onTyping(args: Array<out Any?>?) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val guid = data.optString("guid").takeIf { it.isNotBlank() } ?: return
        SocketBus.typing.tryEmit(TypingEvent(guid, data.optBoolean("display", false)))
    }

    private fun onMessage(args: Array<out Any?>?, isNew: Boolean) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val incoming = BlueBubblesApi.messageEvent(data, isNew) ?: return
        SocketBus.incoming.tryEmit(incoming)
        // The same room as the REST sweep left it, by room guid rather than by chat guid: a group
        // iMessage has forked into sibling rooms is stored once, under the primary guid, and the
        // event is tagged with whichever room the message landed in.
        val stored = runCatching {
            MessageStore.get(this).conversationForRoom(incoming.chatGuid)
        }.getOrNull()
        // Read once: this is a JSON parse per call, and both the filter and the title want it.
        val contacts = contacts()
        // Who the room is, which the event alone cannot say: a push carries no `participants` for a
        // group, so an unnamed one used to reach every notification as "Unknown". Resolved once and
        // used by both the filter and the title, so the two cannot disagree about the same room.
        // See [RoomIdentity].
        val room = RoomIdentity.of(
            eventName = incoming.chatDisplayName,
            eventParticipants = incoming.participants,
            storedName = stored?.displayName,
            storedParticipants = stored?.participants.orEmpty(),
            nickname = Store.nickname(this, stored?.guid ?: incoming.chatGuid),
            sender = incoming.message.sender,
        )
        // A one-time code, if this is one. Read before the alert gate because it decides the
        // gate, and held for the keyboard either way — see CodeProvider. Only for genuinely new
        // incoming messages: a code re-delivered by a reconnect is one the user has already
        // seen, and re-pinning it would put an old number back in the suggestion strip.
        val code = if (isNew && !incoming.message.fromMe && !incoming.message.isGroupEvent) {
            LoginCodes.find(incoming.message.text)
        } else {
            null
        }
        if (code != null) Store.setLoginCode(this, code, incoming.message.date)
        val alertable = isNew &&
            !incoming.message.fromMe &&
            !incoming.message.isGroupEvent &&
            // Someone *removing* a tapback is not news. It bumps the thread in the list
            // (which is iMessage's behaviour) but there is nothing to say about it, and
            // AlertText deliberately has no phrasing for one.
            !incoming.message.isReactionRemoval &&
            // A stranger doesn't interrupt unless you've asked to be interrupted — unless what
            // they sent is the code you are waiting on. The message still arrives above; only
            // the alert is gated. See SenderFilter.
            SenderFilter.mayAlert(
                this,
                SenderFilter.knownSender(
                    contacts,
                    room.name,
                    incoming.message.sender,
                    room.participants,
                ),
                carriesCode = code != null,
            )
        // Arrived while the app was open. Not necessarily *seen*: the user can be on the
        // list, or in another thread, and pressing the power button from there used to mean
        // the message was never recorded anywhere. Hold it for the screen going off
        // instead — see PendingAlerts.
        if (alertable && AppForeground.active) {
            val alert = alertText(incoming, contacts, room)
            // "Foreground" alone can't tell the list from the open thread. A reply that
            // lands in the conversation the user is *in* — the one they just texted — is
            // being read as it arrives, and posting it when the screen goes off is a
            // notification about the message they answered. Held anyway, marked seen, so
            // the flush still moves the watermark past it instead of leaving it for the
            // next poll to buzz about.
            val onScreen = incoming.chatGuid in AppForeground.visibleChatGuids
            PendingAlerts.add(
                incoming.chatGuid, alert.title, alert.body, incoming.message.date,
                seen = onScreen,
            )
        }
        // Notify only for genuinely new incoming messages the user can't see —
        // not group events (renames etc.), whose `text` is empty.
        if (alertable && !AppForeground.active) {
            // Title, and a body that names whoever is responsible — the sender in a group,
            // the reactor for a tapback. See AlertText; the phrasing is shared with the
            // background poll so the same message reads the same either way.
            val alert = alertText(incoming, contacts, room)
            val title = alert.title
            // The notification is the record — it stays in LightOS's list and feeds
            // LightGlance's dot. The box is the alert, and buzzes either way.
            Notifications.post(this, title, alert.body, incoming.chatGuid)
            // Move the watermark past it, which is what stopped this alerting twice.
            //
            // `lastAlertedAt` is meant to be the single line deciding whether a message has been
            // alerted for, however many layers see it. This branch was the one layer that posted
            // without moving it, so the next CatchUp — from any of its six triggers, and so within
            // five minutes — still found the message newer than the line and still `unread`, since
            // with no Private API nothing ever marks it read. It posted and buzzed again. Exactly
            // twice, because CatchUp then advanced the line itself.
            //
            // Same trade the foreground path already makes in MainActivity.flushPendingAlerts:
            // moving the line past this message also moves it past anything older the socket
            // silently missed while wedged, which CatchUp's own header calls out. Alerting once for
            // the message in front of the user beats alerting twice for every message.
            Store.setLastAlertedAt(
                this,
                maxOf(Store.lastAlertedAt(this), incoming.message.date),
            )
            HeadsUp.show(
                this,
                title,
                alert.body,
                incoming.chatGuid,
                // Stamped already: read on another device before the event even got here.
                alreadyRead = incoming.message.dateRead != 0L,
            )
        }
    }

    /** The persisted contact index. Reloaded per notification (infrequent — only
     *  background messages) so a name added while the service ran still resolves. */
    private fun contacts(): Contacts = Store.contacts(this)

    /**
     * What this message's alert says. A tapback's target is looked up in the local store —
     * off the socket thread's own work, but it is a single indexed-ish read for the one
     * message a reaction points at, and without it the line can only say "a message".
     */
    private fun alertText(
        incoming: com.gios.lightchat.IncomingMessage,
        contacts: Contacts,
        room: RoomIdentity.Room,
    ) =
        AlertText.forMessage(
            message = incoming.message,
            isGroup = incoming.isGroup,
            // The room as resolved in [onMessage], not as the event described it: for an unnamed
            // group the event describes nobody. See [RoomIdentity].
            chatDisplayName = room.name,
            participants = room.participants,
            contacts = contacts,
            findTarget = { guid ->
                runCatching { MessageStore.get(this).messageByGuid(guid) }.getOrNull()
            },
        )

    override fun onDestroy() {
        wakeReceiver?.let { runCatching { unregisterReceiver(it) } }
        wakeReceiver = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        scope.cancel()
        socket?.disconnect()
        socket?.off()
        socket = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SocketService"

        /** How long a missed message can stay missed while the service is alive. */
        private const val WATCHDOG_MS = 5 * 60 * 1000L

        /** Floor between catch-ups triggered by the network or a socket reconnect. */
        private const val NETWORK_CATCHUP_MIN_GAP_MS = 60 * 1000L
    }
}
