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
import io.ktor.http.ContentType
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * Alerts (Phase 4: push through our own gateway), emoji verification with another device, the
 * full emoji set for reactions, and adding people to a bridged group.
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
    private const val DB = "beeper_matrix"
    private const val MEDIA_DIR = "beeper_media"
    private const val DEVICE_NAME = "BrightChat (Light Phone)"

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
                if (prefs(context).getString(KEY_REQUEST, null) != null) _status.value = Status.CodeSent(email)
            }
            return
        }
        scope.launch { runCatching { ensureClient() }.onFailure { note("restore failed: ${it.message}") } }
    }

    private suspend fun ensureClient(): MatrixClient? = lifecycle.withLock {
        client?.let { return@withLock it }
        val ctx = appContext ?: return@withLock null
        _status.value = Status.Working
        note("restoring session")
        val restored = MatrixClient.create(
            repositoriesModule = RepositoriesModule.room(databaseBuilder(ctx)),
            mediaStoreModule = MediaStoreModule.okio(mediaDir(ctx)),
            cryptoDriverModule = CryptoDriverModule.libOlm(),
            authProviderData = null,
            configuration = configuration(),
        ).getOrElse { e ->
            note("restore failed: ${e.message}")
            null
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
        observers = scope.launch {
            launch { runCatching { c.startSync(Presence.OFFLINE) }.onFailure { note("sync failed to start: ${it.message}") } }
            launch { watchStatus(c) }
            launch { watchRooms(c) }
            launch { watchTimeline(c) }
            launch { watchTyping(c) }
        }
        note("signed in as ${c.userId.full} on ${c.deviceId}")
    }

    private fun configuration(): MatrixClientConfiguration.() -> Unit = {
        name = "brightchat-beeper"
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
    suspend fun requestCode(email: String): Result<Unit> = withContext(Dispatchers.IO) { requestCodeBlocking(email) }

    // Network and disk, so never on the caller's thread: the Settings field calls this from a
    // Compose scope, which is the main thread (v2.44.91 failed with NetworkOnMainThreadException).
    private suspend fun requestCodeBlocking(email: String): Result<Unit> = runCatching {
        val ctx = appContext ?: error("not started")
        val address = email.trim()
        require(address.contains("@")) { "That doesn’t look like an email address." }
        val init = beeperPost("/user/login", "{}")
        val request = init.optString("request").takeIf { it.isNotBlank() } ?: error("Beeper didn’t start a login.")
        beeperPost("/user/login/email", JSONObject().put("request", request).put("email", address).toString())
        prefs(ctx).edit().putString(KEY_REQUEST, request).putString(KEY_EMAIL, address).apply()
        _status.value = Status.CodeSent(address)
        note("code sent to $address")
    }.onFailure { fail("Couldn’t send the code", it) }

    /** Step 2: trades the emailed [code] for a Matrix session on Beeper's homeserver. */
    suspend fun signIn(code: String): Result<Unit> = withContext(Dispatchers.IO) { signInBlocking(code) }

    private suspend fun signInBlocking(code: String): Result<Unit> = runCatching {
        val ctx = appContext ?: error("not started")
        val request = prefs(ctx).getString(KEY_REQUEST, null) ?: error("Ask for a code first.")
        _status.value = Status.Working
        val response = beeperPost(
            "/user/login/response",
            JSONObject().put("request", request).put("response", code.trim()).toString(),
        )
        val username = response.optJSONObject("whoami")?.optJSONObject("userInfo")?.optString("username")
            ?.takeIf { it.isNotBlank() } ?: error("Beeper didn’t say who you are.")
        val token = response.optString("token").takeIf { it.isNotBlank() } ?: error("Beeper didn’t hand back a login.")
        lifecycle.withLock {
            client?.let { runCatching { it.stopSync() } }
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
            prefs(ctx).edit().putString(KEY_USER, c.userId.full).remove(KEY_REQUEST).apply()
            attach(c)
        }
    }.onFailure { fail("Couldn’t sign in to Beeper", it) }

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
        withContext(Dispatchers.IO) { verifyBlocking(recoveryKey) }

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
    }.onFailure { note("verify failed: ${it.message}") }

    suspend fun signOut() = withContext(Dispatchers.IO) { signOutBlocking() }

    private suspend fun signOutBlocking() {
        val ctx = appContext ?: return
        lifecycle.withLock {
            observers?.cancel()
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
            rowCache.clear()
            _status.value = Status.SignedOut
            note("signed out")
        }
        _changes.tryEmit(Unit)
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
                error(reason ?: "Beeper said HTTP $code")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ observers

    private suspend fun watchStatus(c: MatrixClient) {
        c.syncState.collectLatest { sync ->
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
            val joined = rooms.values.mapNotNull { flow ->
                runCatching { withTimeoutOrNull(2_000) { flow.firstOrNull() } }.getOrNull()
            }.filter { room ->
                room.membership == Membership.JOIN &&
                    room.createEventContent?.type !is CreateEventContent.RoomType.Space
            }.sortedByDescending { it.lastRelevantEventTimestamp }
            val live = joined.mapTo(HashSet()) { it.roomId.full }
            val gone = rowCache.keys.filter { it !in live }
            gone.forEach { rowCache.remove(it) }
            if (gone.isNotEmpty()) {
                store.deleteChat(gone.map(BeeperMapping::roomGuid))
                _changes.tryEmit(Unit)
            }
            val batch = ArrayList<Conversation>()
            var written = 0
            for (room in joined) {
                val head = room.lastRelevantEventId?.full
                val cached = rowCache[room.roomId.full]
                if (cached != null && cached.first == head && head != null) continue
                val row = runCatching { conversationFor(c, room) }
                    .onFailure { if (it is CancellationException) throw it; note("row ${room.roomId.full}: ${it.message}") }
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
            (_status.value as? Status.Ready)?.let { _status.value = it.copy(rooms = rowCache.size) }
        }
    }

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
            error(why)
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
    suspend fun react(roomGuid: String, targetEventId: String, type: ReactionType): ChatMessage {
        val c = ensureClient() ?: error("Beeper isn’t signed in.")
        val roomId = roomIdOrThrow(roomGuid)
        val id = c.api.room.sendMessageEvent(
            roomId,
            ReactionEventContent(relatesTo = RelatesTo.Annotation(EventId(targetEventId), BeeperMapping.emojiFor(type))),
        ).getOrThrow()
        return ChatMessage(
            guid = id.full,
            text = "",
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            associatedMessageGuid = targetEventId,
            associatedMessageType = type.apiValue,
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
        _status.value = Status.Failed("$what: $message")
    }

    fun note(line: String) {
        Log.d(TAG, line)
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.value = (_log.value + "$stamp $line").takeLast(300)
    }
}
