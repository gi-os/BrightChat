package com.gios.lightchat

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightchat.api.AgentApi
import com.gios.lightchat.api.ApiException
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.api.Store
import com.gios.lightchat.api.WhisperApi
import com.gios.lightchat.dial.AddressBookRepo
import com.gios.lightchat.db.AgentStore
import com.gios.lightchat.db.MessageStore
import com.gios.lightchat.db.Sync
import com.gios.lightchat.socket.AppForeground
import com.gios.lightchat.socket.SocketBus
import com.gios.lightchat.socket.SocketService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Status { Idle, Loading, Ready, Error }

// Send a typing "stop" this long after the last keystroke; expire a received
// "typing" after this long without a refresh (the server re-emits ~every 5s).
private const val TYPING_PAUSE_MS = 4_000L
private const val TYPING_EXPIRY_MS = 12_000L

/** Coming back to the app inside this window doesn't re-pull the list — it can't
 *  have gone stale, and a cold start's init refresh would otherwise be cancelled by
 *  the onStart one landing a few hundred milliseconds later. */
private const val RESUME_REFRESH_MIN_GAP_MS = 3_000L

/**
 * Messages the contact page reads per page.
 *
 * Larger than the thread's, because a page of the grid is three photos wide and fifty
 * messages of an ordinary conversation are not fifty photos — most of them are text. Two
 * hundred is roughly a screenful of grid in a chat that trades pictures, and one fetch in
 * one that doesn't.
 */
private const val DETAILS_PAGE = 200

data class UiState(
    val isConfigured: Boolean,                 // a server URL and password are stored
    val status: Status = Status.Idle,
    val conversations: List<Conversation> = emptyList(),
    val open: Conversation? = null,            // the currently-open thread, if any
    val messages: List<ChatMessage> = emptyList(),
    val threadLoading: Boolean = false,
    val loadingOlder: Boolean = false,         // a history page is in flight (see loadOlder)
    val historyExhausted: Boolean = false,     // the server has no messages older than these
    val threadWindow: Int = 0,                 // how many of the open thread's messages are shown
    val contacts: Contacts = Contacts(),       // address → name, from the server's address book
    val contactList: List<Contact> = emptyList(), // searchable recipients for a new message
    val composingNew: Boolean = false,         // the "New message" compose screen is open
    val privateApi: Boolean = false,           // server's Private API live → tapbacks available
    val typingChatGuid: String? = null,        // chat whose other party is currently typing
    val favorites: Set<String> = emptySet(),   // starred chat guids (local, see Store.favorites)
    val pins: List<String> = emptyList(),      // starred chats held at the top, newest pin first
    val message: String? = null,               // transient status / error line
    // Agents (separate system — see Agent.kt). Their conversations are merged into
    // [conversations] as synthetic rows; these drive the agent thread + editor.
    val agents: List<Agent> = emptyList(),
    val openAgent: Agent? = null,              // the agent thread currently open
    val agentMessages: List<AgentMessage> = emptyList(),
    val agentSending: Boolean = false,         // a completion is in flight
    val agentEditor: Boolean = false,          // the agent create/edit screen is open
    val agentEditorTarget: Agent? = null,      // null → creating a new agent
    // Newsletter (see Newsletter.kt) — named recipient batches one message broadcasts to.
    // Three nullable/boolean screens rather than one enum, matching how the agent screens
    // above are routed, so `LightChatApp`'s `when` reads the same way for both features.
    val newsletters: List<NewsletterBatch> = emptyList(),
    val newsletterList: Boolean = false,       // the batch list is open
    val newsletterEditor: NewsletterBatch? = null,   // the batch being edited
    val newsletterCompose: NewsletterBatch? = null,  // the batch being written to
    val newsletterProgress: NewsletterProgress? = null, // a broadcast in flight, or its outcome
    // Whether a transcription server is configured at all. The mic key is drawn from this, so it
    // lives in the state rather than being read off disk by each composer: a key that appears the
    // moment the setting is filled in, and disappears the moment it is cleared.
    val canTranscribe: Boolean = false,
    // A Whisper request is in flight — a dictation on its way to be read, or a received clip
    // being transcribed. Counted rather than a flag (see [transcribing]) so two at once can't
    // have the first one to finish clear it for both.
    val transcribing: Boolean = false,
)

/**
 * Single source of truth for the BlueBubbles client. Owns password setup, the
 * conversation list, the open thread, sending, and the live feed.
 *
 * Reads (REST, [BlueBubblesApi]): [refresh] re-pulls the list, [open] pulls a
 * thread. Writes: [sendMessage] posts optimistically then reconciles with the
 * server's echo. Live: it collects [SocketBus] (fed by the foreground
 * [SocketService]) and folds new/updated messages into the list and open thread.
 * Ordering is enforced here — conversations by last activity, messages by date.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // A client only exists once setup has stored both a server URL and a password.
    private var api: BlueBubblesApi? = run {
        val url = Store.baseUrl(application)
        val pw = Store.password(application)?.takeIf { it.isNotBlank() }
        if (url != null && pw != null) BlueBubblesApi(url, pw) else null
    }

    /**
     * The phone's own copy of the list and of whatever threads have been opened. The app
     * used to re-download the newest thousand messages on the account on every launch just
     * to know what the list said; now it reads that off disk and fetches only the delta.
     */
    private val store = MessageStore.get(application)
    private val agentStore = AgentStore.get(application)
    private val agentApi = AgentApi()
    private var sync: Sync? = api?.let { Sync(it, store) }

    private val _state = MutableStateFlow(
        UiState(
            isConfigured = api != null,
            canTranscribe = Store.canTranscribe(application),
            privateApi = Store.privateApi(application),
            favorites = Store.favorites(application),
            pins = Store.pins(application),
            // Straight off disk, synchronously, before anything is on screen: the list is
            // the first thing drawn and there is no reason for it to be empty while a
            // network round trip decides what it should have said. Also what makes the app
            // usable with the tunnel down.
            agents = agentStore.agents(),
            conversations = mergeAgents(if (api != null) store.chats() else emptyList()),
            contacts = Store.contacts(application),
            newsletters = Store.newsletters(application),
        ),
    )
    val state: StateFlow<UiState> = _state

    /**
     * The contact page's own state (photos, links). Separate from [state] because building
     * it walks every message held for a conversation and extracts every URL in them, and
     * that must not happen on the frame that opens a thread — only when the page is opened.
     */
    private val _details = MutableStateFlow(DetailsState())
    val details: StateFlow<DetailsState> = _details

    private var detailsJob: Job? = null

    private var loadJob: Job? = null

    /** When [refresh] last started, for [refreshOnResume]'s guard. */
    private var lastRefreshAt = 0L
    private var threadJob: Job? = null

    // The address book is small (hundreds of contacts) and changes rarely, so we
    // fetch it once per session alongside the first conversation load and cache it.
    private var contacts = Store.contacts(application)

    private var contactList = emptyList<Contact>()
    private var contactsLoaded = false

    // Per-conversation message cache (session-lived). Reopening a thread shows the
    // cached messages instantly while a fresh fetch refreshes in the background —
    // no "Loading…" flash. Snapshotted on close so live updates persist. Holds the
    // *raw* list (reaction messages included) so reopening re-folds correctly.
    //
    // Concurrent, because it is written from IO (every fetch that lands, every send that
    // invalidates a thread) and read from the main thread (opening a chat, naming a tapback's
    // target). A plain HashMap structurally modified from two threads can corrupt its own
    // buckets, which fails as a wrong answer or a hang rather than as an exception.
    private val messageCache = ConcurrentHashMap<String, List<ChatMessage>>()

    // Per-conversation (keyed by primary guid) the forked-group room guid that last
    // delivered, so AppleScript sends retry it first instead of re-probing a dead room
    // every time (see sendTargets). Session-lived; the Private API path ignores it.
    private val lastGoodRoom = HashMap<String, String>()

    // The open thread's raw messages — the single source for what's shown. State's
    // `messages` is always foldReactions(openRaw); every open-thread mutation goes
    // through updateOpenThread so tapbacks stay folded onto their targets.
    //
    // **Main thread only** — see [onThreadThread]. It is a plain field doing read-modify-write,
    // and it used to be touched from IO as well (photo sends, every landing fetch); two of those
    // interleaving silently dropped whichever change lost the race, which is how a photo still
    // uploading lost its bubble and then never came back.
    private var openRaw: List<ChatMessage> = emptyList()

    // Per-conversation (primary guid → lastDate at the time) unread markers cleared
    // on this device. Papers over the window between our markRead and the server's
    // chat.db reflecting it — without this a refresh would re-derive unread from a
    // not-yet-stamped dateRead and resurrect the dot on a thread just read here. A
    // newer message (lastDate past the recorded one) shows unread again. Session-
    // lived: across launches the server's own read state is correct.
    private val clearedUnread = HashMap<String, Long>()

    // A chat to open as soon as the conversation list has loaded — set when a
    // notification tap arrives before the list exists (cold start).
    private var pendingOpenGuid: String? = null

    init {
        observeSocket()
        if (api != null) {
            refresh()
            startSocket()
        }
    }

    // ---- Setup ------------------------------------------------------------

    /** First-launch setup: store the server URL + password and validate them
     *  against the server before committing. Both are required. */
    fun saveSetup(url: String, password: String) {
        val pw = password.trim()
        if (url.isBlank() || pw.isEmpty()) return
        _state.update { it.copy(status = Status.Loading, message = "Connecting…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = connectClient(url, pw, "Couldn’t reach the server — check the URL and password")
                ?: return@launch
            Store.setPassword(app, pw)
            _state.update { it.copy(isConfigured = true) }
            loadConversations()
            startSocket()
        }
    }

    /** Changes the server URL from Settings: re-validate against the existing
     *  password, then recreate the client and reconnect the socket to the new host. */
    fun updateServerUrl(url: String) {
        if (url.isBlank()) return
        val pw = Store.password(app)?.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(status = Status.Loading, message = "Connecting…") }
        viewModelScope.launch(Dispatchers.IO) {
            connectClient(url, pw, "Couldn’t reach that server — check the URL") ?: return@launch
            // Bounce the socket so it reconnects to the new host.
            stopSocket()
            startSocket()
            loadConversations()
        }
    }

    /**
     * Shared tail of [saveSetup]/[updateServerUrl]: normalizes + stores [url] (the
     * same way it's read back), validates it against [pw], and installs the client
     * as [api], caching the Private API flag. Returns null — with [failureMessage]
     * surfaced as an error — when the server can't be reached.
     */
    private fun connectClient(url: String, pw: String, failureMessage: String): BlueBubblesApi? {
        Store.setBaseUrl(app, url)
        val base = Store.baseUrl(app) ?: return null
        val client = BlueBubblesApi(base, pw)
        val info = runCatching { client.serverInfo() }.getOrNull()
        if (info?.reachable != true) {
            _state.update { it.copy(status = Status.Error, message = failureMessage) }
            return null
        }
        api = client
        sync = Sync(client, store)
        Store.setPrivateApi(app, info.privateApiReady)
        _state.update { it.copy(privateApi = info.privateApiReady, message = null) }
        return client
    }

    // ---- Conversation list ------------------------------------------------

    fun refresh() {
        if (api == null) return
        lastRefreshAt = SystemClock.elapsedRealtime()
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) { loadConversations() }
    }

    /**
     * Re-pull the list when the app comes back to the foreground
     * (`MainActivity.onStart`). The live socket keeps the list current while the app
     * is up, but it only runs as a foreground service — anything that happened while
     * the process was dead, or while the tunnel was down, is missed, and the list
     * would otherwise show whatever it showed when you left.
     *
     * Rate-guarded because `init` already refreshes on a cold start, and onStart
     * fires immediately after it; without the guard every launch would fire two
     * identical requests and the second would cancel the first.
     */
    fun refreshOnResume() {
        if (api == null) return
        // The open thread first, and unconditionally. Leaving the app from inside a chat
        // leaves `open` set, so coming back re-shows that thread from the cache — if the
        // socket missed anything (process killed, tunnel down, Doze), the reply simply
        // wasn't there and nothing was going to fetch it. This is the "it didn't check
        // whether they texted back" case, and it isn't covered by refreshing the list.
        _state.value.open?.let { reopenThread(it) }
        if (SystemClock.elapsedRealtime() - lastRefreshAt < RESUME_REFRESH_MIN_GAP_MS) return
        refresh()
    }

    /** Re-fetches [conversation]'s messages without the open-thread reset [open] does —
     *  no "Loading…", no scroll change, the cached list stays on screen until the fresh
     *  one lands. */
    private fun reopenThread(conversation: Conversation) {
        val syncer = sync ?: return
        threadJob?.cancel()
        threadJob = viewModelScope.launch(Dispatchers.IO) {
            val msgs = runCatching { syncer.thread(conversation) }.getOrNull().orEmpty()
            if (msgs.isEmpty()) return@launch
            messageCache[conversation.guid] = msgs
            publishFetched(conversation.guid, msgs)
        }
    }

    private suspend fun loadConversations() {
        val client = api ?: return
        val syncer = sync ?: return
        // Loading only when there is nothing to show. With rows on disk the list is already
        // on screen and correct as of the last sync; putting a spinner over it to fetch a
        // delta that is usually empty would be a worse app than the one that had no cache.
        val hadRows = _state.value.conversations.isNotEmpty()
        if (!hadRows) _state.update { it.copy(status = Status.Loading, message = null) }
        try {
            val synced = syncer.refreshList()
            if (synced == null && hadRows) {
                // Unreachable, but the phone still knows what it knew. Stay on the cached
                // list rather than throwing it away or showing an error over it.
                _state.update { it.copy(status = Status.Ready) }
                return
            }
            val convos = (synced ?: store.chats()).sortedByDescending { it.lastDate }
                // Honor unreads already cleared on this device (see clearedUnread).
                .map { c ->
                    if (c.unread && (clearedUnread[c.guid] ?: 0L) >= c.lastDate) c.copy(unread = false) else c
                }
            // Re-check Private API liveness so enabling/disabling it on the server
            // (or the helper dropping) reflects without re-running setup.
            val privateApi = runCatching { client.serverInfo() }.getOrNull()
                ?.also { Store.setPrivateApi(app, it.privateApiReady) }
                ?.privateApiReady ?: _state.value.privateApi
            /**
             * **The handset's own contacts, every refresh.**
             *
             * Outside the `contactsLoaded` guard below on purpose. That guard exists because the
             * server's contact list is a slow call worth making once a session; the phone's
             * address book is a local query, and it is the one that changes while the app is
             * open — you save somebody and come straight back. Merging it only on first load
             * would mean a contact saved a moment ago waited for a restart.
             *
             * Empty when the contacts permission has not been granted, which is until the Dial
             * tab has been opened; then this is the old behaviour exactly.
             */
            val local = runCatching { AddressBookRepo(app).nameIndex() }.getOrDefault(emptyMap())
            if (local.isNotEmpty()) {
                contacts = Contacts.fromMap(contacts.asMap() + local)
                Store.setContacts(app, contacts.asMap())
            }
            /**
             * **And the same book into the New Message picker.**
             *
             * It searched `contactList`, which was built from the server's contacts alone, so
             * somebody saved on this phone could not be found in it — starting a conversation
             * with them meant typing the number out in full, which is the "new messages to new
             * contacts don't work" of it. Merged here rather than where `contactList` is first
             * built, because that is inside the once-a-session guard and the address book is the
             * half that changes while the app is open.
             *
             * De-duplicated by the same normalised key the rest of the app compares addresses
             * with, and the phone's row wins: a number saved in both should be offered under the
             * name you gave it here.
             */
            val localRecipients = runCatching { AddressBookRepo(app).recipients() }
                .getOrDefault(emptyList())
                .map { (name, number) -> Contact(name = name, address = number) }
            if (localRecipients.isNotEmpty()) {
                val localKeys = localRecipients.mapTo(HashSet()) { Contacts.key(it.address) }
                contactList = (localRecipients + contactList.filter { Contacts.key(it.address) !in localKeys })
                    .sortedBy { it.name.lowercase() }
            }
            if (!contactsLoaded) {
                runCatching { client.contacts() }.onSuccess { raw ->
                    /**
                     * **Both address books, and the phone's wins.**
                     *
                     * The server's copy is the Mac's contacts, which is what this app has always
                     * named people from. Anybody saved on the handset — a number you got today
                     * and put a name to — was invisible to it, so the list and the thread header
                     * and the notifications all kept showing digits after you had plainly said
                     * who it was. The dialer already folds the two together for its own list
                     * (`AddressBook.withKnown`); this is the same merge in the other direction,
                     * and the direction everything except the dialer reads.
                     *
                     * The phone overwrites on a collision because it is the one the user just
                     * edited. Where the Mac has a name and the phone does not, the Mac's stands.
                     * Empty when the contacts permission has not been granted — that only
                     * happens after opening the Dial tab — in which case this is the old
                     * behaviour exactly.
                     */
                    // Server first, then the handset over the top: the phone wins a collision
                    // because it is the one the user just edited.
                    contacts = Contacts.fromMap(Contacts.from(raw).asMap() + local)
                    // Persist so SocketService can name notification senders even
                    // when the app (and this ViewModel) isn't running.
                    Store.setContacts(app, contacts.asMap())
                    // One pickable row per address (a person may have several),
                    // newest search needs name→address, sorted for the picker.
                    contactList = raw.map { Contact(name = it.second, address = it.first) }
                        .distinctBy { it.address }
                        .sortedBy { it.name.lowercase() }
                    contactsLoaded = true
                }
            }
            _state.update {
                it.copy(
                    status = Status.Ready,
                    conversations = mergeAgents(convos),
                    contacts = contacts,
                    contactList = contactList,
                    privateApi = privateApi,
                    message = null,
                )
            }
            // A notification tap that landed before the list existed (cold start) —
            // open its thread now, unless the user has already navigated somewhere.
            pendingOpenGuid?.let { guid ->
                pendingOpenGuid = null
                if (_state.value.open == null && !_state.value.composingNew) {
                    convos.firstOrNull { guid in it.guids }?.let(::open)
                }
            }
        } catch (t: Throwable) {
            handleError(t)
        }
    }

    /**
     * Opens the conversation containing [chatGuid] — the notification deep link.
     * If the list isn't loaded yet (app launched from the notification), the open
     * is queued and fires when [loadConversations] lands.
     */
    fun openByGuid(chatGuid: String) {
        val convo = _state.value.conversations.firstOrNull { chatGuid in it.guids }
        if (convo != null) {
            open(convo)
        } else {
            pendingOpenGuid = chatGuid
            refresh()
        }
    }

    // ---- One thread -------------------------------------------------------

    fun open(conversation: Conversation) {
        if (conversation.isAgent) {
            val agent = agentStore.agent(conversation.guid.removePrefix("agent:"))
            if (agent != null) openAgentThread(agent)
            return
        }
        // The in-memory cache is free and renders this frame. The on-disk one is read a
        // moment later, in the thread job — parsing fifty messages and their attachment
        // metadata is not something to do on the frame that handles the tap.
        val cached = messageCache[conversation.guid]
        // On the thread that owns openRaw, so that opening a chat can't interleave with a send
        // or a landing fetch and leave the two disagreeing about which conversation is on
        // screen. `open` is called from IO in four places (a notification tap during a load, and
        // the three send-then-open paths), which is what made this reachable.
        onThreadThread { openThread(conversation, cached) }
    }

    /** Lands the user in [conversation], showing [cached] if there is any, and kicks off the
     *  fetch. The state reset is one update, so no frame can show the new thread's title over
     *  the old one's messages. Runs on the thread that owns [openRaw] — see [open]. */
    private fun openThread(conversation: Conversation, cached: List<ChatMessage>?) {
        openRaw = (cached ?: emptyList()).distinctBy { it.guid }
        val folded = foldReactions(openRaw)
        _state.update {
            it.copy(
                open = conversation,
                messages = folded,
                threadLoading = cached == null,
                loadingOlder = false,
                historyExhausted = false,
                // Every thread starts showing its newest page and grows as it is scrolled.
                threadWindow = MessageStore.PAGE,
            )
        }
        conversation.guids.forEach { markReadIfPrivate(it) }
        clearUnread(conversation.guid)
        Notifications.clearChat(app, conversation.guids)
        // Opening the thread is what "you've seen it" means, so mark any alert being held
        // for it seen — otherwise leaving the app would post a notification for the message
        // just read. Marked, not dropped, so the flush still advances the watermark past
        // it. See PendingAlerts. And publish which thread is on screen, so the socket can
        // make the same call about messages that arrive while the user is watching.
        PendingAlerts.markSeen(conversation.guids)
        AppForeground.visibleChatGuids = conversation.guids.toSet()
        // A share that arrived without a recipient was waiting for exactly this.
        flushPendingShared()
        threadJob?.cancel()
        threadJob = viewModelScope.launch(Dispatchers.IO) {
            val syncer = sync ?: return@launch
            try {
                // Disk first: reopening a thread after the process was killed used to show
                // "Loading…" and refetch a hundred messages. Now the stored copy is on
                // screen before the network is touched.
                if (cached == null) {
                    val onDisk = store.messages(conversation.guids, limit = MessageStore.PAGE)
                    if (onDisk.isNotEmpty()) publishFetched(conversation.guid, onDisk)
                }
                // Raw — reactions included; merged across a forked group's sibling rooms
                // (usually just one guid) and re-sorted by date in foldReactions. Sync
                // decides what to ask for: everything on the first open of a chat, and
                // only what is newer than the newest message held on every open after
                // that. Each room is fetched independently so one stale or dead room of a
                // forked group can't sink the whole thread.
                val msgs = syncer.thread(conversation)
                messageCache[conversation.guid] = msgs
                publishFetched(conversation.guid, msgs)
            } catch (t: Throwable) {
                _state.update { if (it.open?.guid == conversation.guid) it.copy(threadLoading = false) else it }
                handleError(t)
            }
        }
    }

    /**
     * Fetches the page of history under what is held, when the thread is scrolled to the top
     * of it.
     *
     * **This is the only thing in the app that asks for old messages**, and it only runs
     * because somebody scrolled to the end of a conversation. Everything else — the list,
     * the delta sync, opening a thread — deals exclusively in what is new. A chat nobody
     * scrolls back through never costs more than its most recent page.
     *
     * Guarded three ways: not while one is already in flight, not once the server has said
     * there is nothing older ([UiState.historyExhausted]), and not before the first page has
     * landed.
     */
    fun loadOlder() {
        val conversation = _state.value.open ?: return
        val syncer = sync ?: return
        if (_state.value.loadingOlder || _state.value.historyExhausted) return
        // Not while the open fetch is still running: both assign openRaw, and whichever
        // landed second won — which on a short thread meant a page of history being
        // clobbered straight back to the newest fifty.
        if (_state.value.threadLoading || threadJob?.isActive == true) return
        if (openRaw.isEmpty()) return
        // The window grows by a page each time. Returning a fixed size meant that once the
        // thread reached it, every further fetch wrote rows nobody could see, the message
        // count never changed, and the trigger never re-armed — a silent dead end a couple
        // of hundred messages back.
        val window = _state.value.threadWindow + MessageStore.PAGE
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val older = runCatching { syncer.olderThan(conversation, window) }.getOrNull()
            if (_state.value.open?.guid != conversation.guid) {
                _state.update { it.copy(loadingOlder = false) }
                return@launch
            }
            if (older == null) {
                // Nothing came back, so this is the start of the conversation. Recorded in
                // the store as well as in state, so reopening the thread tomorrow doesn't
                // ask for the same empty page again.
                _state.update { it.copy(loadingOlder = false, historyExhausted = true) }
                return@launch
            }
            messageCache[conversation.guid] = older
            // Keeps in-flight sends: a page of history is fetched precisely while you are
            // waiting for photos to upload, and it used to wipe every bubble that was still
            // going up. See replaceKeepingPending.
            publishFetched(conversation.guid, older, clearLoading = false)
            _state.update { it.copy(loadingOlder = false, threadWindow = window) }
        }
    }

    // ---- The contact page -------------------------------------------------

    /**
     * Opens the contact page over the current thread: reads what the store holds and
     * extracts the photos and links from it.
     *
     * Off the store only — no network. The page shows what the phone has, and scrolling it
     * asks for more (see [loadMoreDetails]), which is the same bargain the thread makes.
     */
    fun openDetails() {
        val conversation = _state.value.open ?: return
        detailsJob?.cancel()
        _details.value = DetailsState(chatGuid = conversation.guid, loading = true)
        detailsJob = viewModelScope.launch(Dispatchers.IO) {
            // The one store read in the app that had no guard. It is on a plain `launch`
            // with no exception handler, so a locked or corrupt database here would not
            // show an empty page — it would end the process.
            val read = runCatching {
                val loaded = conversation.guids.any { store.isThreadLoaded(it) }
                loaded to store.messages(conversation.guids, limit = DETAILS_PAGE)
            }
            // `cancel()` cannot interrupt a blocking SQLite call, so the read above finishes
            // even when the page has since been closed and reopened. Publishing it then
            // would stamp a fresh session with a stale window.
            if (!isActive) return@launch
            val (loaded, held) = read.getOrElse {
                _details.update { s -> if (s.chatGuid == conversation.guid) s.copy(loading = false) else s }
                return@launch
            }
            publishDetails(conversation, held, loaded, window = DETAILS_PAGE, exhausted = false)
        }
    }

    /** Leaves the contact page. The extracted lists are dropped rather than kept warm:
     *  they are a few hundred objects and rebuilding them costs one disk read. */
    fun closeDetails() {
        detailsJob?.cancel()
        detailsJob = null
        _details.value = DetailsState()
    }

    /**
     * Reads further back for the contact page — scrolled to the bottom of it.
     *
     * Same mechanism as the thread's [loadOlder], and deliberately so: one page more of
     * history per crossing, fetched from the Mac if the phone doesn't have it.
     *
     * The extra guard over [loadOlder] is the growth check. [MessageStore.KEEP_PER_CHAT]
     * caps what a chat keeps on disk, so past that cap a fetch stores a page and the trim
     * immediately drops it again: the server always has something older, `olderThan` never
     * returns null, and the page would keep making a network request per scroll for a grid
     * that never grows. If a wider window produced no more messages, this is as far back as
     * the page goes.
     */
    fun loadMoreDetails() {
        val conversation = _state.value.open ?: return
        val syncer = sync ?: return
        val current = _details.value
        if (current.chatGuid != conversation.guid) return
        if (current.loading || current.exhausted) return
        // Not while the thread's own first page is still landing. Deliberately *not*
        // `threadJob?.isActive`, which the screen cannot observe: bailing on something
        // invisible leaves the trigger armed on a condition that never changes again, and
        // the page would simply never page for the rest of the session.
        if (_state.value.threadLoading) return
        if (current.scanned == 0) return
        val window = current.window + DETAILS_PAGE
        _details.update { it.copy(loading = true) }
        detailsJob = viewModelScope.launch(Dispatchers.IO) {
            val older = runCatching { syncer.olderThan(conversation, window) }.getOrNull()
            // See openDetails: a cancelled job's network call still returns.
            if (!isActive) return@launch
            if (_details.value.chatGuid != conversation.guid) return@launch
            if (older == null) {
                _details.update { it.copy(loading = false, exhausted = true) }
                return@launch
            }
            publishDetails(
                conversation,
                older,
                loaded = true,
                window = window,
                exhausted = older.size <= current.scanned,
            )
        }
    }

    private fun publishDetails(
        conversation: Conversation,
        messages: List<ChatMessage>,
        loaded: Boolean,
        window: Int,
        exhausted: Boolean,
    ) {
        val images = imagesIn(messages)
        val links = linksIn(messages)
        _details.update { current ->
            if (current.chatGuid != conversation.guid) {
                current
            } else {
                current.copy(
                    threadLoaded = loaded,
                    images = images,
                    links = links,
                    window = window,
                    scanned = messages.size,
                    loading = false,
                    exhausted = exhausted,
                )
            }
        }
    }

    /**
     * The key LightNotebook keeps this conversation's note under.
     *
     * Every participant's normalised handle, sorted and joined — stable across a restore,
     * across a new Mac, and across iMessage forking a group into sibling rooms, none of
     * which is true of a chat guid. Falls back to the guid only when a conversation somehow
     * has no participants, where a wrong-but-stable key still beats an empty one.
     */
    fun noteKey(conversation: Conversation): String =
        conversation.participants
            .map { imessageHandle(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(",")
            .ifBlank { conversation.guid }

    /**
     * Stars / unstars a conversation (long-press in the list), moving it between the
     * Favorites and Known tabs. Local only — BlueBubbles has no favorites concept —
     * so it's written straight through to [Store] and survives a reinstall of the
     * server but not of the app. Keyed on the primary guid, like [messageCache]: a
     * forked group's other rooms all resolve to the same conversation.
     */
    /**
     * Pin or unpin, which only ever reorders the starred list.
     *
     * Unstarring elsewhere drops the pin with it (see [toggleFavorite]), so nothing here has to
     * check whether the chat is still starred — by the time this runs it is on the Favorites tab,
     * which is the only place the verb exists.
     */
    fun togglePin(conversation: Conversation) {
        val next = Pins.toggle(_state.value.pins, conversation.guid)
        _state.update { it.copy(pins = next) }
        Store.setPins(app, next)
    }

    /** Sets (or clears, when [name] is blank) a conversation's local nickname. */
    fun setNickname(guid: String, name: String) {
        Store.setNickname(app, guid, name)
        val nick = name.trim().ifBlank { null }
        _state.update { s ->
            s.copy(
                open = s.open?.takeIf { it.guid == guid }?.copy(nickname = nick),
                conversations = s.conversations.map { c ->
                    if (c.guid == guid) c.copy(nickname = nick) else c
                },
            )
        }
    }

    fun toggleFavorite(conversation: Conversation) {
        // The write is deliberately *after* the update, not inside it: update's lambda
        // re-runs on CAS contention (a socket event landing at the same moment), which
        // would mean a duplicate SharedPreferences write and, worse, persisting a value
        // that then lost the race.
        var next = _state.value.favorites
        _state.update { s ->
            next = if (conversation.guid in s.favorites) {
                s.favorites - conversation.guid
            } else {
                s.favorites + conversation.guid
            }
            // A pin is an order within the starred list, so a chat leaving that list takes its
            // position with it. Kept here rather than in Pins because this is the one place that
            // knows a star was actually removed — Pins.order deliberately tolerates a pin for a
            // chat it cannot see, since that also happens while the list is still syncing.
            s.copy(favorites = next, pins = Pins.prune(s.pins, next))
        }
        Store.setFavorites(app, next)
        Store.setPins(app, _state.value.pins)
    }

    fun closeThread() = onThreadThread {
        // Snapshot the raw list (incl. live updates) so reopening is instant. On the thread
        // that owns openRaw, or the snapshot can be of a list a landing fetch was midway
        // through replacing — which cached one conversation's messages under another's guid.
        _state.value.open?.let {
            messageCache[it.guid] = openRaw
            finishTyping(it.guid) // don't leave a typing bubble up after leaving
        }
        openRaw = emptyList()
        threadJob?.cancel()
        // The contact page belongs to the thread; leaving the thread while it is open (the
        // Leave button does exactly that) must not leave its photos addressed to a
        // conversation that is no longer open.
        closeDetails()
        AppForeground.visibleChatGuids = emptySet()
        _state.update { it.copy(open = null, messages = emptyList(), threadLoading = false) }
    }

    /**
     * Permanently deletes a conversation from Messages on the Mac (swipe-to-delete in
     * the list). Private-API only — the server gates `DELETE /chat/:guid` on it. A
     * forked group spans several rooms, so every guid is deleted. Optimistic: the row
     * disappears immediately; if any room fails we re-pull the list so it reappears.
     */
    fun deleteConversation(conversation: Conversation) {
        if (conversation.isAgent) {
            deleteAgent(conversation.guid.removePrefix("agent:"))
            return
        }
        if (!_state.value.privateApi) {
            _state.update { it.copy(message = "Deleting needs the Private API") }
            return
        }
        val client = api ?: return
        // Drop the row now (and close it if it's the open thread); drop its cache too.
        _state.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.guid == conversation.guid },
                open = s.open?.takeUnless { it.guid == conversation.guid },
            )
        }
        onThreadThread { if (_state.value.open == null) { openRaw = emptyList(); threadJob?.cancel() } }
        messageCache.remove(conversation.guid)
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.deleteChat(conversation.guids) } }
        messageCache.remove(conversation.guid)
        viewModelScope.launch(Dispatchers.IO) {
            val failed = conversation.guids.any { runCatching { client.deleteChat(it) }.isFailure }
            // The server can take ~30s per room (it waits for the local DB), so by now
            // the list may have moved on — a refresh reconciles either way: it restores
            // a row that failed to delete, and confirms the ones that succeeded are gone.
            loadConversations()
            // After the reload (which clears `message`), surface any failure.
            if (failed) _state.update { it.copy(message = "Couldn’t delete the conversation") }
        }
    }

    // ---- Agents (separate system — see Agent.kt) --------------------------

    /** Opens an agent's thread: messages come straight off local disk, no network. */
    fun openAgentThread(agent: Agent) {
        _state.update {
            it.copy(openAgent = agent, agentMessages = agentStore.messages(agent.id), agentSending = false, message = null)
        }
    }

    fun closeAgent() {
        _state.update { it.copy(openAgent = null, agentMessages = emptyList(), agentSending = false) }
    }

    /** Optimistically appends the user's turn, calls the agent, appends its reply. */
    fun sendAgentMessage(text: String) {
        val agent = _state.value.openAgent ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.agentSending) return
        val now = System.currentTimeMillis()
        // Persist the user's turn immediately so it survives a failed reply — and take
        // the row id the insert returns. The optimistic copy used to carry id 0, and only
        // a successful reply reloads the thread from the store, so after a failed reply
        // the id-0 row stayed in state; the next send appended a second id-0 row and the
        // LazyColumn's key-uniqueness check crashed the app (light-reports#19).
        val rowId = agentStore.addMessage(agent.id, Role.USER, trimmed, now)
        val history = _state.value.agentMessages + AgentMessage(rowId, Role.USER, trimmed, now)
        _state.update { it.copy(agentMessages = history, agentSending = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reply = agentApi.complete(agent, history)
                agentStore.addMessage(agent.id, Role.ASSISTANT, reply, System.currentTimeMillis())
                _state.update {
                    if (it.openAgent?.id == agent.id) {
                        it.copy(agentMessages = agentStore.messages(agent.id), agentSending = false)
                    } else {
                        it.copy(agentSending = false)
                    }
                }
                refreshAgentList()
            } catch (t: Throwable) {
                _state.update {
                    if (it.openAgent?.id == agent.id) {
                        it.copy(agentSending = false, message = agentErrorMessage(agent, t))
                    } else {
                        it.copy(agentSending = false)
                    }
                }
            }
        }
    }

    fun openNewAgent() = _state.update { it.copy(composingNew = false, agentEditor = true, agentEditorTarget = null, message = null) }

    fun openEditAgent(agent: Agent) = _state.update { it.copy(agentEditor = true, agentEditorTarget = agent, message = null) }

    fun closeAgentEditor() = _state.update { it.copy(agentEditor = false, agentEditorTarget = null) }

    /** Creates or updates an agent, then refreshes the merged list. */
    fun saveAgent(name: String, baseUrl: String, apiKey: String, model: String, systemPrompt: String) {
        if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) return
        val target = _state.value.agentEditorTarget
        val agent = Agent(
            id = target?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            model = model.trim(),
            systemPrompt = systemPrompt,
        )
        agentStore.putAgent(agent)
        _state.update { it.copy(agentEditor = false, agentEditorTarget = null) }
        refreshAgentList()
    }

    fun deleteAgent(id: String) {
        agentStore.deleteAgent(id)
        if (_state.value.openAgent?.id == id) {
            _state.update { it.copy(openAgent = null, agentMessages = emptyList()) }
        }
        refreshAgentList()
    }

    /** Re-merges agent conversations into the list and republishes the agent roster. */
    private fun refreshAgentList() {
        val imessage = _state.value.conversations.filterNot { it.isAgent }
        _state.update { it.copy(agents = agentStore.agents(), conversations = mergeAgents(imessage)) }
    }

    /** Builds a synthetic Conversation row per agent and merges it into the list. */
    private fun mergeAgents(imessage: List<Conversation>): List<Conversation> {
        // Local nicknames override the server display name (see Store.nicknames).
        val nicknames = Store.nicknames(app)
        val named = imessage.map { it.copy(nickname = nicknames[it.guid]) }
        val roster = agentStore.agents()
        if (roster.isEmpty()) return named
        val agentRows = roster.map { a ->
            val last = agentStore.lastMessage(a.id)
            Conversation(
                guid = "agent:${a.id}",
                displayName = a.name,
                participants = emptyList(),
                isGroup = false,
                lastText = agentStore.preview(a.id) ?: "New agent",
                lastDate = last?.date ?: 0L,
                lastFromMe = last?.role == Role.USER,
                isAgent = true,
            )
        }
        return (named + agentRows).sortedByDescending { it.lastDate }
    }

    private fun agentErrorMessage(agent: Agent, t: Throwable): String {
        val code = (t as? ApiException)?.code
        return when (code) {
            401, 403 -> "${agent.name}: bad API key"
            else -> "${agent.name}: couldn’t reach — ${t.message?.take(80).orEmpty()}"
        }
    }

    /**
     * Applies [transform] to the open thread's raw messages and republishes the
     * folded view — but only if [convoGuid] is still the open thread, so a late
     * send/echo can't clobber a thread the user has since navigated away from.
     */
    private fun updateOpenThread(convoGuid: String, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        onThreadThread {
            // Membership, not equality: an incoming message may arrive on any of a forked
            // group's sibling rooms, all of which belong to the same open thread.
            if (_state.value.open?.guids?.contains(convoGuid) != true) return@onThreadThread
            setOpenRaw(transform(openRaw))
        }
    }

    /**
     * Publishes a freshly-fetched page as the open thread's messages, keeping any send still in
     * flight (see [replaceKeepingPending]) — but only if [convoGuid] is still open.
     *
     * The guard used to sit at the call site, a plain `if` around a bare assignment on the IO
     * thread. Both halves were wrong: the check and the write were not atomic, so opening
     * another chat in between published *this* chat's messages into *that* chat's thread (and
     * `closeThread` then cached them under its guid); and the assignment clobbered whatever the
     * socket or a send had put there. Doing it here, on the one thread that owns [openRaw],
     * makes the check mean something.
     */
    private fun publishFetched(convoGuid: String, fetched: List<ChatMessage>, clearLoading: Boolean = true) {
        onThreadThread {
            if (_state.value.open?.guid != convoGuid) return@onThreadThread
            setOpenRaw(replaceKeepingPending(openRaw, fetched))
            if (clearLoading) _state.update { it.copy(threadLoading = false) }
        }
    }

    /**
     * The single writer of [openRaw] and of `state.messages`.
     *
     * **The de-duplication is the crash fix, not tidiness.** The thread's `LazyColumn` is keyed
     * on the message guid and throws `Key "…" was already used` on a repeat — it takes the app
     * down mid-draw, which is light-reports#21 and #22. Until now that could not happen only
     * because every writer independently guaranteed it (`mergeIntoThread` drops rivals,
     * `reconcileEcho` de-duplicates, the store's cross-room read de-duplicates); the invariant
     * was emergent, held in five places at once, and the sixth writer to be added would have
     * reopened the crash silently. Enforcing it at the render boundary makes a duplicate key
     * unrepresentable instead of unlikely.
     */
    private fun setOpenRaw(next: List<ChatMessage>) {
        openRaw = next.distinctBy { it.guid }
        val folded = foldReactions(openRaw)
        _state.update { it.copy(messages = folded) }
    }

    /**
     * Runs [block] on the thread that owns [openRaw].
     *
     * [openRaw] is a plain field doing read-modify-write, and it used to be touched from both
     * the main thread (the socket collector, the text send's optimistic row) and IO (the photo
     * sends, every fetch). Two of those interleaving is a lost update, and lost updates here are
     * not cosmetic: a batch of photos would drop a bubble that was still uploading, and the
     * reconcile that followed had nothing left to swap the real message into. `Main.immediate`
     * so a call already on the main thread runs inline — the transforms are microseconds, and
     * every slow part (the upload, the fetch) is outside them.
     */
    private fun onThreadThread(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    /**
     * Folds tapback messages onto their targets: a reaction message isn't shown as
     * its own row but attached to the message it targets as a [Reaction]. A reactor
     * holds at most one tapback per message, so we key by (target, reactor) and let
     * the latest add win — a removal (3000s) clears it. Group-event rows we can't
     * describe (an itemType with no [GroupEvent] mapping — e.g. FaceTime call
     * markers) are dropped here too: they have no text and would render as blank
     * turns. Output is sorted by date.
     */
    private fun foldReactions(raw: List<ChatMessage>): List<ChatMessage> {
        val rows = raw.filterNot { it.isGroupEvent && it.groupEvent == null }
        if (rows.none { it.isReaction }) return rows.sortedBy { it.date }
        val active = LinkedHashMap<Pair<String, String>, Reaction?>()
        for (r in rows.filter { it.isReaction }.sortedBy { it.date }) {
            val target = r.reactionTargetGuid ?: continue
            val type = r.reactionType ?: continue
            val reactorKey = if (r.fromMe) "me" else (r.sender ?: "?")
            active[target to reactorKey] = if (r.isReactionRemoval) null else Reaction(type, r.fromMe, r.sender)
        }
        val byTarget = HashMap<String, MutableList<Reaction>>()
        for ((key, reaction) in active) {
            if (reaction != null) byTarget.getOrPut(key.first) { mutableListOf() }.add(reaction)
        }
        return rows.asSequence()
            .filterNot { it.isReaction }
            .map { m -> byTarget[m.guid]?.let { m.copy(reactions = it) } ?: m }
            .sortedBy { it.date }
            .toList()
    }

    // ---- Sending ----------------------------------------------------------

    /** The send method for text/attachments: the Private API when it's live, else
     *  AppleScript. The Private API is more capable (it sends by DB identity, so it
     *  handles any room of a forked group); the AppleScript path is pickier about the
     *  room guid — see [sendTarget]. */
    private fun sendMethod() = if (_state.value.privateApi) "private-api" else "apple-script"

    /**
     * The room guids to try when sending [convo], best first. The Private API resolves
     * a chat by its DB identity, so the primary (`convo.guid`) alone is enough.
     * AppleScript is pickier — its `chat id "…"` lookup can't resolve a *dead* room of
     * a forked group (it throws -1728 "Can't get chat id", which sends the server into
     * the DM-only fallback script that rejects groups: "Can't use the send message
     * (fallback) script to text a group chat!"). The live sibling resolves and delivers
     * fine. So for AppleScript we hand back *every* sibling room and let [sendAcrossRooms]
     * try each until one delivers — the last room that delivered (cached in
     * [lastGoodRoom]) first, then UUID-form rooms ahead of `chat<number>` forms, since
     * those tend to resolve. A 1:1 or single-room group is just `[guid]`.
     */
    private fun sendTargets(convo: Conversation, method: String): List<String> {
        if (method != "apple-script") return listOf(convo.guid)
        val ordered = convo.guids.sortedBy { it.substringAfterLast(";").startsWith("chat") }
        val good = lastGoodRoom[convo.guid]?.takeIf { it in convo.guids } ?: return ordered
        return listOf(good) + ordered.filter { it != good }
    }

    /**
     * Sends via the first of [targets] that succeeds; records the winning room in
     * [lastGoodRoom] under [convoGuid] so the next send tries it first, and returns its
     * result. Rethrows the last error if all fail. Lets an AppleScript send fall through
     * a forked group's dead rooms to the live one. Safe against double-sending: the
     * dead-room failure (-1728) happens during chat resolution, before any message goes
     * out.
     */
    private fun <T> sendAcrossRooms(convoGuid: String, targets: List<String>, send: (String) -> T): T {
        var last: Throwable? = null
        for (guid in targets) {
            try {
                val result = send(guid)
                lastGoodRoom[convoGuid] = guid
                return result
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("no send targets")
    }

    /** A client-side guid for an optimistic message, swapped for the server echo's
     *  real guid on reconcile. The `temp-` prefix marks a not-yet-acked message. */
    private fun newTempGuid(prefix: String = "temp") =
        "$prefix-${System.currentTimeMillis()}-${(0..99999).random()}"

    /** Swaps the optimistic [tempGuid] row for the server's [sent] echo, deduping
     *  in case the socket echo already landed under the real guid. */
    private fun reconcileEcho(convoGuid: String, tempGuid: String, sent: ChatMessage) {
        updateOpenThread(convoGuid) { list ->
            list.map { if (it.guid == tempGuid) sent else it }.distinctBy { it.guid }
        }
    }

    /** Drops the optimistic [tempGuid] row after a failed send and surfaces [error]. */
    private fun rollbackOptimistic(convoGuid: String, tempGuid: String, error: String) {
        updateOpenThread(convoGuid) { list -> list.filterNot { it.guid == tempGuid } }
        _state.update { it.copy(message = error) }
    }

    /**
     * Shared tail of [sendMessage]/[sendImage]: runs [call] against the first room
     * of [convo] that delivers (see [sendAcrossRooms]), then reconciles the
     * optimistic [tempGuid] row with the echo and bumps the conversation list —
     * or rolls the optimistic row back with [errorMessage]. Call off-main.
     */
    private fun performSend(
        convo: Conversation,
        tempGuid: String,
        errorMessage: String,
        call: (BlueBubblesApi, String, String) -> ChatMessage,
    ) {
        val client = api ?: return
        try {
            val method = sendMethod()
            val sent = sendAcrossRooms(convo.guid, sendTargets(convo, method)) { g ->
                call(client, g, method)
            }
            reconcileEcho(convo.guid, tempGuid, sent)
            bumpConversation(convo.guid, sent.previewText, sent.date, fromMe = true)
        } catch (t: Throwable) {
            rollbackOptimistic(convo.guid, tempGuid, errorMessage)
        }
    }

    /** Sends [text] into the open thread — as an inline reply to [replyToGuid]
     *  when given (Private-API only; ignored otherwise, and a not-yet-acked temp
     *  guid can't be replied to). */
    fun sendMessage(text: String, replyToGuid: String? = null) {
        val body = text.trim()
        val convo = _state.value.open ?: return
        if (body.isEmpty()) return
        finishTyping(convo.guid) // sending clears our typing bubble
        val reply = replyToGuid?.takeIf { _state.value.privateApi && !it.startsWith("temp-") }
        // Optimistic: show it immediately under a temp guid, then swap in the
        // server's echo (real guid) so the socket's new-message dedupes cleanly.
        val tempGuid = newTempGuid()
        val optimistic = ChatMessage(
            tempGuid, body, System.currentTimeMillis(), fromMe = true, sender = null,
            threadOriginatorGuid = reply,
        )
        updateOpenThread(convo.guid) { it + optimistic }
        _state.update { it.copy(message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            performSend(convo, tempGuid, "Couldn’t send") { client, g, method ->
                client.send(g, body, tempGuid, method, reply)
            }
        }
    }

    // ---- Tapbacks ---------------------------------------------------------

    /**
     * Sends a tapback onto [target] (or removes it, if [target] already carries
     * mine of [type] — long-pressing the same reaction toggles it off). Mirrors
     * [sendMessage]: an optimistic reaction message is folded in immediately, then
     * reconciled with the server's echo. Gated on [UiState.privateApi]; can't react
     * to a not-yet-acked optimistic message (no real guid to target).
     */
    fun sendReaction(target: ChatMessage, type: ReactionType) {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi) return
        if (target.guid.startsWith("temp-")) {
            _state.update { it.copy(message = "Still sending — try again in a moment") }
            return
        }
        val removing = target.reactions.firstOrNull { it.fromMe }?.type == type
        val apiValue = if (removing) "-${type.apiValue}" else type.apiValue
        val tempGuid = newTempGuid("temp-react")
        val optimistic = ChatMessage(
            guid = tempGuid,
            text = "",
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            associatedMessageGuid = target.guid,
            associatedMessageType = apiValue, // "love" or "-love"
        )
        updateOpenThread(convo.guid) { it + optimistic }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val sent = client.react(convo.guid, target.guid, apiValue)
                reconcileEcho(convo.guid, tempGuid, sent)
            } catch (t: Throwable) {
                rollbackOptimistic(convo.guid, tempGuid, "Couldn’t react")
            }
        }
    }

    // ---- Group management (Private API) ------------------------------------

    /**
     * Renames the open group — across *all* its rooms (best-effort), so a forked
     * group's dead siblings keep the same name and the name+participants merge key
     * holds instead of splitting the row. Succeeds if any room renamed; the
     * `group-name-change` socket echo and the refresh reconcile the rest.
     */
    fun renameGroup(name: String) {
        val convo = _state.value.open ?: return
        val newName = name.trim()
        if (!_state.value.privateApi || !convo.isGroup || newName.isEmpty()) return
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val results = convo.guids.map { g -> runCatching { client.renameChat(g, newName) } }
            if (results.none { it.isSuccess }) {
                _state.update { it.copy(message = "Couldn’t rename") }
                return@launch
            }
            _state.update { s ->
                s.copy(
                    open = s.open?.takeIf { it.guid == convo.guid }?.copy(displayName = newName) ?: s.open,
                    conversations = s.conversations.map { c ->
                        if (c.guid == convo.guid) c.copy(displayName = newName) else c
                    },
                )
            }
            loadConversations()
        }
    }

    /** Adds [address] to the open group. iMessage may fork the group into a new
     *  room for the new membership — the refresh reflects whatever it did. */
    fun addMember(address: String) {
        val convo = _state.value.open ?: return
        val addr = address.trim()
        if (!_state.value.privateApi || !convo.isGroup || addr.isEmpty()) return
        val client = api ?: return
        _state.update { it.copy(message = "Adding…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.addParticipant(convo.guid, addr)
                _state.update { s ->
                    s.copy(
                        open = s.open?.takeIf { it.guid == convo.guid }
                            ?.let { it.copy(participants = it.participants + addr) } ?: s.open,
                        message = null,
                    )
                }
                loadConversations()
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t add — are they on iMessage?") }
            }
        }
    }

    /** Removes [address] from the open group. */
    fun removeMember(address: String) {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi || !convo.isGroup) return
        val client = api ?: return
        _state.update { it.copy(message = "Removing…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.removeParticipant(convo.guid, address)
                _state.update { s ->
                    s.copy(
                        open = s.open?.takeIf { it.guid == convo.guid }
                            ?.let { o -> o.copy(participants = o.participants.filterNot { it == address }) }
                            ?: s.open,
                        message = null,
                    )
                }
                loadConversations()
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t remove them") }
            }
        }
    }

    /** Leaves the open group (every room of a forked group, best-effort), then
     *  drops back to the list. iMessage refuses on too-small groups — that
     *  surfaces as the failure message. */
    fun leaveGroup() {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi || !convo.isGroup) return
        val client = api ?: return
        _state.update { it.copy(message = "Leaving…") }
        viewModelScope.launch(Dispatchers.IO) {
            val results = convo.guids.map { g -> runCatching { client.leaveChat(g) } }
            if (results.none { it.isSuccess }) {
                _state.update { it.copy(message = "Couldn’t leave the conversation") }
                return@launch
            }
            closeThread()
            _state.update { it.copy(message = null) }
            loadConversations()
        }
    }

    /** Whether [address] can receive iMessages, delivered via [onResult] (skipped
     *  entirely when the Private API is down — the check needs it — or on a failed
     *  call, so "unknown" never blocks anyone). */
    fun checkIMessage(address: String, onResult: (Boolean) -> Unit) {
        if (!_state.value.privateApi) return
        val client = api ?: return
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) {
                runCatching { client.iMessageAvailable(address) }.getOrNull()
            }
            if (available != null) onResult(available)
        }
    }

    // ---- Attachments ------------------------------------------------------

    /** Decoded inline image for [attachment] (downloaded + cached on first use),
     *  or null if it isn't an image / can't be fetched. Called from the thread UI. */
    suspend fun loadImage(attachment: Attachment): ImageBitmap? {
        val client = api ?: return null
        return Attachments.image(app, client, attachment)
    }

    /** The small decode, for the contact page's grid. Same file on disk as [loadImage]. */
    suspend fun loadThumbnail(attachment: Attachment): ImageBitmap? {
        val client = api ?: return null
        return Attachments.thumbnail(app, client, attachment)
    }

    /**
     * The attachment's file, downloaded and cached but not decoded — for a GIF, which is played
     * from the file rather than decoded to a bitmap. See [Attachments.file].
     */
    suspend fun loadImageFile(attachment: Attachment): File? {
        val client = api ?: return null
        return Attachments.file(app, client, attachment)
    }

    /**
     * Opens a non-image attachment: downloads it to a FileProvider-shared cache file,
     * then hands off to an external app via `ACTION_VIEW`. Falls back to a share
     * chooser, then a message if nothing on the (minimal) device can handle it.
     */
    /**
     * Downloads [attachment] and hands the file back, for something this app can show itself.
     *
     * The download half of [openAttachment] without the hand-off. Split rather than parameterised
     * because the two differ in what they do on success and in nothing else: one starts an
     * activity, the other calls back. The cache path and the "already downloaded" check are
     * shared through [attachmentFile] so a video watched twice is fetched once.
     */
    /**
     * Turn a recording into words, and remember them.
     *
     * The transcript is cached against the attachment's guid, so a voice memo opened twice is
     * transcribed once — this is slow and, pointed at a paid endpoint, billed.
     *
     * [onResult] is handed the words, or null with a message already set on the state when the
     * server would not do it. Off the main thread throughout: the request holds open for as long as
     * the transcription takes, which for a few minutes of audio on a CPU-only server is a minute of
     * its own.
     */
    /**
     * Re-read whether transcription is set up.
     *
     * Called by the settings screen every time one of the three fields is committed or a QR code is
     * scanned. The alternative — each composer reading the store as it composes — is what made the
     * key stale before: set a server, go back to a thread that never left the composition, and the
     * key still believed there was nothing there.
     */
    fun transcriptionChanged() {
        _state.update { it.copy(canTranscribe = Store.canTranscribe(app)) }
    }

    /**
     * How many Whisper requests are in flight. Drives [UiState.transcribing], which is what holds
     * the screen on while one runs — see `KeepScreenOn` in MainActivity.
     *
     * A count and not a boolean: transcribing a received clip and dictating a reply to it are the
     * same operation against the same server, and nothing stops both being outstanding at once.
     * With a flag, whichever finished first would turn the screen off on the other.
     *
     * Atomic because the two ends are on different threads: it goes up on the caller's thread (the
     * tap) and comes down on the IO dispatcher where the request finished.
     */
    private val whisperCalls = AtomicInteger(0)

    private fun beginTranscribing() {
        whisperCalls.incrementAndGet()
        _state.update { it.copy(transcribing = true) }
    }

    private fun endTranscribing() {
        if (whisperCalls.decrementAndGet() <= 0) {
            whisperCalls.set(0)
            _state.update { it.copy(transcribing = false) }
        }
    }

    fun transcribe(attachment: Attachment, file: File, onResult: (String?) -> Unit) {
        val context = app
        Store.transcript(context, attachment.guid)?.let {
            onResult(it)
            return
        }
        val url = Store.whisperUrl(context)
        if (url.isNullOrBlank()) {
            _state.update { it.copy(message = "Set a transcription server in Settings first") }
            onResult(null)
            return
        }
        _state.update { it.copy(message = "Transcribing…") }
        beginTranscribing()
        viewModelScope.launch(Dispatchers.IO) {
            val words = runCatching {
                WhisperApi().transcribe(
                    baseUrl = url,
                    apiKey = Store.whisperKey(context),
                    model = Store.whisperModel(context),
                    file = file,
                )
            }
            endTranscribing()
            val text = words.getOrNull()?.takeIf { it.isNotBlank() }
            if (text != null) Store.setTranscript(context, attachment.guid, text)
            _state.update {
                it.copy(
                    message = when {
                        text != null -> null
                        // The server's own wording where there is one: "the key is wrong" is more
                        // use than "transcription failed".
                        words.exceptionOrNull() is ApiException ->
                            (words.exceptionOrNull() as ApiException).message
                        words.isFailure -> "Couldn't reach the transcription server"
                        else -> "Nothing was said in that recording"
                    },
                )
            }
            withContext(Dispatchers.Main) { onResult(text) }
        }
    }

    /**
     * Transcribe something just dictated, and hand back the words.
     *
     * Not [transcribe]: that one is about an attachment somebody sent, and caches the result against
     * its guid. Dictation has no guid and nothing worth caching — it is said once, turned into text,
     * and the recording is deleted whether it worked or not, because it is a draft of a message and
     * not a message.
     */
    fun transcribeDictation(file: File, onResult: (String?) -> Unit) {
        val context = app
        val url = Store.whisperUrl(context)
        if (url.isNullOrBlank()) {
            _state.update { it.copy(message = "Set a transcription server in Settings first") }
            file.delete()
            onResult(null)
            return
        }
        _state.update { it.copy(message = "Transcribing…") }
        beginTranscribing()
        viewModelScope.launch(Dispatchers.IO) {
            val words = runCatching {
                WhisperApi().transcribe(
                    baseUrl = url,
                    apiKey = Store.whisperKey(context),
                    model = Store.whisperModel(context),
                    file = file,
                )
            }
            endTranscribing()
            file.delete()
            val text = words.getOrNull()?.takeIf { it.isNotBlank() }
            _state.update {
                it.copy(
                    message = when {
                        text != null -> null
                        words.exceptionOrNull() is ApiException ->
                            (words.exceptionOrNull() as ApiException).message
                        words.isFailure -> "Couldn't reach the transcription server"
                        else -> "Didn't catch that"
                    },
                )
            }
            withContext(Dispatchers.Main) { onResult(text) }
        }
    }

    /**
     * Put a short line in front of the user.
     *
     * The state's `message` is how everything else in this class says something went wrong, and the
     * UI needs the same channel for the couple of things it discovers on its own — a microphone that
     * would not open, a dictation with nothing in it.
     */
    fun say(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun downloadAttachment(attachment: Attachment, onReady: (File) -> Unit) {
        val client = api ?: return
        // An optimistic row carries the send's temp guid, which the server has never
        // heard of — asking it to stream that back returns a 404 and the user is told
        // the download failed for a file that is still on its way *up*. Only video
        // reaches this path from an outgoing row (a photo renders inline from the local
        // cache), so this is new with sending clips.
        if (attachment.guid.startsWith("temp-")) {
            _state.update { it.copy(message = "Still sending — it'll play once it's sent") }
            return
        }
        _state.update { it.copy(message = "Downloading…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = attachmentFile(attachment)
                if (!dest.exists() || dest.length() == 0L) client.downloadAttachment(attachment.guid, dest)
                _state.update { it.copy(message = null) }
                withContext(Dispatchers.Main) { onReady(dest) }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn't download attachment") }
            }
        }
    }

    /** Where an attachment lands in the cache. Shared so it is fetched once, not once per verb. */
    private fun attachmentFile(attachment: Attachment): File {
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val safe = (attachment.transferName ?: attachment.guid)
            .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { attachment.guid }
        return File(dir, safe)
    }

    fun openAttachment(attachment: Attachment) {
        val client = api ?: return
        _state.update { it.copy(message = "Downloading…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = attachmentFile(attachment)
                if (!dest.exists() || dest.length() == 0L) client.downloadAttachment(attachment.guid, dest)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dest)
                val mime = attachment.mimeType ?: "application/octet-stream"
                _state.update { it.copy(message = null) }
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    app.startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, "Open with")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { app.startActivity(chooser) }
                        .onFailure { _state.update { s -> s.copy(message = "No app can open this file") } }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t download attachment") }
            }
        }
    }

    /**
     * Sends photos and videos chosen in [com.gios.lightchat.ui.PhotoPickerScreen], as
     * separate attachments, in the order they were picked. One coroutine rather than
     * one per file: iMessage has no concept of a batch, so these are N sends, and
     * letting them race would land them out of order in the thread.
     */
    fun sendImageFiles(files: List<File>) {
        val convo = _state.value.open ?: return
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (file in files) {
                if (isStreamedFile(file)) {
                    sendPickedFile(convo, file)
                } else {
                    sendPicked(convo, readPickedImage(file) ?: continue)
                }
            }
        }
    }

    /**
     * Sends a GIF from the picker.
     *
     * **The file goes out, not the URL.** A link would arrive as a link — iMessage would show the
     * recipient a preview card for somebody's CDN, and half of them would see nothing at all —
     * whereas the bytes arrive as an ordinary `image/gif` attachment that Messages plays inline on
     * every Apple device. It also means what was sent is what was seen: the GIF cannot be
     * re-pointed or taken down afterwards.
     *
     * Fetching it first is the reason this is not simply [sendImageFiles]. The picker has only the
     * preview rendition on the phone at the point you press Send, so the sendable one is downloaded
     * here — and a failed download has to be said out loud, because there is no optimistic bubble
     * yet to go wrong in front of you. Once the file is local, the send is exactly the photo path:
     * bytes seeded into the image cache, optimistic bubble, echo reconciled.
     *
     * The provider is told afterwards ([Gifs.reportShare]) and never before: it is their ranking
     * signal, their terms ask for it, and it must not be able to fail a send.
     */
    fun sendGif(gif: Gif) {
        val convo = _state.value.open ?: return
        _state.update { it.copy(message = "Fetching GIF…") }
        viewModelScope.launch(Dispatchers.IO) {
            val file = Gifs.file(app, gif)
            if (file == null) {
                _state.update { it.copy(message = "Couldn’t fetch that GIF") }
                return@launch
            }
            val picked = readPickedImage(file)
            if (picked == null) {
                _state.update { it.copy(message = "Couldn’t fetch that GIF") }
                return@launch
            }
            // Remembered on the send rather than on the tap, so the Recent tab lists what actually
            // went out to somebody and not what was looked at.
            Gifs.remember(app, gif)
            sendPicked(convo, picked)
            Gifs.reportShare(app, gif)
        }
    }

    /**
     * Sends one picked clip.
     *
     * Deliberately not [sendPicked] with a bigger byte array. Two things differ and
     * both matter on this phone:
     *
     * - **The bytes never enter the heap.** The optimistic bubble for a photo works by
     *   seeding the image cache with the file's contents so the normal loader draws it
     *   with no round trip. There is nothing to seed for a clip — the thread renders a
     *   video as a tappable file row, not inline — so the file is handed to the API as
     *   a [File] and streamed. Reading a 100MB recording into a `ByteArray` to send it
     *   is how this would OOM.
     * - **It can be refused before anything is sent.** Over [MAX_VIDEO_BYTES] we say so
     *   and stop, rather than starting an upload that will spend minutes failing.
     *
     * The optimistic row still goes up, so a long upload isn't a dead screen; it
     * carries the clip's name and reconciles against the echo exactly as a photo does.
     */
    private fun sendPickedFile(convo: Conversation, file: File) {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) {
            _state.update { it.copy(message = "Couldn’t read that video") }
            return
        }
        if (length > MAX_VIDEO_BYTES) {
            _state.update {
                it.copy(message = "That ${kindOf(file)} is too large to send (${length / 1_000_000}MB)")
            }
            return
        }
        val mime = mimeForExtension(file.extension)
        val tempGuid = newTempGuid()
        val optimistic = ChatMessage(
            guid = tempGuid,
            text = ChatMessage.ATTACHMENT_PLACEHOLDER,
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            attachments = listOf(Attachment(tempGuid, mime, file.name, width = 0, height = 0)),
        )
        updateOpenThread(convo.guid) { it + optimistic }
        _state.update { it.copy(message = null) }
        // Blocking, deliberately: sendImageFiles loops over this on one IO coroutine so
        // several files land in the thread in the order they were picked. It also means
        // a big clip holds up the ones behind it, which is the correct trade — the
        // alternative is a parallel upload competing for the same tunnel.
        performSend(convo, tempGuid, "Couldn’t send video") { client, g, method ->
            client.sendAttachment(g, file, file.name, mime, tempGuid, method)
        }
    }

    /** See [MediaKind]. Thin wrappers so the call sites read as they did. */
    private fun isVideoFile(file: File): Boolean = MediaKind.isVideo(file)

    private fun isAudioFile(file: File): Boolean = MediaKind.isAudio(file)

    private fun isStreamedFile(file: File): Boolean = MediaKind.isStreamed(file)

    private fun kindOf(file: File): String = MediaKind.label(file)

    /**
     * Shared body of the image sends. Mirrors [sendMessage]: an optimistic bubble goes
     * up immediately — the photo's bytes are seeded into the image cache under the temp
     * guid, so the normal loader renders them with no round trip — then the server's
     * echo swaps in under the real guid and the socket dedupes.
     */
    private fun sendPicked(convo: Conversation, img: PickedImage) {
        val tempGuid = newTempGuid()
        // Seed the cache so the optimistic bubble renders the local image.
        Attachments.cacheLocal(app, tempGuid, img.bytes)
        val optimistic = ChatMessage(
            guid = tempGuid,
            text = ChatMessage.ATTACHMENT_PLACEHOLDER,
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            attachments = listOf(Attachment(tempGuid, img.mime, img.name, width = 0, height = 0)),
        )
        updateOpenThread(convo.guid) { it + optimistic }
        _state.update { it.copy(message = null) }
        // Blocking, deliberately: sendImageFiles loops over this on one IO coroutine
        // so several photos land in the thread in the order they were picked.
        performSend(convo, tempGuid, "Couldn’t send image") { client, g, method ->
            client.sendAttachment(g, img.bytes, img.name, img.mime, tempGuid, method)
        }
    }

    /** A picked image's bytes plus the metadata a send needs. */
    private class PickedImage(val bytes: ByteArray, val mime: String, val name: String)

    /** Reads a photo straight off disk — the picker hands us [java.io.File]s from
     *  [Gallery], not content URIs, so there is no provider to ask for the type or
     *  the display name. */
    private fun readPickedImage(file: File): PickedImage? {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            _state.update { it.copy(message = "Couldn’t read that photo") }
            return null
        }
        return PickedImage(bytes, mimeForExtension(file.extension), file.name)
    }

    private fun mimeForExtension(extension: String): String = MediaKind.mimeOf(extension)

    /**
     * The largest clip this app will attempt.
     *
     * Not a limit iMessage imposes — the ceiling there is around 100MB and varies with
     * how the message is routed — but the point at which the send stops being worth
     * starting: over this it will take minutes on a phone tunnelling through Tailscale
     * to a Mac, with no progress to show for it, and is as likely to be rejected at the
     * far end as delivered. Refusing up front with the size named is the honest answer.
     */
    private val MAX_VIDEO_BYTES = 100L * 1024 * 1024

    /**
     * Sends a picked image as the first message of a *new* 1:1. There's no chat
     * guid yet, so we construct the canonical BlueBubbles 1:1 guid
     * (`iMessage;-;<handle>`) — sending an attachment to it creates the chat
     * server-side — then drop into the thread and refresh the list. The address is
     * normalized to the E.164 handle iMessage keys its guids by (`newChat`'s
     * AppleScript resolves loose addresses for text, but a constructed guid can't).
     */
    fun sendNewImage(address: String, file: File) {
        val addr = address.trim()
        if (addr.isEmpty()) return
        _state.update { it.copy(composingNew = false, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            val video = isStreamedFile(file)
            // A clip is streamed off disk rather than read into a ByteArray, for the
            // reason given on sendPickedVideo; a still keeps the existing read, which
            // also validates that the file is there before a chat gets created for it.
            val img = if (video) null else (readPickedImage(file) ?: return@launch)
            if (video && file.length() > MAX_VIDEO_BYTES) {
                _state.update { it.copy(message = "That ${kindOf(file)} is too large to send") }
                return@launch
            }
            val handle = imessageHandle(addr)
            val guid = "iMessage;-;$handle"
            try {
                if (img != null) {
                    client.sendAttachment(guid, img.bytes, img.name, img.mime, newTempGuid(), sendMethod())
                } else {
                    client.sendAttachment(
                        guid, file, file.name, mimeForExtension(file.extension),
                        newTempGuid(), sendMethod(),
                    )
                }
                messageCache.remove(guid)
                val convo = Conversation(
                    guid = guid,
                    displayName = "",
                    participants = listOf(handle),
                    isGroup = false,
                    lastText = if (video) "[Video]" else "[Photo]",
                    lastDate = System.currentTimeMillis(),
                    lastFromMe = true,
                )
                _state.update { it.copy(message = null) }
                open(convo)   // land in the new thread (fetch pulls the sent image)
                refresh()     // and pull it into the conversation list
            } catch (t: Throwable) {
                val fallback = if (video) "Couldn’t send video" else "Couldn’t send image"
                _state.update { it.copy(message = t.message ?: fallback) }
            }
        }
    }

    /**
     * Photographs shared in from another app, optionally already addressed.
     *
     * With a recipient this is the whole point of the feature: Roll asked who the photograph
     * was for, so there is nothing left to choose and the send goes straight out — the thread
     * opens with the picture already in it rather than opening a picker the user has just
     * used.
     *
     * **Two shapes of recipient, because a group is not a person.** [address] is a handle and
     * addresses a 1:1 thread, whose guid can simply be *constructed* from it — which is what
     * makes it work for a thread that doesn't exist yet. A group has no handle at all: it is a
     * room on the server, and the only way to name it is [chatGuid], which the sender got from
     * this app's own ChatsProvider. So a guid, when there is one, is used verbatim and no guid
     * is constructed — constructing one for a group is exactly the bug this avoids, since
     * `iMessage;-;<anything>` is by definition a two-person chat and the photograph would go to
     * one member instead of the room.
     *
     * Without either, they are held and the user is put on the conversation list to pick a
     * thread. Only a share from a chooser that could not name the recipient reaches that
     * branch, and guessing would be worse than asking.
     *
     * Sequential rather than parallel, like [sendImageFiles]: iMessage has no batch send and
     * racing several attachments lands them out of order.
     */
    fun receiveShared(address: String, chatGuid: String, files: List<File>) {
        if (files.isEmpty()) return
        if (address.isBlank() && chatGuid.isBlank()) {
            pendingShared = files
            AppForeground.visibleChatGuids = emptySet()
            _state.update {
                it.copy(
                    open = null,
                    composingNew = false,
                    message = if (files.size == 1) {
                        "Open a chat to send the photo"
                    } else {
                        "Open a chat to send ${files.size} photos"
                    },
                )
            }
            return
        }
        AppForeground.visibleChatGuids = emptySet()
        _state.update { it.copy(composingNew = false, open = null, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            val handle = if (chatGuid.isBlank()) imessageHandle(address) else ""
            // A group's guid is handed over as-is. A person's is constructed rather than looked
            // up, exactly as sendNewImage does: a 1:1 chat's guid *is* its handle, so this
            // addresses an existing thread and creates one that doesn't exist without needing to
            // know which case it is. There is no equivalent trick for a room, which is why the
            // group case needs the guid passed in rather than derived.
            val guid = chatGuid.ifBlank { "iMessage;-;$handle" }
            var sent = 0
            for (file in files) {
                // A clip streams off disk. Roll records video now, so a share reaching
                // this loop can be one — and readPickedImage on a 100MB recording is a
                // single allocation this phone will not give us.
                val ok = if (isStreamedFile(file)) {
                    file.length() in 1..MAX_VIDEO_BYTES && runCatching {
                        client.sendAttachment(
                            guid, file, file.name, mimeForExtension(file.extension),
                            newTempGuid(), sendMethod(),
                        )
                    }.isSuccess
                } else {
                    val img = readPickedImage(file)
                    img != null && runCatching {
                        client.sendAttachment(guid, img.bytes, img.name, img.mime, newTempGuid(), sendMethod())
                    }.isSuccess
                }
                if (ok) sent++
                // The copy in our cache has served its purpose either way; leaving it means
                // every shared photo stays on the phone twice.
                runCatching { file.delete() }
            }
            if (sent == 0) {
                _state.update { it.copy(message = "Couldn’t send that photo") }
                return@launch
            }
            messageCache.remove(guid)
            // The stored row first, so a group opens with its real name and members instead of
            // the placeholder below — which for a group would be a nameless chat with an empty
            // participant list, i.e. a thread titled "Unknown" for a room the user just picked
            // by name. The fallback still matters for the 1:1 case, where the thread may not
            // exist on this phone yet.
            val convo = store.chat(guid)?.copy(
                lastText = if (sent == 1) "[Photo]" else "[$sent Photos]",
                lastDate = System.currentTimeMillis(),
                lastFromMe = true,
            ) ?: Conversation(
                guid = guid,
                displayName = "",
                participants = listOfNotNull(handle.takeIf { it.isNotBlank() }),
                isGroup = false,
                lastText = if (sent == 1) "[Photo]" else "[$sent Photos]",
                lastDate = System.currentTimeMillis(),
                lastFromMe = true,
            )
            _state.update { it.copy(message = null) }
            open(convo)
            refresh()
        }
    }

    /**
     * Photographs shared in without a recipient, waiting for a thread to be opened.
     *
     * In memory only: a share the user abandons should not still be pending tomorrow, and the
     * files are in the cache directory, which the system may clear whenever it likes.
     */
    private var pendingShared: List<File> = emptyList()

    /** Called from [open]: if a share is waiting, the thread just opened is its destination. */
    private fun flushPendingShared() {
        val waiting = pendingShared
        if (waiting.isEmpty()) return
        pendingShared = emptyList()
        sendImageFiles(waiting)
    }

    /** Normalizes a picked address to the E.164 (or lowercased email) handle that
     *  iMessage keys 1:1 chat guids by. US-centric on the country code, matching the
     *  single personal account this app serves. */
    private fun imessageHandle(address: String): String {
        val a = address.trim()
        if (a.contains("@")) return a.lowercase()
        val digits = a.filter { it.isDigit() }
        return when {
            a.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> a
        }
    }

    // ---- New message ------------------------------------------------------

    fun startNewMessage() = _state.update { it.copy(composingNew = true, message = null) }

    fun cancelNewMessage() = _state.update { it.copy(composingNew = false, message = null) }

    /**
     * Starts a fresh chat with [addresses] by sending [text], then opens it. One
     * address is a 1:1 (AppleScript); two or more form a group, which the server
     * only creates over the Private API — so a group send is gated on
     * `state.privateApi` (the picker also hides the option, this is the backstop).
     * The group's guid is server-assigned, so we open on whatever `newChat` returns.
     */
    fun sendNewMessage(addresses: List<String>, text: String) {
        val addrs = addresses.map { it.trim() }.filter { it.isNotEmpty() }
        val body = text.trim()
        if (addrs.isEmpty() || body.isEmpty()) return
        val isGroup = addrs.size > 1
        if (isGroup && !_state.value.privateApi) {
            _state.update { it.copy(message = "Group messaging needs the server’s Private API") }
            return
        }
        _state.update { it.copy(composingNew = false, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val guid = client.newChat(addrs, body)
                messageCache.remove(guid)
                val convo = Conversation(
                    guid = guid,
                    displayName = "",
                    participants = addrs,
                    isGroup = isGroup,
                    lastText = body,
                    lastDate = System.currentTimeMillis(),
                    lastFromMe = true,
                )
                _state.update { it.copy(message = null) }
                open(convo)   // land the user in the new thread
                refresh()     // and pull it into the conversation list
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "Couldn’t start the message") }
            }
        }
    }

    // ---- Newsletter -------------------------------------------------------

    /**
     * How long to wait between recipients of a broadcast.
     *
     * Not politeness — it is the AppleScript path. Each send drives Messages.app on the Mac
     * through an Apple Event, and firing forty of them back to back is how that path starts
     * dropping them silently: the script returns success, the message never leaves. A pause
     * costs a broadcast to forty people about twelve seconds, which is nothing next to a
     * recipient who never hears from you and never finds out.
     */
    private val newsletterGapMs = 300L

    /** The broadcast currently in flight, if any. One at a time — see [sendNewsletter]. */
    private var newsletterJob: Job? = null

    /**
     * What the last broadcast was carrying, kept so the resend button has something to send.
     *
     * The photo *bytes* rather than the files: a picture attached minutes ago can be evicted
     * from the cache between the send and the retry, and a resend that failed because the file
     * had gone would be indistinguishable from one that failed because the chat was
     * unreachable. Held for one batch at a time, dropped when the composer is cleared.
     */
    private class NewsletterPayload(
        val batch: NewsletterBatch,
        val body: String,
        val photos: List<PickedImage>,
    )

    private var newsletterPayload: NewsletterPayload? = null

    fun openNewsletters() = _state.update {
        it.copy(composingNew = false, newsletterList = true, newsletterProgress = null, message = null)
    }

    fun closeNewsletters() = _state.update {
        it.copy(newsletterList = false, newsletterEditor = null, newsletterCompose = null)
    }

    /** Opens the editor on a brand-new, empty batch. Not written to disk until it is
     *  saved — an abandoned "New batch" should leave nothing behind. */
    fun newNewsletterBatch() = _state.update {
        it.copy(
            newsletterEditor = NewsletterBatch(id = newTempGuid("nl"), name = ""),
            newsletterProgress = null,
        )
    }

    fun editNewsletterBatch(batch: NewsletterBatch) = _state.update {
        it.copy(newsletterEditor = batch, newsletterProgress = null)
    }

    fun closeNewsletterEditor() = _state.update { it.copy(newsletterEditor = null) }

    /**
     * Writes [batch] back, replacing the entry with its id or appending it if it is new.
     *
     * A batch with no name is stored with a blank one and *rendered* as [NewsletterBatch.UNTITLED]
     * rather than being given that name here: naming it on save would mean a user who later types
     * a real name is editing a placeholder they never wrote, and a batch that came back from disk
     * would look like one they had named.
     */
    fun saveNewsletterBatch(batch: NewsletterBatch) {
        val next = _state.value.newsletters.toMutableList()
        val at = next.indexOfFirst { it.id == batch.id }
        if (at >= 0) next[at] = batch else next += batch
        Store.setNewsletters(app, next)
        _state.update { it.copy(newsletters = next, newsletterEditor = null) }
    }

    fun deleteNewsletterBatch(id: String) {
        val next = _state.value.newsletters.filterNot { it.id == id }
        Store.setNewsletters(app, next)
        _state.update {
            it.copy(
                newsletters = next,
                newsletterEditor = null,
                // A batch deleted while it was the one being written to would leave the compose
                // screen addressed to nothing.
                newsletterCompose = it.newsletterCompose?.takeIf { c -> c.id != id },
            )
        }
    }

    /**
     * Opens the composer on [batch].
     *
     * A *finished* broadcast's outcome is dropped — it belonged to whatever was written last and
     * has been read. An *unfinished* one is kept, because backing out of a send and opening
     * another batch does not stop the first one, and clearing it here is precisely what would
     * let the user start a second broadcast on top of a running one.
     */
    fun openNewsletterCompose(batch: NewsletterBatch) = _state.update {
        it.copy(
            newsletterCompose = batch,
            newsletterProgress = it.newsletterProgress?.takeIf { p -> !p.done },
            message = null,
        )
    }

    fun closeNewsletterCompose() = _state.update { it.copy(newsletterCompose = null) }

    /** Dismisses a finished broadcast's outcome line. No-op while one is still running —
     *  the progress line is the only thing on screen saying the send is still happening. */
    fun clearNewsletterProgress() = _state.update {
        if (it.newsletterProgress?.done == true) it.copy(newsletterProgress = null) else it
    }

    /**
     * The conversation to send a [NewsletterTarget] into.
     *
     * A **chat** target is looked up: the live list first (its `guids` carry every room of a
     * forked group, which is what makes an AppleScript send survive a dead fork), then the
     * on-disk store for a chat that has fallen out of the sweep window. Nothing is constructed
     * for it — a group's guid cannot be derived from anything.
     *
     * A **contact** target's 1:1 guid *is* its handle, so it is constructed when no thread
     * exists yet — the same trick [sendNewImage] uses, and what lets a batch include somebody
     * this phone has never messaged.
     */
    private fun newsletterConversation(target: NewsletterTarget): Conversation? {
        val convos = _state.value.conversations
        if (target.isChat) {
            return convos.firstOrNull { !it.isAgent && target.id in it.guids } ?: store.chat(target.id)
        }
        val handle = imessageHandle(target.id)
        val guid = "iMessage;-;$handle"
        return convos.firstOrNull { !it.isAgent && !it.isGroup && guid in it.guids }
            ?: store.chat(guid)
            ?: Conversation(
                guid = guid,
                displayName = "",
                participants = listOf(handle),
                isGroup = false,
                lastText = "",
                lastDate = 0L,
                lastFromMe = false,
            )
    }

    /**
     * Sends one message — [text] and/or [files] — separately to every recipient of [batch].
     *
     * **Separately is the feature.** There is no group here: each recipient gets their own
     * message in their own thread, sees no other recipient, and replies to you alone. A group
     * chat would have been one API call and a completely different product.
     *
     * Sequential, on one IO coroutine, for the same reason [sendImageFiles] is: iMessage has no
     * batch send, and racing N of them at the server is how the AppleScript path starts dropping
     * messages that it reports as sent. The trade is wall-clock — see [newsletterGapMs].
     *
     * **Text first, then the photos.** A partial failure is the case that decides this: if the
     * connection dies halfway through a recipient, the words are the part that had to land. A
     * recipient whose text delivered but whose photos did not is therefore *not* a failure — it
     * is [NewsletterProgress.partial], reported separately, because re-running the whole batch
     * double-sends the text to everyone who succeeded and is the wrong answer for them.
     *
     * Failures are collected per recipient rather than aborting the run — one unreachable number
     * in a batch of forty must not stop the other thirty-nine — and named in the outcome line, so
     * "who didn't get it" is answerable without opening every thread.
     *
     * **One broadcast at a time.** [newsletterJob] refuses a second while one is in flight: two
     * loops would interleave into the single [UiState.newsletterProgress] and each would report
     * the other's counters, and the screen gate alone can't prevent it (backing out of a send and
     * opening another batch leaves a live Send bar in front of a running broadcast).
     */
    fun sendNewsletter(batch: NewsletterBatch, text: String, files: List<File>) {
        val body = text.trim()
        val targets = batch.targets
        val label = batch.name.ifBlank { NewsletterBatch.UNTITLED }
        val total = targets.size

        /** The whole broadcast failed before it began. Reported through the progress line, not
         *  `state.message`: no newsletter screen renders that field, so it would be invisible
         *  here and then turn up floating on the conversation list — the same bug "Mark all as
         *  read" was written around. */
        fun stillborn(reason: String) = _state.update {
            it.copy(
                newsletterProgress = NewsletterProgress(
                    batchId = batch.id, batchName = label, done = true, error = reason,
                ),
                message = null,
            )
        }

        if (newsletterJob?.isActive == true) {
            stillborn("Another batch is still sending")
            return
        }
        if (targets.isEmpty()) {
            stillborn("That batch has no recipients")
            return
        }
        if (body.isEmpty() && files.isEmpty()) return

        // The grid, built before anything is sent so every square is on screen from the first
        // frame. A row that appeared only once it had been attempted would make a slow batch
        // look shorter than it is.
        val items = buildList {
            if (body.isNotEmpty()) add(NewsletterItem("Message", isPhoto = false))
            files.forEach { add(NewsletterItem(it.name, isPhoto = true)) }
        }
        _state.update {
            it.copy(
                newsletterProgress = NewsletterProgress(
                    batchId = batch.id,
                    batchName = label,
                    items = items,
                    rows = targets.map { t ->
                        NewsletterRow(t.key, t.label, List(items.size) { SendState.Pending })
                    },
                ),
                message = null,
            )
        }
        newsletterJob = viewModelScope.launch(Dispatchers.IO) {
            val client = api
            if (client == null) {
                stillborn("Not signed in to the server")
                return@launch
            }
            // Read every photo once, up front, rather than once per recipient: a batch of twenty
            // is twenty re-reads of the same file off the same disk otherwise. **An unreadable
            // one aborts the whole broadcast** rather than being skipped — a photo attached
            // minutes ago can be evicted from the cache before Send is tapped, and silently
            // dropping it would send forty people a message missing the picture it was about,
            // or (for a photos-only broadcast) send forty people nothing at all and report
            // "Sent to 40".
            val photos = ArrayList<PickedImage>(files.size)
            for (file in files) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    stillborn("Couldn’t read ${file.name} — nothing was sent")
                    return@launch
                }
                photos += PickedImage(bytes, mimeForExtension(file.extension), file.name)
            }

            newsletterPayload = NewsletterPayload(batch, body, photos)

            val method = sendMethod()

            // The live grid. Mutated in place and republished after every item, so a row fills
            // in square by square rather than appearing whole once its recipient is finished.
            val grid = targets.map { t ->
                NewsletterRow(t.key, t.label, List(items.size) { SendState.Pending })
            }.toMutableList()

            fun publish(done: Boolean) {
                val snapshot = NewsletterProgress(
                    batchId = batch.id,
                    batchName = label,
                    items = items,
                    rows = grid.toList(),
                    done = done,
                )
                // Written whole rather than as a copy() of whatever is in state: this coroutine
                // owns every field of it, and `update` re-runs its block on CAS contention.
                _state.update { it.copy(newsletterProgress = snapshot) }
            }

            /** Set one square and show it immediately. The point of the whole feature. */
            fun mark(row: Int, item: Int, value: SendState) {
                val states = grid[row].states.toMutableList()
                states[item] = value
                grid[row] = grid[row].copy(states = states)
                publish(done = false)
            }

            /** Everything this recipient has not had yet, when the chat itself is unreachable. */
            fun failRest(row: Int) {
                val states = grid[row].states.toMutableList()
                for (i in states.indices) if (states[i] != SendState.Sent) states[i] = SendState.Failed
                grid[row] = grid[row].copy(states = states)
                publish(done = false)
            }

            for ((index, target) in targets.withIndex()) {
                if (index > 0) delay(newsletterGapMs)
                val convo = newsletterConversation(target)
                if (convo == null) {
                    failRest(index)
                    continue
                }
                // The room that delivered the first thing is reused for the rest, so a forked
                // group's photos land in the same room as its text instead of each one
                // re-probing the dead siblings (see sendAcrossRooms / lastGoodRoom).
                var room: String? = null
                var cursor = 0

                var textOk = true
                if (body.isNotEmpty()) {
                    mark(index, cursor, SendState.Sending)
                    textOk = runCatching {
                        val echo = sendAcrossRooms(convo.guid, sendTargets(convo, method)) { g ->
                            client.send(g, body, newTempGuid(), method, null).also { room = g }
                        }
                        bumpConversation(convo.guid, echo.previewText, echo.date, fromMe = true)
                    }.isSuccess
                    mark(index, cursor, if (textOk) SendState.Sent else SendState.Failed)
                    cursor++
                }

                // Photos are not attempted when the text failed: that chat is unreachable, and
                // every photo would be another doomed upload holding up the rest of the batch.
                if (!textOk) {
                    failRest(index)
                    continue
                }

                for (photo in photos) {
                    mark(index, cursor, SendState.Sending)
                    val ok = runCatching {
                        val target1 = room
                        val echo = if (target1 != null) {
                            client.sendAttachment(
                                target1, photo.bytes, photo.name, photo.mime, newTempGuid(), method,
                            )
                        } else {
                            sendAcrossRooms(convo.guid, sendTargets(convo, method)) { g ->
                                client.sendAttachment(
                                    g, photo.bytes, photo.name, photo.mime, newTempGuid(), method,
                                ).also { room = g }
                            }
                        }
                        bumpConversation(convo.guid, echo.previewText, echo.date, fromMe = true)
                    }.isSuccess
                    mark(index, cursor, if (ok) SendState.Sent else SendState.Failed)
                    cursor++
                    // One failed upload no longer abandons the rest. Each photo is its own
                    // square now, so carrying on means the grid says which ones actually
                    // landed -- and the resend can then ask for only those that did not.
                }

                // The thread's cached messages no longer include what just went out; drop it so
                // opening that chat re-fetches rather than showing a thread the broadcast is
                // missing from. Also on a partial -- some of it landed.
                messageCache.remove(convo.guid)
            }
            publish(done = true)
            // Pull the broadcast's own echoes into the list. Without this the rows it just
            // touched carry the optimistic bump and nothing else until the next refresh.
            refresh()
        }
    }

    /**
     * Send only what is still missing, to only the recipients still missing it.
     *
     * The reason the grid exists. Re-running a whole batch to fix two recipients double-sends to
     * everyone who was fine, which is worse than the original failure -- so before this, a
     * partly-failed broadcast had no fix at all short of opening each chat by hand.
     *
     * The grid is kept and repaired in place rather than rebuilt: a square that succeeded the
     * first time stays green, so a second failure reads as "still missing" rather than starting
     * the count over. Recipients who already have everything are not touched.
     */
    fun resendNewsletterMissing() {
        val progress = _state.value.newsletterProgress ?: return
        if (!progress.done) return
        val payload = newsletterPayload
        if (payload == null || payload.batch.id != progress.batchId) {
            _state.update {
                it.copy(
                    newsletterProgress = progress.copy(
                        error = "That broadcast is no longer loaded — open the batch and send again",
                    ),
                )
            }
            return
        }
        if (newsletterJob?.isActive == true) return
        val outstanding = progress.resendable()
        if (outstanding.isEmpty()) return

        val byKey = payload.batch.targets.associateBy { it.key }
        val grid = progress.rows.toMutableList()

        newsletterJob = viewModelScope.launch(Dispatchers.IO) {
            val client = api
            if (client == null) {
                _state.update {
                    it.copy(
                        newsletterProgress = progress.copy(error = "Not signed in to the server"),
                    )
                }
                return@launch
            }
            val method = sendMethod()

            fun publish(done: Boolean) {
                val snapshot = progress.copy(rows = grid.toList(), done = done, error = null)
                _state.update { it.copy(newsletterProgress = snapshot) }
            }

            fun mark(row: Int, item: Int, value: SendState) {
                val states = grid[row].states.toMutableList()
                states[item] = value
                grid[row] = grid[row].copy(states = states)
                publish(done = false)
            }

            publish(done = false)

            var first = true
            for (row in outstanding) {
                val at = grid.indexOfFirst { it.targetKey == row.targetKey }
                if (at < 0) continue
                val target = byKey[row.targetKey] ?: continue
                if (!first) delay(newsletterGapMs)
                first = false

                val convo = newsletterConversation(target)
                if (convo == null) {
                    val states = grid[at].states.toMutableList()
                    for (i in states.indices) if (states[i] != SendState.Sent) states[i] = SendState.Failed
                    grid[at] = grid[at].copy(states = states)
                    publish(done = false)
                    continue
                }

                var room: String? = null
                // Index 0 is the message when there is one; the photos follow in order. The
                // same layout the first pass built, so a square means the same thing on a retry.
                val textIndex = if (payload.body.isNotEmpty()) 0 else -1
                val photoBase = if (textIndex == 0) 1 else 0

                if (textIndex >= 0 && grid[at].states[textIndex] != SendState.Sent) {
                    mark(at, textIndex, SendState.Sending)
                    val ok = runCatching {
                        val echo = sendAcrossRooms(convo.guid, sendTargets(convo, method)) { g ->
                            client.send(g, payload.body, newTempGuid(), method, null).also { room = g }
                        }
                        bumpConversation(convo.guid, echo.previewText, echo.date, fromMe = true)
                    }.isSuccess
                    mark(at, textIndex, if (ok) SendState.Sent else SendState.Failed)
                    if (!ok) continue
                }

                payload.photos.forEachIndexed { i, photo ->
                    val slot = photoBase + i
                    if (grid[at].states.getOrNull(slot) == SendState.Sent) return@forEachIndexed
                    mark(at, slot, SendState.Sending)
                    val ok = runCatching {
                        val known = room
                        val echo = if (known != null) {
                            client.sendAttachment(
                                known, photo.bytes, photo.name, photo.mime, newTempGuid(), method,
                            )
                        } else {
                            sendAcrossRooms(convo.guid, sendTargets(convo, method)) { g ->
                                client.sendAttachment(
                                    g, photo.bytes, photo.name, photo.mime, newTempGuid(), method,
                                ).also { room = g }
                            }
                        }
                        bumpConversation(convo.guid, echo.previewText, echo.date, fromMe = true)
                    }.isSuccess
                    mark(at, slot, if (ok) SendState.Sent else SendState.Failed)
                }

                messageCache.remove(convo.guid)
            }
            publish(done = true)
            refresh()
        }
    }

    // ---- Live feed (socket) ----------------------------------------------

    private fun observeSocket() {
        viewModelScope.launch {
            SocketBus.incoming.collect { applyIncoming(it) }
        }
        viewModelScope.launch {
            SocketBus.typing.collect { applyTyping(it) }
        }
        viewModelScope.launch {
            SocketBus.readStatus.collect { applyReadStatus(it) }
        }
    }

    // ---- Typing indicators ------------------------------------------------

    private var typingExpiryJob: Job? = null      // clears a stale "typing" if no refresh
    private var typingSent = false                // whether we've told the server we're typing
    private var typingStopJob: Job? = null        // fires the auto-stop after a pause

    /** Receiving: fold a typing change into state. A "stopped" event can be missed,
     *  so a "typing" auto-expires after [TYPING_EXPIRY_MS] without a refresh (the
     *  server re-emits ~every 5s while typing continues). */
    private fun applyTyping(event: TypingEvent) {
        typingExpiryJob?.cancel()
        if (event.typing) {
            _state.update { it.copy(typingChatGuid = event.chatGuid) }
            typingExpiryJob = viewModelScope.launch {
                delay(TYPING_EXPIRY_MS)
                _state.update { if (it.typingChatGuid == event.chatGuid) it.copy(typingChatGuid = null) else it }
            }
        } else {
            _state.update { if (it.typingChatGuid == event.chatGuid) it.copy(typingChatGuid = null) else it }
        }
    }

    /** Sending: the open thread's compose text changed. Tells the server we're
     *  typing on the first keystroke and schedules an auto-stop after a pause; an
     *  empty field stops immediately. No-op unless the Private API is live. */
    fun onComposeTextChanged(text: String) {
        if (!_state.value.privateApi) return
        val guid = _state.value.open?.guid ?: return
        if (text.isBlank()) {
            typingStopJob?.cancel()
            stopTypingNow(guid)
            return
        }
        val client = api ?: return
        if (!typingSent) {
            typingSent = true
            viewModelScope.launch(Dispatchers.IO) { runCatching { client.startTyping(guid) } }
        }
        typingStopJob?.cancel()
        typingStopJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            stopTypingNow(guid)
        }
    }

    private fun stopTypingNow(guid: String) {
        if (!typingSent) return
        typingSent = false
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { client.stopTyping(guid) } }
    }

    /** Cancel a pending auto-stop and clear our typing state — on send or close. */
    private fun finishTyping(guid: String) {
        typingStopJob?.cancel()
        stopTypingNow(guid)
    }

    private fun applyIncoming(incoming: IncomingMessage) {
        val known = _state.value.conversations.any { incoming.chatGuid in it.guids }
        // Looking right at the thread — don't flag unread, and mark read below.
        val viewing = _state.value.open?.guids?.contains(incoming.chatGuid) == true && AppForeground.active
        // A genuinely new message (or tapback) from someone else, not on screen →
        // the row goes unread. Group events bump recency but aren't unread-worthy
        // (they also never get a dateRead, so the load derivation skips them too).
        val flagUnread = incoming.isNew && !incoming.message.fromMe &&
            !incoming.message.isGroupEvent && !viewing
        _state.update { s ->
            val convos = s.conversations.map { c ->
                if (incoming.chatGuid in c.guids) {
                    // An out-of-order message — an `updated-message` for something older
                    // than the row already shows (a read receipt, a delivery stamp, an
                    // edit) — must not roll the preview back. The sweep path guards this
                    // in Conversation.advancedBy; the socket path was overwriting
                    // lastText/lastDate regardless, which resurfaced an old message as the
                    // row's preview. Mirrors advancedBy's `date < lastDate` rule.
                    if (incoming.message.date < c.lastDate) {
                        c
                    } else when {
                        // A group event (rename, member change) bumps recency but
                        // isn't speech — the text preview keeps the newest real message.
                        incoming.message.isGroupEvent -> c.copy(
                            lastDate = maxOf(c.lastDate, incoming.message.date),
                        )
                        // A tapback bumps recency and surfaces as "Liz loved an image"
                        // ([lastReaction]); a removal clears that overlay; a normal message
                        // updates the text preview and clears any reaction overlay.
                        incoming.message.isReaction -> c.copy(
                            lastDate = incoming.message.date,
                            lastReaction = incoming.message.reactionPreview(::cachedMessage),
                            unread = c.unread || flagUnread,
                        )
                        else -> c.copy(
                            lastText = incoming.message.previewText,
                            lastDate = incoming.message.date,
                            lastFromMe = incoming.message.fromMe,
                            lastReaction = null,
                            unread = c.unread || flagUnread,
                        )
                    }
                } else {
                    c
                }
            }.sortedByDescending { it.lastDate }
            s.copy(conversations = convos)
        }
        // A rename should reflect immediately in the open thread's title; the list
        // row's stored displayName comes from the refresh below.
        if (incoming.message.groupEvent == GroupEvent.RENAMED) {
            _state.update { s ->
                val open = s.open
                if (open != null && incoming.chatGuid in open.guids) {
                    s.copy(open = open.copy(displayName = incoming.chatDisplayName))
                } else {
                    s
                }
            }
            refresh()
        }
        // Fold the message into the open thread's raw list (a tapback lands on its
        // target; a normal message appends). foldReactions re-runs in updateOpenThread.
        updateOpenThread(incoming.chatGuid) { mergeRaw(it, incoming.message) }
        // If it's an incoming message in the thread you're looking at, mark the chat
        // read so the unread clears on your other devices too — and record the clear
        // locally so a refresh can't resurrect the dot before the server catches up.
        if (!incoming.message.fromMe && viewing) {
            markReadIfPrivate(incoming.chatGuid)
            _state.value.conversations.firstOrNull { incoming.chatGuid in it.guids }
                ?.let { clearUnread(it.guid) }
        }
        // **Persist it.** Without this the store would drift from what is on screen, and the
        // next launch would read a list missing everything that arrived while the app was
        // open — then fetch it all again. The message body is only kept for threads that
        // have actually been opened; for the rest the list row is all the app shows.
        persistIncoming(incoming)
        // A message for a chat not currently in the list (e.g. a brand-new
        // conversation) — pull the list again so it appears with full metadata.
        if (!known) refresh()
    }

    /** Writes a live message and its list row through to the store, off the main thread. */
    private fun persistIncoming(incoming: IncomingMessage) {
        val rows = _state.value.conversations.filter { incoming.chatGuid in it.guids }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (rows.isNotEmpty()) store.putChats(rows)
                if (incoming.raw.isNotBlank() && store.isThreadLoaded(incoming.chatGuid)) {
                    store.putMessages(
                        listOf(MessageStore.Row(incoming.chatGuid, incoming.message, incoming.raw)),
                    )
                    store.trim(incoming.chatGuid)
                }
            }
        }
    }

    /** A `chat-read-status-changed` from the socket: the chat was read somewhere
     *  (another device, or our own markRead echoing back) — drop its unread dot.
     *
     *  Don't trust the bare event: the server's chat.db poller can report `read: true`
     *  describing the state *before* a message that just arrived (see
     *  SocketService.onReadStatus / newestIsRead — same race, same fix). Verify the
     *  chat's actual newest message carries a dateRead before clearing the dot. */
    private fun applyReadStatus(event: ReadStatusEvent) {
        if (!event.read) return
        val convo = _state.value.conversations.firstOrNull { event.chatGuid in it.guids } ?: return
        viewModelScope.launch {
            val stillUnread = withContext(Dispatchers.IO) {
                val client = api ?: return@withContext false
                val newest = runCatching { client.messages(event.chatGuid, limit = 5) }
                    .getOrNull()
                    ?.filterNot { it.fromMe || it.isGroupEvent }
                    ?.maxByOrNull { it.date }
                    ?: return@withContext false
                newest.dateRead == 0L
            }
            if (!stillUnread) clearUnread(convo.guid)
        }
    }

    /** Clears [convoGuid]'s unread marker in the list and records it in
     *  [clearedUnread] so the next refresh can't resurrect it (the server's
     *  dateRead stamp can lag our markRead). */
    private fun clearUnread(convoGuid: String) {
        val convo = _state.value.conversations.firstOrNull { it.guid == convoGuid } ?: return
        clearedUnread[convoGuid] = maxOf(clearedUnread[convoGuid] ?: 0L, convo.lastDate)
        if (!convo.unread) return
        _state.update { s ->
            s.copy(conversations = s.conversations.map { if (it.guid == convoGuid) it.copy(unread = false) else it })
        }
        // **Written through, not just to state.** The delta filters on creation date, so a
        // message whose `dateRead` is stamped afterwards is never re-fetched and the stored
        // row keeps `unread = true` for good. The old full sweep re-derived it from
        // `dateRead` every launch and so got away with an in-memory clear; this one would
        // resurrect the dot on every process restart, on every chat that has since gone
        // quiet.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.putChats(listOf(convo.copy(unread = false))) }
        }
    }

    /**
     * Clears every unread dot at once (Settings → Mark all as read). Returns what to tell
     * the user, because the two halves can disagree and `state.message` is the wrong place
     * for it — the conversation list only renders that when the list is *empty*, so the
     * text would be invisible here and then turn up floating in the next thread opened.
     *
     * The dots go immediately and are recorded in [clearedUnread] so the next refresh
     * can't resurrect them while the server's `dateRead` catches up — that part always
     * works. Sending the actual read receipts needs the Private API, and without it the
     * Mac still thinks they're unread, so say so rather than implying more happened.
     */
    fun markAllRead(): String {
        val unread = _state.value.conversations.filter { it.unread }
        if (unread.isEmpty()) return "Nothing unread"
        val cleared = unread.map { it.guid }.toSet()
        unread.forEach { clearedUnread[it.guid] = maxOf(clearedUnread[it.guid] ?: 0L, it.lastDate) }
        // Keyed on the captured set, not on `it.unread`: update's lambda re-runs on CAS
        // contention, and a message arriving in that window would have its dot cleared
        // here without a clearedUnread entry or a receipt — so the next refresh would
        // bring it straight back.
        _state.update { s ->
            s.copy(conversations = s.conversations.map { if (it.guid in cleared) it.copy(unread = false) else it })
        }
        Notifications.clear(app)
        // Every room, not just the primary guid: a forked group spans several.
        unread.flatMap { it.guids }.forEach { markReadIfPrivate(it) }
        val n = cleared.size
        val what = if (n == 1) "1 conversation" else "$n conversations"
        return if (_state.value.privateApi) {
            "Marked $what read"
        } else {
            "Cleared $what here — the Private API is off, so the Mac still shows them unread"
        }
    }

    /** Marks [chatGuid] read on the server (best-effort, off-main), but only when
     *  the Private API is live — it's the only path that can send a read receipt. */
    private fun markReadIfPrivate(chatGuid: String) {
        if (!_state.value.privateApi) return
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { client.markRead(chatGuid) } }
    }

    /** Match by guid, or — for the socket echo of our own send — by the tempGuid the
     *  server echoes back, since the optimistic bubble still carries it as its guid.
     *  Without the latter the echo (real guid) would render as a second row until the
     *  HTTP send call returns and swaps the temp guid in. See [mergeIntoThread], which
     *  is where the rule that no two rows may share a guid lives. */
    private fun mergeRaw(list: List<ChatMessage>, m: ChatMessage): List<ChatMessage> =
        mergeIntoThread(list, m)

    /** Looks up a message by guid in whatever's cached (the open thread, or any
     *  previously-opened thread), so a live tapback can describe its target. Null
     *  when the target isn't loaded — the preview then reads "a message". */
    private fun cachedMessage(guid: String): ChatMessage? {
        openRaw.firstOrNull { it.guid == guid }?.let { return it }
        for (list in messageCache.values) list.firstOrNull { it.guid == guid }?.let { return it }
        return null
    }

    private fun bumpConversation(guid: String, text: String, date: Long, fromMe: Boolean) {
        _state.update { s ->
            val convos = s.conversations.map { c ->
                // A real message (this is only called for sends) clears any reaction overlay.
                if (guid in c.guids) c.copy(lastText = text, lastDate = date, lastFromMe = fromMe, lastReaction = null) else c
            }.sortedByDescending { it.lastDate }
            s.copy(conversations = convos)
        }
    }

    // ---- Service lifecycle ------------------------------------------------

    private fun startSocket() {
        app.startForegroundService(Intent(app, SocketService::class.java))
    }

    private fun stopSocket() {
        app.stopService(Intent(app, SocketService::class.java))
    }

    // ---- Errors / sign out ------------------------------------------------

    private fun handleError(t: Throwable) {
        if (t is ApiException && t.isAuthError) {
            signOutInternal("Password rejected")
            return
        }
        _state.update { it.copy(status = Status.Error, message = t.message ?: "Something went wrong") }
    }

    fun signOut() = signOutInternal(null)

    /** The store holds the account's messages, so it goes when the password does. Off the
     *  main thread: three unbounded DELETEs over a database that may have run for months. */
    private fun clearStore() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.clear() } }
    }

    private fun signOutInternal(message: String?) {
        loadJob?.cancel()
        threadJob?.cancel()
        api = null
        sync = null
        stopSocket()
        Store.signOut(app)
        clearStore()
        messageCache.clear()
        _state.value = UiState(isConfigured = false, message = message)
    }

    override fun onCleared() {
        loadJob?.cancel()
        threadJob?.cancel()
        super.onCleared()
    }
}
