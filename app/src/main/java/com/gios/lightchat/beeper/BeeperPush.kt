package com.gios.lightchat.beeper

import android.content.Context
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.clientserverapi.model.push.PusherData
import de.connect2x.trixnity.clientserverapi.model.push.SetPushers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * The wake-up signal for Beeper while the sync loop sleeps.
 *
 * The phone has no Google push, so Beeper cannot reach it directly. Instead the account gets a
 * Matrix HTTP pusher that points at an ntfy server's Matrix gateway
 * (`<server>/_matrix/push/v1/notify`). On every new message Beeper posts a small
 * `event_id_only` payload there: a room id and an event id, no text and no keys. ntfy files it
 * under the topic named in the pusher's pushkey, and this object holds that topic's JSON stream
 * open. Each line that arrives becomes one [onPush], which runs a single sync. The sync, not the
 * push, is what delivers and decrypts the message.
 *
 * ### Which server
 *
 * `https://ntfy.sh` unless Settings names another. The topic is random per install (`up` plus 12
 * letters and digits, the UnifiedPush shape, so ntfy.sh rate-limits it against this phone and not
 * against Beeper's shared servers). Anyone who knew the topic could read the ids or send empty
 * wake-ups; neither carries a message.
 *
 * ### What happens when it drops
 *
 * Nothing is lost. Beeper retries its posts, ntfy keeps about twelve hours of them, and the stream
 * resumes from the last id it saw (`since=`). The poll alarm still runs underneath.
 *
 * The pattern (pusher on ntfy's gateway, stream held by the app) follows fenleon/chats (MIT).
 */
object BeeperPush {

    const val DEFAULT_SERVER = "https://ntfy.sh"
    private const val PREFS = "beeper_push"
    private const val KEY_SERVER = "server"
    private const val KEY_TOPIC = "topic"
    private const val KEY_LAST_ID = "last_id"
    private const val APP_ID = "com.gios.lightchat"
    private const val NOTIFY_PATH = "/_matrix/push/v1/notify"

    private const val CONNECT_TIMEOUT_MS = 15_000

    /** ntfy sends a keepalive every 45 s. Silence for twice that means a dead socket. */
    private const val READ_TIMEOUT_MS = 100_000
    private const val RECONNECT_BASE_MS = 5_000L
    private const val RECONNECT_MAX_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var startedFor: MatrixClient? = null

    /** True while the stream is open, so a push can arrive right now. */
    @Volatile var connected: Boolean = false
        private set

    /** Wall-clock time of the last push, 0 for none since the process started. */
    @Volatile var lastPushAt: Long = 0L
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun server(ctx: Context): String = prefs(ctx).getString(KEY_SERVER, null) ?: DEFAULT_SERVER

    /** "https://ntfy.example.com/" → "https://ntfy.example.com". Blank means the default. */
    fun normalizeServer(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return DEFAULT_SERVER
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return runCatching { URL(withScheme) }.getOrNull()?.takeIf { it.host.isNotBlank() }?.let { withScheme }
    }

    /** A new topic made of `up` and twelve letters and digits. */
    fun newTopic(random: SecureRandom = SecureRandom()): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return "up" + (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun topic(ctx: Context): String =
        prefs(ctx).getString(KEY_TOPIC, null) ?: newTopic().also { prefs(ctx).edit().putString(KEY_TOPIC, it).apply() }

    fun pushkey(server: String, topic: String) = "$server/$topic?up=1"

    fun streamUrl(server: String, topic: String, since: String?) =
        "$server/$topic/json?up=1&since=${since ?: "all"}"

    /**
     * Registers the pusher and holds the stream. Safe to call again: it does nothing while the
     * stream for this client is already up.
     */
    @Synchronized
    fun start(ctx: Context, c: MatrixClient, onPush: suspend (roomId: String?, eventId: String?) -> Unit) {
        if (job?.isActive == true && startedFor === c) return
        stop()
        startedFor = c
        val app = ctx.applicationContext
        val server = server(app)
        val topic = topic(app)
        job = scope.launch {
            register(c, server, topic)
            stream(app, server, topic, onPush)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { conn?.disconnect() }
        conn = null
        connected = false
    }

    /** New server from Settings: take the pusher off the old one, then start over on the new. */
    suspend fun changeServer(ctx: Context, c: MatrixClient?, server: String, onPush: suspend (String?, String?) -> Unit) {
        val app = ctx.applicationContext
        c?.let { unregister(app, it) }
        stop()
        prefs(app).edit()
            .putString(KEY_SERVER, server.takeIf { it != DEFAULT_SERVER })
            .remove(KEY_TOPIC)
            .remove(KEY_LAST_ID)
            .apply()
        startedFor = null
        c?.let { start(app, it, onPush) }
    }

    /** Takes this phone's pusher off the account (sign out). Best effort. */
    suspend fun unregister(ctx: Context, c: MatrixClient) {
        val topic = prefs(ctx).getString(KEY_TOPIC, null) ?: return
        runCatching {
            c.api.push.setPushers(SetPushers.Request.Remove(APP_ID, pushkey(server(ctx), topic))).getOrThrow()
        }.onSuccess { BeeperEngine.note("push: pusher removed") }
            .onFailure { BeeperEngine.note("push: couldn’t remove the pusher: ${it.message}") }
    }

    /** Forgets the topic and server, after sign out. */
    fun forget(ctx: Context) {
        stop()
        startedFor = null
        prefs(ctx).edit().clear().apply()
    }

    private suspend fun register(c: MatrixClient, server: String, topic: String) {
        val request = SetPushers.Request.Set(
            appId = APP_ID,
            pushkey = pushkey(server, topic),
            kind = "http",
            appDisplayName = "BrightChat",
            deviceDisplayName = "Light Phone",
            lang = "en",
            // No message content on the wire: ids only.
            data = PusherData(format = "event_id_only", url = server + NOTIFY_PATH),
            append = false,
        )
        runCatching { c.api.push.setPushers(request).getOrThrow() }
            .onSuccess { BeeperEngine.note("push: registered on ${URL(server).host}") }
            .onFailure { BeeperEngine.note("push: registration failed, the poll still runs: ${it.message}") }
    }

    private suspend fun CoroutineScope.stream(
        ctx: Context,
        server: String,
        topic: String,
        onPush: suspend (String?, String?) -> Unit,
    ) {
        var backoff = RECONNECT_BASE_MS
        while (isActive) {
            val since = prefs(ctx).getString(KEY_LAST_ID, null)
            try {
                val c = (URL(streamUrl(server, topic, since)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/x-ndjson")
                }
                conn = c
                val code = c.responseCode
                if (code !in 200..299) error("HTTP $code")
                connected = true
                backoff = RECONNECT_BASE_MS
                BeeperEngine.note("push: stream open")
                c.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val push = parse(line) ?: continue
                        if (push.id != null) prefs(ctx).edit().putString(KEY_LAST_ID, push.id).apply()
                        lastPushAt = System.currentTimeMillis()
                        runCatching { onPush(push.roomId, push.eventId) }
                            .onFailure { if (it is CancellationException) throw it }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) BeeperEngine.note("push: stream dropped: ${e.message}")
            } finally {
                connected = false
                runCatching { conn?.disconnect() }
                conn = null
            }
            if (!isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    /** One push off the stream: ntfy's message id, and the room and event it names if any. */
    data class Push(val id: String?, val roomId: String?, val eventId: String?)

    /**
     * An ntfy stream line, or null for one that is not a push (`open`, `keepalive`, junk).
     *
     * ntfy's gateway puts the homeserver's whole POST body into `message` as a string, and that
     * body is `{"notification":{"room_id":…,"event_id":…,"counts":…}}`. Beeper also posts
     * counts-only bodies with no ids when a chat is read elsewhere; those still wake a sync, which
     * is what clears the alert.
     */
    fun parse(line: String): Push? {
        val outer = runCatching { JSONObject(line.trim()) }.getOrNull() ?: return null
        if (outer.optString("event") != "message") return null
        val id = outer.optString("id").takeIf { it.isNotBlank() }
        val body = outer.optString("message")
        val notification = runCatching { JSONObject(body) }.getOrNull()
            ?.let { it.optJSONObject("notification") ?: it }
        return Push(
            id = id,
            roomId = notification?.optString("room_id")?.takeIf { it.isNotBlank() },
            eventId = notification?.optString("event_id")?.takeIf { it.isNotBlank() },
        )
    }
}
