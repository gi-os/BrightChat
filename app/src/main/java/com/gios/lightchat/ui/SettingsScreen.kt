package com.gios.lightchat.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.CallAnnounce
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Delivery
import com.gios.lightchat.Dialer
import com.gios.lightchat.AlertOwner
import com.gios.lightchat.api.Store
import com.gios.lightchat.api.KlipyApi
import com.gios.lightchat.api.parseApiKeyQr
import com.gios.lightchat.api.parseWhisperQr
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatDimens
import com.gios.lightchat.ui.theme.ChatType

/**
 * Everything this app can be told, on one page.
 *
 * ### Why it was rebuilt
 *
 * The report was that it is hard to get at everything, and it was true twice over.
 *
 * The page was a plain [Column] with a `weight(1f)` spacer at each end. That centres a short page
 * nicely and *clips* a long one: as settings were added — unknown senders, on-screen alerts,
 * announce calls, transcription — everything past the bottom of the panel became unreachable, Sign
 * out included. There was no scroll to find, so there was no sign anything was missing. That is the
 * bug; the sections below are the readable part.
 *
 * The second half is that things which are settings did not live here. Newsletters — named
 * recipient batches, the closest thing this app has to a mailing list — were reachable only by
 * starting a new message and finding a line at the foot of the recipient picker. Nobody would look
 * there for it, so it is linked from here as well. (The dialer's speed-dial slots stay on the Dial
 * tab, where the keypad they belong to is.)
 *
 * ### Shape
 *
 * One scrolling page with named sections rather than a menu of sub-screens. On a panel this size a
 * tree costs a tap and a redraw per level, and the whole list is only a few turns of the wheel —
 * which scrolls it, since a thumb on this screen covers the line it is trying to read.
 */
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    // Scanning is a full-screen sub-mode, exactly as in the agent editor: the camera takes the
    // whole panel and the page comes back with the field filled in.
    var scanning by rememberSaveable { mutableStateOf(false) }
    // Which field the camera is being opened for. Two things on this page are an API key now, and
    // a scan aimed at one must not be able to overwrite the other — a Whisper code carries a URL
    // and a model as well, and applying that to the GIF key would repoint transcription.
    var scanFor by rememberSaveable { mutableStateOf(ScanTarget.Whisper) }
    var scanNote by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped by a successful scan, to re-read what the scan just wrote into the store.
    var scanned by remember { mutableStateOf(0) }

    if (scanning) {
        QrScanScreen(
            onResult = { text ->
                if (scanFor == ScanTarget.Gif) {
                    val key = parseApiKeyQr(text)
                    scanNote = if (key == null) {
                        "That QR code doesn’t look like a key."
                    } else {
                        Store.setKlipyKey(context, key)
                        scanned++
                        "Scanned: GIF key"
                    }
                    scanning = false
                    return@QrScanScreen
                }
                val config = parseWhisperQr(text)
                scanNote = when {
                    config == null -> "That QR code doesn’t look like a key."
                    else -> {
                        config.url?.let { Store.setWhisperUrl(context, it) }
                        config.key?.let { Store.setWhisperKey(context, it) }
                        config.model?.let { Store.setWhisperModel(context, it) }
                        viewModel.transcriptionChanged()
                        scanned++
                        // Says which parts landed, because a code carrying only a key looks
                        // identical to one carrying nothing until the next transcription fails.
                        listOfNotNull(
                            config.url?.let { "server" },
                            config.key?.let { "key" },
                            config.model?.let { "model" },
                        ).joinToString(", ").let { "Scanned: $it" }
                    }
                }
                scanning = false
            },
            onClose = { scanning = false },
        )
        return
    }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ChatDimens.screenPadding)
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        // ---------------------------------------------------------------------------- server
        SectionHeader("Server")
        ServerUrl(viewModel)

        // -------------------------------------------------------------------------- messages
        SectionHeader("Messages")

        // Off by default: an old iMessage account gets a steady trickle from short codes,
        // delivery notices and two-factor senders, and on this phone every one of them buzzes,
        // wakes the panel and puts a box in front of what you were doing. Filtered messages still
        // arrive and still carry an unread mark — they just don't interrupt.
        var notifyUnknown by remember { mutableStateOf(Store.notifyUnknown(context)) }
        Toggle(
            label = if (notifyUnknown) "Unknown senders: notify" else "Unknown senders: silent",
            hint = if (notifyUnknown) {
                "Anyone can buzz this phone."
            } else {
                "Only your contacts and named groups buzz. The rest still arrive, quietly."
            },
            onClick = {
                notifyUnknown = !notifyUnknown
                Store.setNotifyUnknown(context, notifyUnknown)
            },
        )

        // On by default. Off keeps the buzz and the shade notification but never puts
        // the box over the screen (or wakes it) — for people who find a lit-up panel
        // worse than waiting to check.
        //
        // A third state, and it is not this app's to set: BrightControl draws the box for every
        // app on the phone now, and when it does, this one stands down. Saying "on" while nothing
        // appeared would be a toggle that lies — and the setting really is still on, which is why
        // it is said out loud here rather than quietly flipped.
        var headsUpBox by remember { mutableStateOf(Store.headsUpBox(context)) }
        val ownedElsewhere = AlertOwner.ownedElsewhere(context)
        Toggle(
            label = when {
                ownedElsewhere -> "On-screen alerts: BrightControl"
                headsUpBox -> "On-screen alerts: on"
                else -> "On-screen alerts: off"
            },
            hint = when {
                ownedElsewhere ->
                    "BrightControl puts the box up for every app now, so this one stands aside. " +
                        "Turn banners off there to bring this one back."
                headsUpBox -> "New messages put a box over whatever the phone is showing."
                else -> "Just the buzz and a notification. Nothing appears over the screen."
            },
            onClick = {
                headsUpBox = !headsUpBox
                Store.setHeadsUpBox(context, headsUpBox)
            },
        )

        // Stays on this screen rather than going back, so the outcome can actually be
        // read — including the case where the Private API is off and the Mac still shows
        // everything unread.
        var readResult by remember { mutableStateOf<String?>(null) }
        Action(label = "Mark all as read", hint = readResult) {
            readResult = viewModel.markAllRead()
        }

        Action(
            label = "Refresh conversations",
            hint = "Re-pulls the list from the Mac.",
        ) {
            viewModel.refresh()
            onBack()
        }

        // ---------------------------------------------------------------------------- panel
        SectionHeader("Panel")

        // On by default: nothing on this screen has a button border, and the tick is how a tap
        // says it landed. Off covers every tap, long press and menu pick in the app in one go
        // (see Haptics) — but not the buzz a new message makes, which is an alert rather than
        // feedback and belongs to the phone's notification settings.
        var haptics by remember { mutableStateOf(Haptics.enabled(context)) }
        Toggle(
            label = if (haptics) "Buzz on tap: on" else "Buzz on tap: off",
            hint = if (haptics) {
                "Every tap gives a short tick."
            } else {
                "Nothing you tap buzzes. New messages still do."
            },
            onClick = {
                haptics = !haptics
                Haptics.set(context, haptics)
            },
        )

        // ----------------------------------------------------------------------------- calls
        // Only on a phone that can place a call at all.
        if (Dialer.available(context)) {
            SectionHeader("Calls")
            AnnounceCalls()
        }

        // ----------------------------------------------------------------------- newsletters
        //
        // Linked from here because the only other way in is a line at the foot of the new-message
        // recipient picker, which is not a place anyone looks for a saved list of people.
        SectionHeader("Newsletters")
        Action(
            label = "Newsletters",
            hint = when (val count = state.newsletters.size) {
                0 -> "Save a group of people to write to them all at once."
                1 -> "1 batch."
                else -> "$count batches."
            },
            onClick = { viewModel.openNewsletters() },
        )

        // ---------------------------------------------------------------------------- agents
        SectionHeader("Agents")
        state.agents.forEach { agent ->
            HapticText(
                text = agent.name,
                style = ChatType.body,
                color = ChatColors.onSurface,
                onClick = { viewModel.openEditAgent(agent) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        Action(
            label = "Add agent",
            hint = if (state.agents.isEmpty()) "An OpenAI-compatible model, as a conversation." else null,
            onClick = { viewModel.openNewAgent() },
        )

        // --------------------------------------------------------------------- transcription
        SectionHeader("Transcription")
        Whisper(
            viewModel = viewModel,
            reload = scanned,
            note = scanNote.takeIf { scanFor == ScanTarget.Whisper },
            onScan = { scanNote = null; scanFor = ScanTarget.Whisper; scanning = true },
        )

        // ------------------------------------------------------------------------------ gifs
        SectionHeader("GIFs")
        GifKey(
            reload = scanned,
            note = scanNote.takeIf { scanFor == ScanTarget.Gif },
            onScan = { scanNote = null; scanFor = ScanTarget.Gif; scanning = true },
        )

        // -------------------------------------------------------------------------- delivery
        SectionHeader("Delivery")
        DeliveryHealth()

        // --------------------------------------------------------------------------- account
        SectionHeader("Account")
        Action(label = "Sign out", hint = null) { viewModel.signOut() }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

/**
 * The server URL, editable in place.
 *
 * The scheme is hidden when it is shown and kept when it is edited: the panel is narrow, and
 * `https://` is the half of the line nobody needs to read.
 */
@Composable
private fun ServerUrl(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val currentUrl = Store.baseUrl(context).orEmpty()
    var editing by remember { mutableStateOf(false) }
    var draftUrl by remember { mutableStateOf(currentUrl) }

    if (editing) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = draftUrl,
                onValueChange = { draftUrl = it },
                singleLine = true,
                textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(ChatColors.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (draftUrl.isNotBlank()) viewModel.updateServerUrl(draftUrl)
                    editing = false
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        }
    } else {
        HapticText(
            text = currentUrl.removePrefix("https://").removePrefix("http://").ifEmpty { "Tap to set" },
            style = ChatType.body,
            color = ChatColors.onSurface,
            textAlign = TextAlign.Center,
            onClick = {
                draftUrl = currentUrl
                editing = true
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Announce calls, and the number the announcement names.
 *
 * When on, placing a call also iMessages the callee which number is ringing them — the SIM's
 * number, not the iMessage one — so the call-back comes to this phone.
 */
@Composable
private fun AnnounceCalls() {
    val context = LocalContext.current
    var callAnnounce by remember { mutableStateOf(Store.callAnnounce(context)) }
    // The SIM's own number needs READ_PHONE_NUMBERS; asked for at the moment the toggle goes on,
    // which is the moment the answer starts mattering. A refusal is not a dead end — the number
    // can be typed in below, and the message has a wording for having no number at all.
    var ownNumber by remember { mutableStateOf(CallAnnounce.ownNumber(context)) }
    val askNumber = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ownNumber = CallAnnounce.ownNumber(context) }

    Toggle(
        label = if (callAnnounce) "Announce calls: on" else "Announce calls: off",
        hint = if (callAnnounce) {
            "Calling someone also texts them: “" + CallAnnounce.messageText(ownNumber) + "”"
        } else {
            "Calls are just calls."
        },
        onClick = {
            callAnnounce = !callAnnounce
            Store.setCallAnnounce(context, callAnnounce)
            if (callAnnounce && ownNumber == null) {
                askNumber.launch(Manifest.permission.READ_PHONE_NUMBERS)
            }
        },
    )

    if (callAnnounce) {
        // The number the text names, editable because plenty of SIMs don't know their own
        // number. Prefilled from the SIM when it does.
        var editingNumber by remember { mutableStateOf(false) }
        var draftNumber by remember { mutableStateOf(Store.myNumber(context) ?: ownNumber.orEmpty()) }
        if (editingNumber) {
            BasicTextField(
                value = draftNumber,
                onValueChange = { draftNumber = it },
                singleLine = true,
                textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(ChatColors.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    Store.setMyNumber(context, draftNumber)
                    ownNumber = CallAnnounce.ownNumber(context)
                    editingNumber = false
                }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        } else {
            HapticText(
                text = ownNumber?.let { "Your number: " + CallAnnounce.prettyUs(it) }
                    ?: "Tap to set your number",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                textAlign = TextAlign.Center,
                onClick = {
                    draftNumber = Store.myNumber(context) ?: ownNumber.orEmpty()
                    editingNumber = true
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}

/**
 * The transcription server.
 *
 * A URL, a key and a model name, because there is nothing to switch on: no model ships in this
 * app, so transcription exists exactly to the extent that this points at a server. Anything
 * answering OpenAI's `/v1/audio/transcriptions` will do — whisper.cpp's own server, a
 * faster-whisper box, LM Studio, or OpenAI itself.
 *
 * [reload] is bumped by a QR scan; it exists so the three fields re-read the store after a scan
 * writes to it, rather than sitting there showing what was there before.
 */
@Composable
private fun Whisper(viewModel: ChatViewModel, reload: Int, note: String?, onScan: () -> Unit) {
    val context = LocalContext.current
    var whisperUrl by remember(reload) { mutableStateOf(Store.whisperUrl(context).orEmpty()) }
    var whisperKey by remember(reload) { mutableStateOf(Store.whisperKey(context)) }
    var whisperModel by remember(reload) { mutableStateOf(Store.whisperModel(context)) }
    // Opened by a scan, so what the scan wrote is visible without hunting for the tap. In an
    // effect and not straight in the body: assigning state while composing is how you get a
    // recomposition that never settles.
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reload) { if (reload > 0) editing = true }

    HapticText(
        text = if (whisperUrl.isBlank()) "Transcription: off" else "Transcription: $whisperModel",
        style = ChatType.body,
        color = if (whisperUrl.isBlank()) ChatColors.onSurfaceDim else ChatColors.onSurface,
        onClick = { editing = !editing },
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(
        if (whisperUrl.isBlank()) {
            "Point this at a Whisper server and a voice memo can be read as well as heard."
        } else {
            whisperUrl
        },
    )

    if (editing) {
        Spacer(modifier = Modifier.height(10.dp))
        WhisperField(
            value = whisperUrl,
            hint = "https://your-server:9000",
            onDone = {
                Store.setWhisperUrl(context, it)
                whisperUrl = Store.whisperUrl(context).orEmpty()
                // The URL is what decides whether dictation exists at all, so the microphone in
                // every composer appears — or goes — on this line.
                viewModel.transcriptionChanged()
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        WhisperField(
            value = whisperKey,
            hint = "API key, if it needs one",
            onDone = {
                Store.setWhisperKey(context, it)
                whisperKey = Store.whisperKey(context)
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        WhisperField(
            value = whisperModel,
            hint = "whisper-1",
            onDone = {
                Store.setWhisperModel(context, it)
                whisperModel = Store.whisperModel(context)
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        // The key is the reason this exists: fifty characters of case-sensitive base62 is a bad
        // thing to type on any phone and a worse one here. `qrencode` over the key on a laptop,
        // or a code carrying the whole setup — see parseWhisperQr.
        HapticText(
            text = "Scan a QR code",
            style = ChatType.body,
            color = ChatColors.onSurface,
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(note ?: "Reads an API key, or a whole server setup, off the screen of another device.")
    }
    Spacer(modifier = Modifier.height(12.dp))
}

/** Which key the QR scanner was opened for. */
private enum class ScanTarget { Whisper, Gif }

/**
 * The GIF search key, and how many GIFs this phone has kept.
 *
 * **The app ships with a key**, so this row normally has nothing to do: GIF search works out of the
 * box. What the field is for is a *personal* key, which matters because the shipped one's allowance
 * is per key rather than per install — every phone running BrightChat draws on the same one, so a
 * busy hour is a busy hour for all of them. Entering one here takes precedence over it.
 *
 * The field shows only what this phone's owner typed, never the built-in key: pre-filling it would
 * be handing the shipped key to anybody who opened this screen, and would then save it back as
 * though they had chosen it.
 *
 * The service is KLIPY, which is what Discord's GIF search runs on since Google switched the Tenor
 * API off in June — see [KlipyApi] for the whole story.
 */
@Composable
private fun GifKey(reload: Int, note: String?, onScan: () -> Unit) {
    val context = LocalContext.current
    var own by remember(reload) { mutableStateOf(Store.ownKlipyKey(context)) }
    val canSearch = remember(reload, own) { Store.canSearchGifs(context) }
    val saved = remember(reload) { Store.savedGifs(context).size }
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reload) { if (reload > 0) editing = true }

    HapticText(
        text = if (canSearch) "GIF search: KLIPY" else "GIF search: off",
        style = ChatType.body,
        color = if (canSearch) ChatColors.onSurface else ChatColors.onSurfaceDim,
        onClick = { editing = !editing },
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(
        when {
            !canSearch -> "A key turns the GIF button in a thread into a search."
            own.isNotBlank() && saved > 0 -> "Your own key. $saved saved, kept on the phone."
            own.isNotBlank() -> "Your own key. Hold a GIF in the picker to save it."
            saved == 0 -> "Shared with every BrightChat. Hold a GIF in the picker to save it."
            saved == 1 -> "Shared with every BrightChat. 1 saved GIF, kept on the phone."
            else -> "Shared with every BrightChat. $saved saved GIFs, kept on the phone."
        },
    )

    if (editing) {
        Spacer(modifier = Modifier.height(10.dp))
        WhisperField(
            value = own,
            hint = "Your own API key",
            onDone = {
                Store.setKlipyKey(context, it)
                own = Store.ownKlipyKey(context)
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        HapticText(
            text = "Scan a QR code",
            style = ChatType.body,
            color = ChatColors.onSurface,
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(
            note ?: "The shared key is enough most of the time. If searching says it is busy, a " +
                "free one from ${KlipyApi.KEY_SOURCE} is yours alone — type it, or scan it off a " +
                "laptop screen.",
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
}

/**
 * Whether the phone is currently letting background delivery happen at all, and when it last did.
 *
 * Both are otherwise unanswerable from the phone: the app can look connected while its poll is
 * being deferred for hours by the standby bucket, and an alarm chain that stopped firing overnight
 * leaves no other trace. Read on each visit rather than remembered — the whole value is that it's
 * current.
 */
@Composable
private fun DeliveryHealth() {
    val context = LocalContext.current
    val health = Delivery.healthLines(context)
    Hint(health.first)
    Hint(health.second)
    if (!Delivery.isExempt(context)) {
        // LightOS ships almost no Settings UI, so this dialog usually doesn't exist — the tap is
        // offered when it resolves and the adb command named otherwise, rather than showing a
        // control that silently does nothing.
        val intent = Delivery.exemptionIntent(context)
        if (intent != null) {
            HapticText(
                text = "Allow background delivery",
                style = ChatType.body,
                color = ChatColors.onSurfaceDim,
                textAlign = TextAlign.Center,
                onClick = { runCatching { context.startActivity(intent) } },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        } else {
            Hint("Fix over adb: dumpsys deviceidle whitelist +" + context.packageName)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

/**
 * A section's name.
 *
 * The page is long enough that it is read by scanning for these, so they carry the space above
 * them: one place decides how far apart the sections sit, rather than a spacer per section that
 * drifts as things are added.
 */
@Composable
private fun SectionHeader(title: String) {
    Spacer(modifier = Modifier.height(30.dp))
    Text(text = title, style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
    Spacer(modifier = Modifier.height(14.dp))
}

/** A setting that flips, with the line underneath saying what the current state means. */
@Composable
private fun Toggle(label: String, hint: String, onClick: () -> Unit) {
    HapticText(
        text = label,
        style = ChatType.body,
        color = ChatColors.onSurfaceDim,
        textAlign = TextAlign.Center,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(hint)
    Spacer(modifier = Modifier.height(18.dp))
}

/** Something that happens when tapped, rather than something that holds a value. */
@Composable
private fun Action(label: String, hint: String?, onClick: () -> Unit) {
    HapticText(
        text = label,
        style = ChatType.body,
        color = ChatColors.onSurfaceDim,
        textAlign = TextAlign.Center,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
    hint?.let { Hint(it) }
    Spacer(modifier = Modifier.height(18.dp))
}

/** The small grey line under a setting. */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = ChatType.hint,
        color = ChatColors.onSurfaceDisabled,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/**
 * One line of the transcription setup.
 *
 * Three near-identical fields rather than one screen, because they are three unrelated strings and
 * a form on this panel is worse than three lines. Each commits on Done, so nothing is half-saved if
 * you leave.
 */
@Composable
private fun WhisperField(value: String, hint: String, onDone: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(ChatColors.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(draft) }),
        decorationBox = { inner ->
            if (draft.isEmpty()) {
                Text(
                    hint,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            inner()
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
