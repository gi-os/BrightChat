package com.gios.lightchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay
import com.gios.lightchat.api.Store
import com.gios.lightchat.socket.AppForeground
import com.gios.lightchat.ui.AgentEditScreen
import com.gios.lightchat.ui.AgentThreadScreen
import com.gios.lightchat.ui.ConversationTab
import com.gios.lightchat.ui.ConversationsScreen
import com.gios.lightchat.ui.DialerScreen
import com.gios.lightchat.ui.NewMessageScreen
import com.gios.lightchat.ui.NewsletterComposeScreen
import com.gios.lightchat.ui.NewsletterEditScreen
import com.gios.lightchat.ui.NewsletterScreen
import com.gios.lightchat.ui.SettingsScreen
import com.gios.lightchat.ui.SetupScreen
import com.gios.lightchat.ui.ThreadScreen
import com.gios.lightchat.ui.tabOf
import com.gios.lightchat.ui.KeepScreenOn
import com.gios.lightchat.ui.theme.LightChatTheme

/** The recipient extra on an incoming share. AOSP messaging's key, and what Roll sends. */
private const val SHARE_EXTRA_ADDRESS = "address"

/**
 * A chat-room guid on an incoming share — how a sender addresses a *group*.
 *
 * There is no AOSP convention for this, because AOSP's model of a recipient is an address and
 * a group iMessage does not have one: it is a room on the server with its own identity, and the
 * set of people in it is a property of the room rather than the way you reach it. So this is a
 * key private to these two apps, read from this app's own ChatsProvider — which is what makes
 * it safe to treat as opaque and pass straight through to the API. A sender that doesn't know
 * about it keeps working unchanged; it only ever adds a case.
 */
private const val SHARE_EXTRA_CHAT_GUID = "chat_guid"

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` calls the window callback before
     * it walks the view hierarchy — which is what lets a notch beat the compose bar while
     * it holds focus and the keyboard is up.
     *
     * Both halves of the pair are consumed. One notch is a complete DOWN+UP, and letting
     * the UP through means the focused text field takes it as a keypress: the wheel would
     * type into the message you were about to send.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw: the handler chains onto whatever is
        // already installed and only writes a file, so it is safe this early.
        LightReport.install(
            context = this,
            appName = "LightChat",
            label = "chat",
            token = BuildConfig.REPORT_TOKEN,
        )
        Notifications.ensureChannels(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableImmersive()
        setContent {
            // The screen stays awake while something slow is in flight.
            //
            // Two things in this app take longer than the display timeout. A broadcast is one send
            // per recipient down a single tunnel to a Mac, deliberately in sequence so they arrive
            // in order and do not compete for the socket — twenty recipients is a minute or two,
            // not a moment. A transcription is a whole audio file uploaded to a Whisper server and
            // a model run over it, which on a self-hosted box can be slower than the recording was.
            //
            // Both survive the panel going dark, so this is not about correctness: it is that you
            // cannot see how far either has got, and the only way to find out is to wake the phone
            // and hope the line is still there. Worse on a phone this size, where the display
            // timeout is short by design.
            //
            // Scoped to these two and not to every upload on purpose. A photo takes a second or two
            // and holding the screen on for that would cost battery all day for nothing.
            //
            // Recording a dictation holds the screen too, but from inside [rememberDictation] —
            // whether the microphone is open is not this activity's business, and every screen that
            // can dictate goes through that one function.
            val progress by viewModel.state.collectAsState()
            val broadcasting = progress.newsletterProgress?.let { !it.done } == true
            KeepScreenOn(broadcasting || progress.transcribing)
            LightChatTheme {
                // Every screen below can reach the wheel.
                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    LightChatApp(viewModel)
                }
                // Shake to report, the crash offer on next launch, and the app's own noticed
                // failures. A sibling, not a wrapper — the sheet is its own window, so it covers
                // the app whether or not it contains it.
                ReportOverlay()
            }
        }
        handlePasswordExtra(intent)
        handleChatGuidExtra(intent)
        handleSharedImages(intent)
    }

    // Hide the status + navigation bars for a full-screen, edge-to-edge look
    // (matches vandamd's LightOS apps, which call the same thing via Expo). The
    // bars slide back transiently on an edge swipe, then re-hide. Re-applied on
    // focus because returning from the keyboard/recents can resurface them.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    private fun enableImmersive() {
        // Hide the top status bar only; keep the nav bar so the bottom compose
        // field clears the gesture strip. Transient-on-swipe, re-applied on focus.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Track foreground so the socket service only notifies when the user has
    // actually left the app, and clear any message notification on return.
    override fun onStart() {
        super.onStart()
        AppForeground.active = true
        Notifications.clear(this)
        // The user is here; a box telling them about a message they're about to read is
        // just something in the way.
        HeadsUpOverlay.hide()
        // Re-pull the conversation list on every return to the app, not just cold
        // start — the socket only runs while the service does. Rate-guarded in the
        // ViewModel so this doesn't duplicate the init refresh.
        viewModel.refreshOnResume()
        // Re-arm the asleep-phone poll. Idempotent, and it repairs the chain if a firing
        // was ever lost (force-stop cancels every alarm an app has).
        PollAlarm.schedule(this)
        // And the backstop that notices when that chain has gone missing entirely.
        DeliveryWorker.ensure(this)
        // Anything held from a previous visit is stale now — the user is here and the list
        // shows it, so a notification for it would be a row about a message they can see.
        PendingAlerts.clearAll()
        // Re-lift grayscale if the user left with the image viewer open.
        ColorMode.onAppVisible(this)
    }

    override fun onStop() {
        super.onStop()
        AppForeground.active = false
        // On this phone, leaving the app almost always means the screen went off. Messages
        // that arrived while it was open were deliberately not alerted for, on the
        // assumption the user was looking at them — an assumption that expires right here.
        // Post them now, plainly: no buzz and no box, since they arrived while the phone was
        // in hand and the point is only that they end up in the notification list (and so on
        // LightGlance's dot) rather than nowhere. See PendingAlerts.
        flushPendingAlerts()
        // The rest of the phone must stay B&W even if the viewer is still open.
        ColorMode.onAppHidden(this)
    }

    private fun flushPendingAlerts() {
        val held = PendingAlerts.drain()
        if (held.isEmpty()) return
        // Seen entries — the thread was open, or the message arrived into the thread on
        // screen — are not posted: a notification at screen-off about the message the
        // user just read and answered is the noise this map exists to prevent. They are
        // still drained and still counted below, because the watermark must pass them.
        held.filterNot { it.seen }.forEach { Notifications.post(this, it.title, it.text, it.chatGuid) }
        // These have now been alerted for, so the catch-up's watermark may pass them. It is
        // deliberately held below anything suppressed while the app was open (see CatchUp),
        // and without moving it here the next background poll would find the same messages
        // still unread — on a server with no Private API nothing ever marks them read — and
        // post and buzz for them a second time.
        val newest = held.maxOf { it.date }
        Store.setLastAlertedAt(this, maxOf(Store.lastAlertedAt(this), newest))
    }

    // singleTask, so a re-launch with a fresh extra comes through here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePasswordExtra(intent)
        handleChatGuidExtra(intent)
        handleSharedImages(intent)
    }

    /**
     * A photograph shared in from another app — Roll's send picker, or anything else that
     * registers an image share.
     *
     * **The URIs are copied into this app's cache before anything else happens.** A share
     * grant is scoped to the receiving *activity's* lifetime, so holding the URI and reading
     * it later — after the send coroutine has been rescheduled, or after a configuration
     * change — hands back a SecurityException that looks like a corrupt photograph. Copying
     * is also what makes the existing send path usable unchanged: it takes `File`s, because
     * the in-app picker walks the filesystem directly rather than going through MediaStore.
     *
     * The recipient rides in an `address` extra — the AOSP messaging convention, and what
     * Roll sends for a person. A group instead rides in `chat_guid`, since a group has no
     * address to put in the AOSP extra (see [SHARE_EXTRA_CHAT_GUID]). With neither, the
     * photographs wait until a thread is opened.
     */
    private fun handleSharedImages(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

            Intent.ACTION_SEND_MULTIPLE ->
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

            else -> return
        }
        if (uris.isEmpty()) return
        // Consumed, so an activity recreation doesn't send the same photographs twice.
        intent.removeExtra(Intent.EXTRA_STREAM)
        val address = intent.getStringExtra(SHARE_EXTRA_ADDRESS)?.trim().orEmpty()
        val chatGuid = intent.getStringExtra(SHARE_EXTRA_CHAT_GUID)?.trim().orEmpty()
        intent.removeExtra(SHARE_EXTRA_ADDRESS)
        intent.removeExtra(SHARE_EXTRA_CHAT_GUID)
        val files = uris.mapNotNull { copyIntoCache(it) }
        if (files.isEmpty()) return
        viewModel.receiveShared(address, chatGuid, files)
    }

    /** Copies a shared URI into `cacheDir/shared-in`, returning null if it can't be read. */
    private fun copyIntoCache(uri: Uri): java.io.File? = runCatching {
        val dir = java.io.File(cacheDir, "shared-in").apply { mkdirs() }
        // The name only has to carry a plausible extension — the send reads the mime type
        // off it — and be unique enough that two shares in a row don't collide.
        // The extension is what the send path reads the mime type back off, so it has to be one
        // [MediaKind.mimeOf] knows — a mime *subtype* is not an extension. See [MediaKind].
        val extension = MediaKind.extensionOf(contentResolver.getType(uri))
        val out = java.io.File(dir, "share-" + System.nanoTime() + "." + extension)
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    /** A tapped message notification carries its chat's guid — jump straight to
     *  that thread rather than wherever the app was left. */
    private fun handleChatGuidExtra(intent: Intent?) {
        val guid = intent?.getStringExtra(Notifications.EXTRA_CHAT_GUID)
            ?.takeIf { it.isNotBlank() } ?: return
        // Consume it so an activity recreation doesn't re-trigger the jump.
        intent.removeExtra(Notifications.EXTRA_CHAT_GUID)
        viewModel.openByGuid(guid)
    }

    /**
     * Lets setup be pushed over adb instead of typed on the phone:
     * `adb shell am start -n com.gios.lightchat/.MainActivity -e server YOUR_URL -e password YOUR_PASSWORD`
     * The `server` extra is optional once a URL is already stored.
     */
    private fun handlePasswordExtra(intent: Intent?) {
        val password = intent?.getStringExtra("password")?.takeIf { it.isNotBlank() } ?: return
        val server = intent.getStringExtra("server")?.takeIf { it.isNotBlank() }
            ?: Store.baseUrl(this) ?: return
        viewModel.saveSetup(server, password)
    }
}

@Composable
fun LightChatApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    // Which conversation tab is showing, and where each one is scrolled to. Both
    // live *here*, above the `when` — ConversationsScreen is removed from the
    // composition whenever a thread, settings or the composer is open, so anything
    // remembered inside it is thrown away and coming back would reset the list to
    // the top. rememberSaveable carries them through process death too.
    // **Favorites is the front page.** The starred list is the handful of people this phone is
    // actually for, and opening on the full message list meant scrolling past everyone else to
    // reach them. Known is still one tap away and still called "Messages".
    var tab by rememberSaveable { mutableStateOf(ConversationTab.Favorites) }
    // Whether the list header's "there's Settings behind this title" hint has already played
    // this app session. Plain `remember`, not `rememberSaveable`: the point is once-per-open,
    // and a fresh process is a fresh "open".
    var titleHintPlayed by remember { mutableStateOf(false) }
    // One scroll position per tab, so switching tabs doesn't scramble the others.
    // Spelled out rather than built in a loop: `remember` inside an iteration is
    // positional, and three named values are easier to trust than that.
    val favoritesScroll = rememberLazyListState()
    val knownScroll = rememberLazyListState()
    val unknownScroll = rememberLazyListState()
    val dialScroll = rememberLazyListState()

    // A tapped notification can open a thread that isn't on the current tab (a
    // message from an unknown number while Known is showing). Follow it, so closing
    // the thread lands on the list that actually contains it instead of one where
    // the chat you were just reading is nowhere to be seen.
    val openGuid = state.open?.guid
    LaunchedEffect(openGuid) {
        val open = state.open ?: return@LaunchedEffect
        tab = tabOf(open, state.contacts, state.favorites)
    }

    when {
        !state.isConfigured -> {
            // A rejected password sends us back here; make sure settings is dismissed.
            showSettings = false
            SetupScreen(viewModel)
        }
        // Newsletter, innermost first: the editor and the composer are both opened *from* the
        // batch list, so they have to be matched before it or opening either would still draw
        // the list underneath them.
        state.newsletterEditor != null -> {
            val batch = state.newsletterEditor!!
            BackHandler { viewModel.closeNewsletterEditor() }
            NewsletterEditScreen(viewModel, batch)
        }
        state.newsletterCompose != null -> {
            val batch = state.newsletterCompose!!
            BackHandler { viewModel.closeNewsletterCompose() }
            NewsletterComposeScreen(viewModel, batch)
        }
        state.newsletterList -> {
            BackHandler { viewModel.closeNewsletters() }
            NewsletterScreen(viewModel)
        }
        state.composingNew -> {
            BackHandler { viewModel.cancelNewMessage() }
            NewMessageScreen(viewModel)
        }
        state.agentEditor -> {
            BackHandler { viewModel.closeAgentEditor() }
            AgentEditScreen(viewModel)
        }
        state.openAgent != null -> {
            BackHandler { viewModel.closeAgent() }
            AgentThreadScreen(viewModel)
        }
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(viewModel, onBack = { showSettings = false })
        }
        state.open != null -> {
            BackHandler { viewModel.closeThread() }
            ThreadScreen(viewModel)
        }
        tab == ConversationTab.Dial -> DialerScreen(tab = tab, onSelectTab = { tab = it })
        else -> ConversationsScreen(
            viewModel,
            tab = tab,
            listState = when (tab) {
                ConversationTab.Favorites -> favoritesScroll
                ConversationTab.Known -> knownScroll
                ConversationTab.Unknown -> unknownScroll
                // Unreachable — the branch above catches Dial before this runs — but the
                // compiler wants every entry and an exception here would be a crash waiting for
                // whoever adds a fifth tab.
                ConversationTab.Dial -> dialScroll
            },
            onSelectTab = { tab = it },
            onOpenSettings = { showSettings = true },
            onNewMessage = { viewModel.startNewMessage() },
            playTitleHint = !titleHintPlayed,
            onTitleHintPlayed = { titleHintPlayed = true },
        )
    }
}
