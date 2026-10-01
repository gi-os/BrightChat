package com.gios.lightchat.beeper

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.gios.lightchat.ChatMessage
import com.gios.lightchat.Conversation
import com.gios.lightchat.IncomingMessage
import com.gios.lightchat.ReactionType
import com.gios.lightchat.TypingEvent
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.db.MessageStore
import com.gios.lightchat.socket.SocketBus
import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MatrixClientConfiguration
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.libolm.libOlm
import de.connect2x.trixnity.client.key
import de.connect2x.trixnity.client.key.KeySecretService
import de.connect2x.trixnity.client.key.KeyTrustService
import de.connect2x.trixnity.client.media
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.notification
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.GetTimelineEventsConfig
import de.connect2x.trixnity.client.room.message.file
import de.connect2x.trixnity.client.room.message.image
import de.connect2x.trixnity.client.room.message.replace
import de.connect2x.trixnity.client.room.message.reply
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.room.message.video
import de.connect2x.trixnity.client.store.GlobalAccountDataStore
import de.connect2x.trixnity.client.store.Room as MatrixRoom
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.eventId
import de.connect2x.trixnity.client.store.isReplaced
import de.connect2x.trixnity.client.store.originTimestamp
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import de.connect2x.trixnity.client.store.repository.room.room
import de.connect2x.trixnity.client.store.sender
import de.connect2x.trixnity.client.store.unsigned
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.verification
import de.connect2x.trixnity.client.verification.SelfVerificationMethod
import de.connect2x.trixnity.client.verification.VerificationService
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.classicLogin
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.clientserverapi.model.authentication.LoginType
import de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.RedactedEventContent
import de.connect2x.trixnity.core.model.events.m.Presence
import de.connect2x.trixnity.core.model.events.m.ReactionEventContent
import de.connect2x.trixnity.core.model.events.m.ReceiptType
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.ImageInfo
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.NameEventContent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.VideoInfo
import de.connect2x.trixnity.core.model.events.m.secretstorage.DefaultSecretKeyEventContent
import de.connect2x.trixnity.core.model.events.m.secretstorage.SecretKeyEventContent
import de.connect2x.trixnity.crypto.key.DeviceTrustLevel
import de.connect2x.trixnity.crypto.key.decodeRecoveryKey
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import de.connect2x.trixnity.core.AuthRequired
import io.ktor.http.ContentType
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import okio.Path.Companion.toPath
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * BrightChat's Beeper account: WhatsApp, Signal, Telegram, Instagram and the rest, through Beeper's
 * cloud bridges, spoken as Matrix.
 *
 * ### Shape
 *
 * One process-wide client, because the Matrix session is one device on the account and its
 * encryption keys live in one database. The UI never sees Trixnity: rooms come out as
 * [Conversation] rows written into [MessageStore], messages as BlueBubbles-shaped JSON (see
 * [BeeperMapping]) and live events on [SocketBus], the same bus the BlueBubbles socket feeds. So
 * the list, the thread and every screen behind them treat a WhatsApp chat as another chat.
 *
 * ### Where the Beeper-specific parts came from
 *
 * The login flow (email code, then a JWT login on Beeper's homeserver), the `/keys/claim` repair
 * ([ClaimFixEngine]) and the recovery-key verification that skips Trixnity's config-MAC check are
 * adapted from fenleon/chats and Beeper4LightOS, both MIT, both proven on the Light Phone III.
 *
 * ### What is not here yet
 *
 * Emoji verification with another device, the full emoji set for reactions, and adding people to
 * a bridged group. Alerts are [BeeperAlerts]; the wake-up while asleep is [BeeperPush].
 */
object BeeperEngine {

    private const val TAG = "Beeper"
    private const val API = "https://api.beeper.com"
    private const val HOMESERVER = "https://matrix.beeper.com"

    /** Not a secret: the public token every Beeper client sends to start a login. */
    private const val API_TOKEN = "BEEPER-PRIVATE-API-PLEASE-DONT-USE"
    private const val PREFS = "beeper"
    private const val KEY_USER = "user_id"
    private const val KEY_REQUEST = "login_request"
    private const val KEY_EMAIL = "email"
    private const val KEY_REQUEST_AT = "login_request_at"

    /** How long an emailed code is worth entering. Beeper's own expiry is shorter than a day. */
    private const val CODE_TTL_MS = 10 * 60_000L

    /** How long sync must stay broken, with the phone online, before it is worth a report. */
    private const val SYNC_REPORT_AFTER_MS = 10 * 60_000L
    private const val DB = "beeper_matrix"
    private const val MEDIA_DIR = "beeper_media"
    private const val DEVICE_NAME = "BrightChat (Light Phone)"

    /** The characters a recovery key is written in: no 0, O, I or l. */
    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** How much of a room one thread fetch reads. */
    private const val PAGE = 50
    private const val DECRYPT_WAIT_MS = 3_000L
    private const val QUICK_DECRYPT_WAIT_MS = 300L
    private const val FETCH_BUDGET_MS = 20_000L
    private const val SEND_ACK_BUDGET_MS = 30_000L
    private const val TYPING_TIMEOUT_MS = 20_000L

    sealed interface Status {
        /** No account on this phone. */
        data object SignedOut : Status

        /** A code was emailed; waiting for it. */
        data class CodeSent(val email: String) : Status

        /** Signing in or restoring the session. */
        data object Working : Status

        data class Ready(
            val userId: String,
            /** Cross-signed: this phone can read encrypted history from the key backup. */
            val verified: Boolean,
            val rooms: Int,
            val sync: String,
        ) : Status

        data class Failed(val message: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()

    @Volatile private var client: MatrixClient? = null
    @Volatile private var unsubscribeSync: (() -> Unit)? = null
    @Volatile private var appContext: Context? = null
    private var observers: Job? = null

    private val _status = MutableStateFlow<Status>(Status.SignedOut)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** The last few hundred things the engine did, for the Settings page and bug reports. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the Beeper rows in [MessageStore] changed and the list should re-read them. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** Room id → the conversation row last written for it, and the event it was built from. */
    private val rowCache = ConcurrentHashMap<String, Pair<String?, Conversation>>()

    /** Trixnity transaction id → the ViewModel's optimistic guid, so the echo replaces the bubble. */
    private val tempGuids = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------------ lifecycle

    fun hasSession(context: Context): Boolean = prefs(context).getString(KEY_USER, null) != null

    /** Restores a saved session and starts syncing. Safe to call more than once. */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (!hasSession(context)) {
            prefs(context).getString(KEY_EMAIL, null)?.let { email ->
                val at = prefs(context).getLong(KEY_REQUEST_AT, 0L)
                val fresh = System.currentTimeMillis() - at < CODE_TTL_MS
                if (prefs(context).getString(KEY_REQUEST, null) != null && fresh) {
                    _status.value = Status.CodeSent(email)
                } else {
                    // A code older than Beeper keeps it is a 403 waiting to happen; start over.
                    prefs(context).edit().remove(KEY_REQUEST).remove(KEY_REQUEST_AT).apply()
                }
            }
            return
        }
        scope.launch { runCatching { ensureClient() }.onFailure { note("restore failed: ${it.message}") } }
    }

    private suspend fun ensureClient(): MatrixClient? = lifecycle.withLock {
        client?.let { return@withLock it }
        val ctx = appContext ?: return@withLock null
        // Signed out (or signing out): nothing to restore. Without this a thread still open, or a
        // send queued behind the sign-out, recreated the deleted database and reported a failure.
        if (!hasSession(ctx)) return@withLock null
        val before = _status.value
        _status.value = Status.Working
        note("restoring session")
        val restored = try {
            MatrixClient.create(
            repositoriesModule = RepositoriesModule.room(databaseBuilder(ctx)),
            mediaStoreModule = MediaStoreModule.okio(mediaDir(ctx)),
            cryptoDriverModule = CryptoDriverModule.libOlm(),
            authProviderData = null,
            configuration = configuration(),
            ).getOrElse { e ->
                note("restore failed: ${e.message}")
                if (!isNetworkError(e)) autoReport("restore the session", e)
                null
            }
        } catch (e: CancellationException) {
            // The caller went away mid-restore; the status must not stay on "Working".
            if (_status.value == Status.Working) _status.value = before
            throw e
        }
        if (restored == null) {
            _status.value = Status.Failed("Couldn’t restore the Beeper session. Sign in again.")
            prefs(ctx).edit().remove(KEY_USER).apply()
            return@withLock null
        }
        attach(restored)
        restored
    }

    private fun attach(c: MatrixClient) {
        client = c
        observers?.cancel()
        unsubscribeSync?.invoke()
        // A finished sync response, whatever the state flow says: a healthy loop stays RUNNING and
        // never re-emits, so the state alone left every poll thinking the loop was asleep.
        lastSyncAt = android.os.SystemClock.elapsedRealtime()
        unsubscribeSync = runCatching {
            c.api.sync.subscribe(subscriber = { _ -> lastSyncAt = android.os.SystemClock.elapsedRealtime() })
        }.getOrNull()
        observers = scope.launch {
            launch {
                runCatching { c.startSync(Presence.OFFLINE) }.onFailure {
                    note("sync failed to start: ${it.message}")
                    autoReport("start syncing", it)
                }
            }
            launch { watchStatus(c) }
            launch { watchRooms(c) }
            launch { watchTimeline(c) }
            launch { watchTyping(c) }
            appContext?.let { ctx ->
                launch { BeeperAlerts.watch(ctx, c) }
                BeeperPush.start(ctx, c) { roomId, eventId -> onPush(c, roomId, eventId) }
            }
        }
        note("signed in as ${c.userId.full} on ${c.deviceId}")
    }

    private fun configuration(): MatrixClientConfiguration.() -> Unit = {
        name = "brightchat-beeper"
        // Trixnity evaluates the account's push rules and hands [BeeperAlerts] what to raise.
        enableExternalNotifications = true
        httpClientEngine = engine
        // Room "last relevant event" is real speech only: an edit or a reaction must not become
        // what the list row says.
        lastRelevantEventFilter = { event ->
            val content = event.content
            event is ClientEvent.RoomEvent.MessageEvent<*> &&
                (content is RoomMessageEventContent || content is EncryptedMessageEventContent) &&
                (content as? RoomMessageEventContent)?.relatesTo !is RelatesTo.Replace
        }
    }

    /** One engine for every Trixnity client this process builds, login included. */
    private val engine by lazy { ClaimFixEngine(Android.create()) }

    private fun databaseBuilder(ctx: Context) =
        Room.databaseBuilder(ctx, TrixnityRoomDatabase::class.java, DB)

    private fun mediaDir(ctx: Context) = File(ctx.cacheDir, MEDIA_DIR).absolutePath.toPath()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ login

    /** Step 1: Beeper emails [email] a six-digit code. */
    suspend fun requestCode(email: String): Result<Unit> = onEngine { requestCodeBlocking(email) }

    /**
     * Runs a login step on the engine's own scope and waits for it. The Settings screen calls
     * these from its composition scope; leaving the screen mid-step used to cancel the step
     * after Beeper had already used up the code, leaving the status on "Working" for good.
     */
    private suspend fun <T> onEngine(block: suspend () -> Result<T>): Result<T> {
        val job = scope.async { block() }
        return try {
            job.await()
        } catch (e: CancellationException) {
            // The screen went away; the step carries on and its outcome lands in [status].
            throw e
        }
    }

    // Network and disk, so never on the caller's thread: the Settings field calls this from a
    // Compose scope, which is the main thread (v2.44.91 failed with NetworkOnMainThreadException).
    private suspend fun requestCodeBlocking(email: String): Result<Unit> = runCatching {
        val ctx = appContext ?: error("not started")
        val address = email.trim()
        require(address.contains("@")) { "That doesn’t look like an email address." }
        val init = beeperPost("/user/login", "{}")
        val request = init.optString("request").takeIf { it.isNotBlank() } ?: error("Beeper didn’t start a login.")
        beeperPost("/user/login/email", JSONObject().put("request", request).put("email", address).toString())
        prefs(ctx).edit().putString(KEY_REQUEST, request).putString(KEY_EMAIL, address)
            .putLong(KEY_REQUEST_AT, System.currentTimeMillis()).apply()
        _status.value = Status.CodeSent(address)
        note("code sent to $address")
    }.onFailure {
        if (it is IllegalArgumentException) {
            note("code: ${it.message}")
            _status.value = Status.Failed(it.message.orEmpty())
        } else {
            fail("Couldn’t send the code", it)
        }
    }

    /** Step 2: trades the emailed [code] for a Matrix session on Beeper's homeserver. */
    suspend fun signIn(code: String): Result<Unit> = onEngine { signInBlocking(code) }

    private suspend fun signInBlocking(code: String): Result<Unit> = runCatching {
        val ctx = appContext ?: error("not started")
        val request = prefs(ctx).getString(KEY_REQUEST, null) ?: error("Ask for a code first.")
        _status.value = Status.Working
        val response = try {
            beeperPost(
                "/user/login/response",
                JSONObject().put("request", request).put("response", code.trim()).toString(),
            )
        } catch (e: BeeperHttpException) {
            if (e.code == 403 || e.code == 401) {
                // A wrong or expired code. Beeper won't take this request again either way.
                _status.value = Status.CodeSent(prefs(ctx).getString(KEY_EMAIL, null).orEmpty())
                throw IllegalArgumentException("That code didn’t work. Check it, or ask for a new one.")
            }
            throw e
        }
        // The request is spent from here on, whatever happens next.
        prefs(ctx).edit().remove(KEY_REQUEST).remove(KEY_REQUEST_AT).apply()
        val username = response.optJSONObject("whoami")?.optJSONObject("userInfo")?.optString("username")
            ?.takeIf { it.isNotBlank() } ?: error("Beeper didn’t say who you are.")
        val token = response.optString("token").takeIf { it.isNotBlank() } ?: error("Beeper didn’t hand back a login.")
        lifecycle.withLock {
            observers?.cancel()
            client?.let { old ->
                runCatching { old.stopSync() }
                runCatching { old.closeSuspending() }
            }
            client = null
            val auth = MatrixClientAuthProviderData.classicLogin(
                baseUrl = Url(HOMESERVER),
                identifier = IdentifierType.User(username),
                token = token,
                loginType = LoginType.Unknown("org.matrix.login.jwt", buildJsonObject {}),
                initialDeviceDisplayName = DEVICE_NAME,
                httpClientEngine = engine,
            ).getOrThrow()
            val c = MatrixClient.create(
                repositoriesModule = RepositoriesModule.room(databaseBuilder(ctx)),
                mediaStoreModule = MediaStoreModule.okio(mediaDir(ctx)),
                cryptoDriverModule = CryptoDriverModule.libOlm(),
                authProviderData = auth,
                configuration = configuration(),
            ).getOrThrow()
            prefs(ctx).edit().putString(KEY_USER, c.userId.full).apply()
            attach(c)
        }
    }.onFailure {
        if (it is IllegalArgumentException) note("sign-in: ${it.message}") else fail("Couldn’t sign in to Beeper", it)
    }

    /**
     * Verifies this phone with the account's recovery key, which is what lets it read encrypted
     * history from Beeper's key backup.
     *
     * Beeper creates its secret-storage key with the secret-name derivation, so stock Trixnity's
     * config-MAC check rejects the right key. Instead the key is proven by decrypting the
     * cross-signing secrets with it, and then [KeyTrustService] signs this device. From
     * fenleon/chats, where the reasoning is written out in full.
     */
    suspend fun verifyWithRecoveryKey(recoveryKey: String): Result<Unit> =
        onEngine { verifyBlocking(recoveryKey) }

    private suspend fun verifyBlocking(recoveryKey: String): Result<Unit> = runCatching {
        val c = client ?: error("Sign in first.")
        val methods = withTimeoutOrNull(10_000) { c.verification.getSelfVerificationMethods().first() }
            ?: error("Beeper hasn’t said how this account verifies yet. Try again in a minute.")
        if (methods !is VerificationService.SelfVerificationMethods.CrossSigningEnabled) {
            error("This account has no cross-signing set up.")
        }
        if (methods.methods.none { it is SelfVerificationMethod.AesHmacSha2RecoveryKey }) {
            error("This account has no recovery key.")
        }
        val key = recoveryKey.filter { it.isLetterOrDigit() }
        require(key.length >= 48) { "A recovery key is 48 characters; that was ${key.length}." }
        val bad = key.firstOrNull { it !in BASE58 }
        // The character itself is not named: the log goes out with every later report.
        require(bad == null) { "That recovery key has a character that can't appear in one. Check it and try again." }
        val keyBytes = decodeRecoveryKey(key)
        val accountData = c.di.get<GlobalAccountDataStore>(GlobalAccountDataStore::class)
        val keyId = accountData.get(DefaultSecretKeyEventContent::class).first()?.content?.key
            ?: error("No secret storage on this account.")
        val keyInfo = accountData.get(SecretKeyEventContent::class, keyId).first()?.content
            ?: error("No secret storage key $keyId.")
        c.di.get<KeySecretService>(KeySecretService::class).decryptOrCreateMissingSecrets(keyBytes, keyId, keyInfo)
        c.di.get<KeyTrustService>(KeyTrustService::class)
            .checkOwnAdvertisedMasterKeyAndVerifySelf(keyBytes, keyId, keyInfo)
            .getOrThrow()
        note("verified with the recovery key")
        (_status.value as? Status.Ready)?.let { _status.value = it.copy(verified = true) }
        // Heads that couldn't be decrypted before can be now.
        invalidateRows()
        Unit
    }.recoverCatching { e ->
        val detail = e.message.orEmpty()
        if (detail.contains("mac", ignoreCase = true) || detail.contains("did not match", ignoreCase = true)) {
            throw IllegalArgumentException("That recovery key doesn’t match. Check it and try again.")
        }
        throw e
    }.onFailure {
        if (it is CancellationException) throw it
        note("verify failed: ${it.message}")
        if (it !is IllegalArgumentException && !isNetworkError(it)) autoReport("verify with the recovery key", it)
    }

    suspend fun signOut() {
        scope.async { signOutBlocking() }.await()
    }

    private suspend fun signOutBlocking() {
        val ctx = appContext ?: return
        lifecycle.withLock {
            observers?.cancel()
            identityJob?.cancel()
            unsubscribeSync?.invoke()
            unsubscribeSync = null
            client?.let { c -> withTimeoutOrNull(5_000) { BeeperPush.unregister(ctx, c) } }
            BeeperPush.forget(ctx)
            BeeperAlerts.forget(ctx)
            BeeperIdentities.forget(ctx)
            client?.let { c ->
                runCatching { c.logout() }
                runCatching { c.closeSuspending() }
            }
            client = null
            prefs(ctx).edit().clear().apply()
            runCatching { ctx.deleteDatabase(DB) }
            runCatching { File(ctx.cacheDir, MEDIA_DIR).deleteRecursively() }
            val beeperGuids = MessageStore.get(ctx).chats().map { it.guid }.filter(BeeperMapping::isBeeper)
            MessageStore.get(ctx).deleteChat(beeperGuids)
            // Their alerts would open chats that no longer exist.
            runCatching { com.gios.lightchat.Notifications.clearChat(ctx, beeperGuids) }
            beeperGuids.forEach { runCatching { com.gios.lightchat.HeadsUp.cancel(it) } }
            rowCache.clear()
            _status.value = Status.SignedOut
            note("signed out")
        }
        _changes.tryEmit(Unit)
        // The service was only running for Beeper: with no Mac set up, nothing is left for it.
        if (!com.gios.lightchat.api.Store.hasPassword(ctx) || com.gios.lightchat.api.Store.baseUrl(ctx) == null) {
            runCatching { ctx.stopService(android.content.Intent(ctx, com.gios.lightchat.socket.SocketService::class.java)) }
        }
    }

    private fun beeperPost(path: String, body: String): JSONObject {
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $API_TOKEN")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw BeeperHttpException(code, reason ?: "Beeper said HTTP $code")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** A refusal from Beeper's login API, with its HTTP status. */
    class BeeperHttpException(val code: Int, message: String) : IllegalStateException(message)

    /** No network, DNS gone, a socket cut: the phone's problem, not a bug worth a report. */
    fun isNetworkError(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 8) {
            if (e is java.net.UnknownHostException || e is java.net.SocketException ||
                e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException ||
                e is javax.net.ssl.SSLException
            ) return true
            val m = e.message.orEmpty()
            if (m.contains("UnknownHost") || m.contains("Unable to resolve host") ||
                m.contains("NetworkError") || m.contains("timed out", ignoreCase = true) ||
                m.contains("connection abort", ignoreCase = true) || m.contains("Connection reset", ignoreCase = true)
            ) return true
            e = e.cause
            depth++
        }
        return false
    }

    /** When sync last went into ERROR, 0 while it is healthy. */
    @Volatile private var syncErrorSince = 0L

    // ------------------------------------------------------------------ observers

    private suspend fun watchStatus(c: MatrixClient) {
        var last: String? = null
        c.syncState.collectLatest { sync ->
            val name = sync.name.uppercase()
            if (name == "RUNNING") {
                lastSyncAt = android.os.SystemClock.elapsedRealtime()
                if (syncErrorSince != 0L) note("sync is back")
                syncErrorSince = 0L
            }
            if (name == "ERROR") {
                val now = android.os.SystemClock.elapsedRealtime()
                // Once per outage, not once per retry: the loop retries every few seconds.
                if (syncErrorSince == 0L) {
                    syncErrorSince = now
                    note("sync is failing")
                } else if (now - syncErrorSince > SYNC_REPORT_AFTER_MS && phoneOnline()) {
                    // Ten minutes broken with a working network: that is ours to look at.
                    autoReport("keep syncing", null)
                }
            }
            if (name == last) return@collectLatest
            last = name
            val verified = runCatching {
                withTimeoutOrNull(5_000) { c.key.getTrustLevel(c.userId, c.deviceId).firstOrNull() }
            }.getOrNull() is DeviceTrustLevel.CrossSigned
            _status.value = Status.Ready(
                userId = c.userId.full,
                verified = verified,
                rooms = rowCache.size,
                sync = sync.name.lowercase(),
            )
        }
    }

    /**
     * Keeps the Beeper rows of the conversation list current.
     *
     * Debounced: an initial sync of a busy account adds hundreds of rooms in a burst, and one pass
     * after it settles is enough. Newest rooms first, and written in batches, so the chats you
     * care about appear in seconds even when the pass takes minutes on a large account. A room is
     * only rebuilt when its newest message changed, so a pass that was cancelled by the next burst
     * resumes where it stopped instead of starting over.
     *
     * `getAll()` only re-emits when rooms come or go, not when one gets a message, so a live event
     * drops its room from [rowCache] and bumps [rewrite] (see [emitLive]).
     */
    private suspend fun watchRooms(c: MatrixClient) {
        combine(c.room.getAll(), rewrite) { rooms, _ -> rooms }.collectLatest { rooms ->
            delay(1_500)
            val ctx = appContext ?: return@collectLatest
            val store = MessageStore.get(ctx)
            // Read every room; a room that did not answer in time is unknown, not gone.
            var unread = 0
            val read = rooms.map { (id, flow) ->
                val room = try {
                    withTimeoutOrNull(2_000) { flow.firstOrNull() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (room == null) unread++
                id.full to room
            }
            currentCoroutineContext().ensureActive()
            val joined = read.mapNotNull { it.second }.filter { room ->
                room.membership == Membership.JOIN &&
                    room.createEventContent?.type !is CreateEventContent.RoomType.Space
            }.sortedByDescending { it.lastRelevantEventTimestamp }
            // Gone means Trixnity no longer lists it, or lists it as left, banned or a space.
            val stillListed = read.filter { (_, room) -> room == null || room.membership == Membership.JOIN }
                .mapTo(HashSet()) { it.first }
            val gone = rowCache.keys.filter { it !in stillListed }
            gone.forEach { rowCache.remove(it) }
            if (gone.isNotEmpty()) {
                store.deleteChat(gone.map(BeeperMapping::roomGuid))
                _changes.tryEmit(Unit)
            }
            val batch = ArrayList<Conversation>()
            var written = 0
            var failedRows = 0
            var firstRowError: Throwable? = null
            for (room in joined) {
                val head = room.lastRelevantEventId?.full
                val cached = rowCache[room.roomId.full]
                if (cached != null && cached.first == head && head != null) continue
                val row = runCatching { conversationFor(c, room) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        note("row ${room.roomId.full}: ${it.message}")
                        failedRows++
                        if (firstRowError == null) firstRowError = it
                    }
                    .getOrNull() ?: continue
                rowCache[room.roomId.full] = head to row
                batch += row
                if (batch.size >= 20) {
                    store.putChats(batch)
                    written += batch.size
                    batch.clear()
                    _changes.tryEmit(Unit)
                }
            }
            if (batch.isNotEmpty()) {
                store.putChats(batch)
                written += batch.size
                _changes.tryEmit(Unit)
            }
            if (written > 0 || gone.isNotEmpty()) {
                note("list: $written updated, ${gone.size} removed, ${rowCache.size} chats")
            }
            if (failedRows > 0) {
                note("list: $failedRows chats couldn’t be built")
                autoReport("build the chat list", firstRowError)
            }
            (_status.value as? Status.Ready)?.let { _status.value = it.copy(rooms = rowCache.size) }
            lookUpIdentities(c, joined)
        }
    }

    @Volatile private var identityJob: Job? = null

    /**
     * Asks the bridge for the numbers behind one-to-one chats it has not been asked about (see
     * [BeeperIdentities]). One small request per person, a few at a time, once: the answers are kept.
     * Its own job so a list pass that restarts does not cancel lookups halfway, and single-flight so
     * two passes do not ask twice.
     */
    private fun lookUpIdentities(c: MatrixClient, rooms: List<MatrixRoom>) {
        val ctx = appContext ?: return
        if (identityJob?.isActive == true) return
        val wanted = rooms.mapNotNull { room ->
            val row = rowCache[room.roomId.full]?.second ?: return@mapNotNull null
            if (row.isGroup) return@mapNotNull null
            val user = row.participants.singleOrNull() ?: return@mapNotNull null
            if (!BeeperIdentities.needsLookup(ctx, user)) null else room.roomId to user
        }.take(IDENTITY_BATCH)
        if (wanted.isEmpty()) return
        identityJob = scope.launch {
            var found = 0
            for ((roomId, user) in wanted) {
                val keys = runCatching { memberIdentifiers(c, roomId, user) }
                    .onFailure { if (it is CancellationException) throw it }
                    // A refusal (no such member, a bridge that sets nothing) is an answer too, kept
                    // as empty so it is asked again in a week. No network is not an answer.
                    .getOrElse { e -> if (isNetworkError(e)) return@launch else emptySet() }
                BeeperIdentities.remember(ctx, user, keys)
                if (keys.isNotEmpty()) found++
                delay(150)
            }
            if (found > 0) {
                note("people: numbers for $found chats")
                _changes.tryEmit(Unit)
            }
        }
    }

    /** One ghost's `m.room.member` content, raw, for the identifier field Trixnity has no type for. */
    private suspend fun memberIdentifiers(c: MatrixClient, roomId: RoomId, user: String): Set<String> {
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        val url = "$HOMESERVER/_matrix/client/v3/rooms/${enc(roomId.full)}/state/m.room.member/${enc(user)}"
        val body = withTimeoutOrNull(10_000) {
            c.api.baseClient.baseClient.get(url) {
                attributes.put(AuthRequired.attributeKey, AuthRequired.YES)
            }.bodyAsText()
        } ?: error("timed out")
        return BeeperIdentities.fromMemberContent(JSONObject(body))
    }

    private const val IDENTITY_BATCH = 60

    /** Every new event, as it syncs, onto the same bus the BlueBubbles socket feeds. */
    private suspend fun watchTimeline(c: MatrixClient) {
        c.room.getTimelineEventsFromNowOn(decryptionTimeout = 10.seconds).collect { te ->
            runCatching { emitLive(c, te) }.onFailure { note("live event: ${it.message}") }
        }
    }

    private suspend fun watchTyping(c: MatrixClient) {
        var typingRooms = emptySet<String>()
        c.room.usersTyping.collect { byRoom ->
            val now = byRoom.filterValues { content -> content.users.any { it != c.userId } }.keys.map { it.full }.toSet()
            (now - typingRooms).forEach { SocketBus.typing.tryEmit(TypingEvent(BeeperMapping.roomGuid(it), true)) }
            (typingRooms - now).forEach { SocketBus.typing.tryEmit(TypingEvent(BeeperMapping.roomGuid(it), false)) }
            typingRooms = now
        }
    }

    private suspend fun emitLive(c: MatrixClient, te: TimelineEvent) {
        val roomId = te.event.roomId
        val content = te.content?.getOrNull() ?: te.event.content
        // An edit arrives as its own event; the thread wants the original, now carrying new text.
        val replaced = (content as? RoomMessageEventContent)?.relatesTo as? RelatesTo.Replace
        val target = if (replaced != null) {
            withTimeoutOrNull(5_000) {
                c.room.getTimelineEvent(roomId, replaced.eventId) {
                    decryptionTimeout = 5.seconds
                    fetchTimeout = 5.seconds
                }.filterNotNull().firstOrNull()
            } ?: return
        } else {
            te
        }
        val row = rowFor(c, target) ?: return
        val convo = rowCache[roomId.full]?.second
        // The list row for this room is now out of date; the next pass rebuilds just it.
        rowCache[roomId.full]?.let { (_, cachedRow) -> rowCache[roomId.full] = null to cachedRow }
        rewrite.value = rewrite.value + 1
        SocketBus.incoming.tryEmit(
            IncomingMessage(
                chatGuid = BeeperMapping.roomGuid(roomId.full),
                message = row.message,
                isNew = replaced == null,
                chatDisplayName = convo?.displayName.orEmpty(),
                isGroup = convo?.isGroup == true,
                participants = convo?.participants.orEmpty(),
                raw = row.json,
            ),
        )
    }

    // ------------------------------------------------------------------ alerts and wake-ups

    /** Elapsed-realtime of the last finished sync. Counts deep sleep, which is the point. */
    @Volatile private var lastSyncAt = 0L

    /** A healthy loop finishes a long poll every 30 s; well past that, it is wedged or asleep. */
    private const val SYNC_STALE_MS = 75_000L

    /** How long a poll-alarm wake may spend on Beeper inside the Doze network window. */
    private const val CATCH_UP_BUDGET_MS = 8_000L

    /** The stored-message row for an alert, as the thread would show it. */
    internal suspend fun alertRow(c: MatrixClient, te: TimelineEvent): MessageStore.Row? = rowFor(c, te)

    /** The list row for a room: the one last written, else built now. */
    internal suspend fun conversationOf(c: MatrixClient, roomId: RoomId): Conversation? =
        rowCache[roomId.full]?.second ?: runCatching {
            withTimeoutOrNull(3_000) { c.room.getById(roomId).filterNotNull().firstOrNull() }?.let { conversationFor(c, it) }
        }.getOrNull()

    private suspend fun onPush(c: MatrixClient, roomId: String?, eventId: String?) {
        // Trixnity already holds the event: the live sync got there first, nothing to do.
        if (roomId != null) {
            val handled = runCatching {
                c.notification.onPush(RoomId(roomId), eventId?.let { EventId(it) })
            }.getOrDefault(false)
            if (handled) return
        }
        wake(c, "push")
    }

    /**
     * One sync now. A sync-once request interrupts the loop's long poll and sends a fresh one, so
     * this also repairs a loop stuck on a socket that died while the phone slept. Trixnity turns
     * what arrives into alerts through [BeeperAlerts] as usual.
     */
    private suspend fun wake(c: MatrixClient, reason: String) {
        val stale = android.os.SystemClock.elapsedRealtime() - lastSyncAt > SYNC_STALE_MS
        if (!stale && reason == "poll") return
        val ok = withTimeoutOrNull(CATCH_UP_BUDGET_MS) { c.syncOnce(Presence.OFFLINE).isSuccess } ?: false
        if (ok) lastSyncAt = android.os.SystemClock.elapsedRealtime()
        if (!ok) note("wake ($reason): sync didn’t finish")
        val state = c.syncState.value.name
        if (state.equals("STOPPED", true) || state.equals("ERROR", true)) {
            runCatching { c.startSync(Presence.OFFLINE) }
        }
    }

    /**
     * The poll alarm's Beeper half: restore the session if the process was restarted, then sync
     * once if the loop looks asleep. Returns the job so the caller can wait on it inside its own
     * budget; null without a Beeper account.
     */
    fun catchUpAsync(context: Context): Job? {
        if (!hasSession(context)) return null
        appContext = context.applicationContext
        return scope.launch {
            val c = runCatching { ensureClient() }.getOrNull() ?: return@launch
            wake(c, "poll")
        }
    }

    /** One line for Settings: whether push is live and when it last fired. */
    fun pushLine(context: Context): String {
        val host = runCatching { URL(BeeperPush.server(context)).host }.getOrDefault("ntfy")
        val state = if (BeeperPush.connected) "connected" else "not connected"
        val last = BeeperPush.lastPushAt.takeIf { it > 0L }?.let { at ->
            val min = (System.currentTimeMillis() - at) / 60_000
            if (min < 1) " · last push just now" else " · last push ${min}m ago"
        }.orEmpty()
        return "Push via $host: $state$last"
    }

    suspend fun setPushServer(context: Context, raw: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val server = BeeperPush.normalizeServer(raw) ?: throw IllegalArgumentException("That isn’t a web address.")
            BeeperPush.changeServer(context, client, server) { roomId, eventId ->
                client?.let { onPush(it, roomId, eventId) }
            }
        }
    }

    // ------------------------------------------------------------------ rooms → rows

    private suspend fun conversationFor(c: MatrixClient, room: MatrixRoom): Conversation? {
        val roomId = room.roomId
        val heroes = room.name?.heroes.orEmpty().filterNot { BeeperMapping.isBridgeBot(it.full) }
        val members = if (heroes.isNotEmpty()) heroes else runCatching {
            withTimeoutOrNull(2_000) { c.user.getAll(roomId).first().keys.toList() }
        }.getOrNull().orEmpty().filter { it != c.userId && !BeeperMapping.isBridgeBot(it.full) }
        val names = members.take(4).map { heroName(c, roomId, it) }
        val explicit = room.name?.explicitName?.takeIf { it.isNotBlank() }
        val title = explicit ?: names.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Chat" }
        val isGroup = !room.isDirect && (members.size > 1 || (room.name?.otherUsersCount ?: 0) > 1)
        val network = BeeperMapping.networkOfRoom(members.map { it.full }, c.userId.full)

        // Short on purpose: on a new device most heads can't be decrypted until the key backup is
        // unlocked, and a long wait per room is minutes before the list shows anything.
        val head = room.lastRelevantEventId?.let { id ->
            withTimeoutOrNull(1_500) {
                c.room.getTimelineEvent(roomId, id) {
                    decryptionTimeout = 1.seconds
                    fetchTimeout = 1.seconds
                }.filterNotNull().firstOrNull { it.content != null }
            } ?: withTimeoutOrNull(500) { c.room.getTimelineEvent(roomId, id).firstOrNull() }
        }
        val headRow = head?.let { rowFor(c, it) }
        val lastDate = head?.originTimestamp ?: room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L
        val fromMe = head?.sender == c.userId
        val readUpTo = runCatching {
            withTimeoutOrNull(2_000) {
                c.user.getReceiptsById(roomId, c.userId).firstOrNull()
                    ?.receipts?.get(ReceiptType.Read)?.receipt?.timestamp
            }
        }.getOrNull() ?: 0L
        return Conversation(
            guid = BeeperMapping.roomGuid(roomId.full),
            displayName = title,
            participants = members.map { it.full },
            isGroup = isGroup,
            lastText = headRow?.message?.previewText.orEmpty(),
            lastDate = lastDate,
            lastFromMe = fromMe,
            lastSender = if (fromMe) null else headRow?.message?.sender,
            unread = head != null && !fromMe && lastDate > readUpTo,
            network = network,
        )
    }

    private suspend fun heroName(c: MatrixClient, roomId: RoomId, user: UserId): String =
        runCatching { withTimeoutOrNull(1_500) { c.user.getById(roomId, user).firstOrNull()?.name } }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: user.localpart

    // ------------------------------------------------------------------ events → messages

    /** A timeline event as a stored message row, or null for anything the thread doesn't show. */
    private suspend fun rowFor(c: MatrixClient, te: TimelineEvent, readUpTo: Long = 0L): MessageStore.Row? {
        val event = te.event as? ClientEvent.RoomEvent.MessageEvent<*> ?: return null
        val roomGuid = BeeperMapping.roomGuid(event.roomId.full)
        val fromMe = event.sender == c.userId
        val senderName = if (fromMe) null else heroName(c, event.roomId, event.sender)
        val decrypted = te.content
        val content = decrypted?.getOrNull() ?: if (te.event.content !is EncryptedMessageEventContent) te.event.content else null
        val txn = te.unsigned?.transactionId
        val base = BeeperMapping.Event(
            eventId = event.id.full,
            sender = event.sender.full,
            senderName = senderName,
            fromMe = fromMe,
            timestamp = event.originTimestamp,
            text = "",
            readAt = if (fromMe && readUpTo >= event.originTimestamp) readUpTo else 0L,
            tempGuid = txn?.let { tempGuids[it] },
        )
        val mapped = when (content) {
            null -> base.copy(
                text = if (decrypted?.isFailure == true) {
                    "Couldn’t decrypt this message yet. Verify this phone in Settings."
                } else {
                    "Decrypting…"
                },
            )
            is RoomMessageEventContent -> {
                if (content.relatesTo is RelatesTo.Replace) return null
                val reply = content.relatesTo?.replyTo?.eventId?.full
                val files = (content as? RoomMessageEventContent.FileBased)?.let { f -> listOf(fileOf(f)) }.orEmpty()
                val text = when (content) {
                    is RoomMessageEventContent.FileBased -> ""
                    else -> BeeperMapping.stripEditFallback(BeeperMapping.stripReplyFallback(content.body))
                }
                base.copy(
                    text = if (files.isNotEmpty()) ChatMessage.ATTACHMENT_PLACEHOLDER.takeIf { text.isBlank() } ?: text else text,
                    files = files,
                    replyTo = reply,
                    editedAt = if (te.isReplaced) event.originTimestamp + 1 else 0L,
                    isCall = content is RoomMessageEventContent.TextBased.Notice && BeeperMapping.isCallNotice(content.body),
                )
            }
            is ReactionEventContent -> {
                val relates = content.relatesTo ?: return null
                base.copy(reactionTarget = relates.eventId.full, reactionKey = relates.key)
            }
            is RedactedEventContent -> {
                // A removed reaction shows as its removal, recovered from the stored original.
                val ctx = appContext
                val original = ctx?.let { MessageStore.get(it).messageByGuid(event.id.full) }
                if (original?.isReaction == true) {
                    base.copy(
                        reactionTarget = original.associatedMessageGuid,
                        reactionRemoval = original.reactionType,
                        reactionRemovalEmoji = original.associatedMessageEmoji,
                    )
                } else {
                    base.copy(text = "Message deleted")
                }
            }
            else -> return null
        }
        val json = BeeperMapping.messageJson(mapped, event.roomId.full).toString()
        val message = BlueBubblesApi.parseMessage(JSONObject(json))
        return MessageStore.Row(roomGuid, message, json)
    }

    private fun fileOf(f: RoomMessageEventContent.FileBased): BeeperMapping.File {
        val info = f.info
        val (w, h) = when (info) {
            is ImageInfo -> (info.width ?: 0) to (info.height ?: 0)
            is VideoInfo -> (info.width ?: 0) to (info.height ?: 0)
            else -> 0 to 0
        }
        return BeeperMapping.File(
            mimeType = info?.mimeType ?: when (f) {
                is RoomMessageEventContent.FileBased.Image -> "image/jpeg"
                is RoomMessageEventContent.FileBased.Video -> "video/mp4"
                is RoomMessageEventContent.FileBased.Audio -> "audio/ogg"
                else -> null
            },
            name = f.fileName ?: f.body,
            width = w,
            height = h,
        )
    }

    // ------------------------------------------------------------------ threads

    /**
     * The newest page of a room (or the page before [beforeEventId]), written to the store and
     * returned oldest-first with reactions included, the way `Sync.thread` returns iMessage.
     */
    suspend fun thread(roomGuid: String, beforeEventId: String? = null, limit: Int = PAGE): List<ChatMessage> {
        val c = ensureClient() ?: return emptyList()
        val ctx = appContext ?: return emptyList()
        val roomId = RoomId(BeeperMapping.roomIdOf(roomGuid) ?: return emptyList())
        val events = collect(c, roomId, beforeEventId, limit)
        val readUpTo = othersReadUpTo(c, roomId)
        val rows = events.mapNotNull { runCatching { rowFor(c, it, readUpTo) }.getOrNull() }
        val store = MessageStore.get(ctx)
        store.putMessages(rows)
        store.setThreadLoaded(roomGuid)
        return rows.map { it.message }.sortedBy { it.date }
    }

    private suspend fun collect(c: MatrixClient, roomId: RoomId, before: String?, max: Int): List<TimelineEvent> {
        val out = ArrayList<TimelineEvent>()
        val config: GetTimelineEventsConfig.() -> Unit = {
            maxSize = max.toLong()
            fetchTimeout = 10.seconds
            decryptionTimeout = 10.seconds
        }
        withTimeoutOrNull(FETCH_BUDGET_MS) {
            val flows = if (before == null) {
                c.room.getLastTimelineEvents(roomId, config).filterNotNull().first()
            } else {
                c.room.getTimelineEvents(roomId, EventId(before), Direction.BACKWARDS, config)
            }
            // Decryption is all-or-nothing per room: when the first event won't decrypt, the rest
            // won't either, so stop waiting on each one (from fenleon/chats).
            var patient = true
            flows.collect { eventFlow ->
                val resolved = withTimeoutOrNull(if (patient) DECRYPT_WAIT_MS else QUICK_DECRYPT_WAIT_MS) {
                    eventFlow.filterNotNull().firstOrNull {
                        it.content?.getOrNull() != null || it.event.content !is EncryptedMessageEventContent
                    }
                } ?: eventFlow.filterNotNull().firstOrNull()
                if (resolved != null) {
                    patient = resolved.content?.getOrNull() != null || resolved.event.content !is EncryptedMessageEventContent
                    if (resolved.event.id.full != before) out += resolved
                }
            }
        }
        return out
    }

    /** The newest point anyone else has read to, as a timestamp, for the "Read" mark. */
    private suspend fun othersReadUpTo(c: MatrixClient, roomId: RoomId): Long = runCatching {
        withTimeoutOrNull(3_000) {
            val receipts = c.user.getAllReceipts(roomId).first()
            receipts.filterKeys { it != c.userId && !BeeperMapping.isBridgeBot(it.full) }.values
                .mapNotNull { it.firstOrNull()?.receipts?.get(ReceiptType.Read)?.receipt?.timestamp }
                .maxOrNull()
        }
    }.getOrNull() ?: 0L

    // ------------------------------------------------------------------ sending

    /**
     * Sends [body] (as a reply to [replyTo] when given) and waits for Beeper to accept it.
     * [tempGuid] is the optimistic bubble's guid; the echo carries it so the bubble is replaced,
     * not doubled.
     */
    suspend fun sendText(roomGuid: String, body: String, replyTo: String?, tempGuid: String): ChatMessage {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        val roomId = roomIdOrThrow(roomGuid)
        val txn = c.room.sendMessage(roomId) {
            if (replyTo != null) reply(EventId(replyTo), null)
            text(body)
        }
        tempGuids[txn] = tempGuid
        return awaitSent(c, roomId, txn, tempGuid, body)
    }

    /**
     * Sends a photo, video or file. The mime type decides which Matrix message it becomes: an
     * image or a video is drawn inline on the other side, anything else arrives as a file.
     */
    suspend fun sendMedia(roomGuid: String, bytes: ByteArray, name: String, mimeType: String, tempGuid: String): ChatMessage {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        val roomId = roomIdOrThrow(roomGuid)
        val type = runCatching { ContentType.parse(mimeType) }.getOrNull()
        val txn = c.room.sendMessage(roomId) {
            when {
                mimeType.startsWith("image/") -> {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    image(
                        body = name,
                        image = flowOf(bytes),
                        fileName = name,
                        type = type,
                        size = bytes.size.toLong(),
                        width = bounds.outWidth.takeIf { it > 0 },
                        height = bounds.outHeight.takeIf { it > 0 },
                    )
                }
                mimeType.startsWith("video/") -> video(
                    body = name,
                    video = flowOf(bytes),
                    fileName = name,
                    type = type,
                    size = bytes.size.toLong(),
                )
                else -> file(
                    body = name,
                    file = flowOf(bytes),
                    fileName = name,
                    type = type,
                    size = bytes.size.toLong(),
                )
            }
        }
        tempGuids[txn] = tempGuid
        return awaitSent(c, roomId, txn, tempGuid, ChatMessage.ATTACHMENT_PLACEHOLDER)
    }

    /** Forgets which rows were written, so the next room change rewrites them all (after a
     *  BlueBubbles sign-out cleared the shared store). */
    fun invalidateRows() {
        rowCache.clear()
        rewrite.value = rewrite.value + 1
    }

    /** Bumped to make [watchRooms] run a pass with no room having changed. */
    private val rewrite = MutableStateFlow(0)

    private suspend fun awaitSent(c: MatrixClient, roomId: RoomId, txn: String, tempGuid: String, text: String): ChatMessage {
        val sent = withTimeoutOrNull(SEND_ACK_BUDGET_MS) {
            c.room.getOutbox(roomId, txn).filterNotNull().first { it.eventId != null || it.sendError != null }
        }
        val eventId = sent?.eventId?.full
        if (eventId == null) {
            val why = sent?.sendError?.toString() ?: "Beeper didn’t confirm it in time."
            note("send failed in ${roomId.full}: $why")
            // The bubble is about to be taken back; the outbox must not send it later behind the
            // user's back, or a retry arrives twice.
            runCatching { c.room.cancelSendMessage(roomId, txn) }
            val offline = why.contains("NetworkError") || why.contains("UnknownHost") || !phoneOnline()
            if (!offline) {
                autoReport(if (sent == null) "send (no confirmation)" else "send (refused)", IllegalStateException(why))
            }
            error(if (offline) "No connection. The message wasn’t sent." else why)
        }
        return ChatMessage(
            guid = eventId,
            text = text,
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            dateDelivered = System.currentTimeMillis(),
            tempGuid = tempGuid,
        )
    }

    /** Puts a tapback on [targetEventId]. Reactions go unencrypted, as every Matrix client sends them. */
    suspend fun react(roomGuid: String, targetEventId: String, type: ReactionType, emoji: String? = null): ChatMessage {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        val roomId = roomIdOrThrow(roomGuid)
        val key = if (type == ReactionType.EMOJI) requireNotNull(emoji) else BeeperMapping.emojiFor(type)
        val id = c.api.room.sendMessageEvent(
            roomId,
            ReactionEventContent(relatesTo = RelatesTo.Annotation(EventId(targetEventId), key)),
        ).getOrThrow()
        return ChatMessage(
            guid = id.full,
            text = "",
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            associatedMessageGuid = targetEventId,
            associatedMessageType = type.apiValue,
            associatedMessageEmoji = if (type == ReactionType.EMOJI) key else null,
        )
    }

    /** Takes back a reaction or a message: a Matrix redaction either way. */
    suspend fun redact(roomGuid: String, eventId: String) {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        c.api.room.redactEvent(roomIdOrThrow(roomGuid), EventId(eventId)).getOrThrow()
    }

    /** Replaces the words of one of our messages (an `m.replace` edit, encrypted like a send). */
    suspend fun edit(roomGuid: String, eventId: String, body: String) {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        c.room.sendMessage(roomIdOrThrow(roomGuid)) {
            replace(EventId(eventId))
            text(body)
        }
    }

    /** Marks the room read up to its newest message, on every device on the account. */
    suspend fun markRead(roomGuid: String) {
        val c = ensureClient() ?: return
        val roomId = RoomId(BeeperMapping.roomIdOf(roomGuid) ?: return)
        val head = withTimeoutOrNull(3_000) { c.room.getById(roomId).firstOrNull()?.lastEventId } ?: return
        c.api.room.setReadMarkers(roomId, fullyRead = head, read = head)
            .onFailure { note("mark read ${roomId.full}: ${it.message}") }
        rowCache[roomId.full]?.let { (h, row) -> rowCache[roomId.full] = h to row.copy(unread = false) }
    }

    suspend fun typing(roomGuid: String, on: Boolean) {
        val c = client ?: return
        val roomId = RoomId(BeeperMapping.roomIdOf(roomGuid) ?: return)
        c.api.room.setTyping(roomId, c.userId, on, if (on) TYPING_TIMEOUT_MS else null)
    }

    suspend fun rename(roomGuid: String, name: String) {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        c.api.room.sendStateEvent(roomIdOrThrow(roomGuid), NameEventContent(name)).getOrThrow()
    }

    suspend fun leave(roomGuid: String) {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        c.api.room.leaveRoom(roomIdOrThrow(roomGuid)).getOrThrow()
    }

    /** Downloads (and decrypts) an attachment into [dest]. False when it can't be found or fetched. */
    suspend fun download(attachmentGuid: String, dest: File): Boolean {
        val c = ensureClient() ?: return false
        val ref = BeeperMapping.parseAttachmentGuid(attachmentGuid) ?: return false
        val te = withTimeoutOrNull(15_000) {
            c.room.getTimelineEvent(RoomId(ref.roomId), EventId(ref.eventId)) {
                decryptionTimeout = 10.seconds
                fetchTimeout = 10.seconds
            }.filterNotNull().firstOrNull { it.content != null }
        } ?: return false
        val content = te.content?.getOrNull() as? RoomMessageEventContent.FileBased ?: return false
        val media = withTimeoutOrNull(60_000) {
            val encrypted = content.file
            val url = content.url
            when {
                encrypted != null -> c.media.getEncryptedMedia(encrypted, maxSize = null)
                url != null -> c.media.getMedia(url, maxSize = null)
                else -> null
            }
        }?.getOrNull() ?: return false
        val bytes = media.toByteArray() ?: return false
        dest.writeBytes(bytes)
        return true
    }

    private fun roomIdOrThrow(roomGuid: String): RoomId =
        RoomId(BeeperMapping.roomIdOf(roomGuid) ?: error("Not a Beeper chat: $roomGuid"))

    // ------------------------------------------------------------------ diagnostics

    private fun fail(what: String, t: Throwable) {
        if (t is CancellationException) throw t
        val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        note("$what: $message")
        _status.value = Status.Failed(if (isNetworkError(t)) "$what: no connection." else "$what: $message")
        if (!isNetworkError(t)) autoReport(what.lowercase(), t)
    }

    /** Files a report by itself; see [BeeperReports] for the throttle. Kind names the failure's family. */
    private fun autoReport(kind: String, t: Throwable?) {
        val ctx = appContext ?: return
        if (!BeeperReports.shouldSend(ctx, kind)) return
        note("sending a report: $kind")
        val snapshot = _log.value
        scope.launch { BeeperReports.send(ctx, kind, t, snapshot) }
    }

    /** The Settings button: send the log now, whatever the throttle says. */
    suspend fun sendLogNow(): Boolean {
        val ctx = appContext ?: return false
        val lines = _log.value.ifEmpty { readSavedLog(ctx) }
        if (lines.isEmpty()) return false
        note("log sent from Settings")
        BeeperReports.send(ctx, "log from settings", null, lines + _log.value.takeLast(1), manual = true)
        return true
    }

    /** The log as the last process left it, for a report sent right after a restart. */
    private fun readSavedLog(ctx: Context): List<String> =
        runCatching { File(ctx.filesDir, LOG_FILE).readLines().takeLast(300) }.getOrDefault(emptyList())

    private const val LOG_FILE = "beeper_log.txt"

    /** Whether there is anything worth sending. */
    fun hasLog(): Boolean = _log.value.isNotEmpty() || (appContext?.let { File(it.filesDir, LOG_FILE).length() > 0 } == true)

    /** Whether the phone has a validated network right now. */
    private fun phoneOnline(): Boolean {
        val ctx = appContext ?: return true
        return runCatching {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(true)
    }

    fun note(line: String) {
        Log.d(TAG, line)
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val entry = "$stamp $line"
        _log.value = (_log.value + entry).takeLast(300)
        // Kept on disk too, so a report sent after a restart still says what happened. Trimmed to
        // the last 300 lines whenever it passes 600.
        appContext?.let { ctx ->
            logWriter.execute {
                runCatching {
                    val f = File(ctx.filesDir, LOG_FILE)
                    f.appendText(entry + "\n")
                    if (f.length() > 64_000) f.writeText(f.readLines().takeLast(300).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    private val logWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
}
