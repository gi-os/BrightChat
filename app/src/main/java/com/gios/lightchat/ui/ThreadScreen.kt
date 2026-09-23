@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.gios.lightchat.ui

import android.content.Context
import android.text.format.DateUtils
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import com.gios.light.common.hw.WheelScroll
import kotlinx.coroutines.launch
import com.gios.lightchat.Dictation
import com.gios.lightchat.api.Store
import com.gios.lightchat.Attachment
import com.gios.lightchat.ChatBackground
import com.gios.lightchat.ChatMessage
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Contacts
import com.gios.lightchat.Conversation
import com.gios.lightchat.SharedFiles
import com.gios.lightchat.Dialer
import com.gios.lightchat.ReactionType
import com.gios.lightchat.URL_REGEX
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

/** One open conversation: messages oldest→newest, user on the right, others left. */
@Composable
fun ThreadScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val convo = state.open ?: return
    val listState = rememberLazyListState()

    // Which message's tapback picker is open (its guid), if any. A long-press or a
    // double-tap opens it; picking a reaction or tapping elsewhere closes it. Only when
    // the server's Private API is live — otherwise reacting can't be sent, so we don't
    // offer it.
    var reactingTo by remember { mutableStateOf<String?>(null) }

    // The message the next send replies to (chosen from the long-press menu),
    // shown as a banner above the compose bar until sent or cancelled.
    var replyingTo by remember(convo.guid) { mutableStateOf<ChatMessage?>(null) }
    // The sent message whose words are in the compose field being changed. Reply and edit
    // are exclusive: starting one clears the other, because the field can only mean one thing.
    var editing by remember(convo.guid) { mutableStateOf<ChatMessage?>(null) }

    // The contact page (tap the title): people, the note, the photos and the links, and —
    // for a group — rename / add / remove / leave. Every conversation has one now: a 1:1
    // had nothing to *manage*, but it has as much to look back at as a group does, and the
    // note only makes sense per person.
    var showDetails by remember(convo.guid) { mutableStateOf(false) }
    if (showDetails) {
        BackHandler { showDetails = false }
        ChatDetailsScreen(viewModel, onBack = { showDetails = false })
        return
    }

    // Full-screen image viewer (tap an inline image). Drawn as an opaque overlay
    // on top of the thread — NOT the details screen's early-return pattern — so the
    // LazyColumn (and its scroll position) never leaves composition: dismissing
    // lands exactly where you were, and the thread is already rendered underneath,
    // which is also what hides the delayed grayscale restore (see ColorMode).
    var viewingImage by remember(convo.guid) { mutableStateOf<Attachment?>(null) }
    // The downloaded sound being listened to, with the name it arrived under. A File rather than
    // the Attachment for the same reason the video is: by the time it plays, the bytes are local.
    var listeningTo by remember(convo.guid) { mutableStateOf<Listening?>(null) }
    var transcript by remember(convo.guid) { mutableStateOf<String?>(null) }
    // The downloaded video being watched, if any. A File rather than the Attachment, because by
    // the time this is set the fetch has already happened and the player only wants the bytes.
    var viewingVideo by remember(convo.guid) { mutableStateOf<java.io.File?>(null) }

    val context = LocalContext.current

    // Tap to start, tap again to stop and transcribe. See [rememberDictation] — it lives there
    // rather than here because a new message and an agent thread are both places you type, and
    // neither of them had this, which was most of why it could not be found.
    val dictate = rememberDictation(viewModel)
    val ring = rememberCaller()

    /**
     * **Opening a chat should not open the keyboard.**
     *
     * The manifest now says `stateAlwaysHidden`, which is the declarative half and covers the
     * window appearing. This is the other half: the compose field is the only focusable thing on
     * this screen, so anything that hands focus around — coming back from the dialer, a
     * notification deep link swapping the open thread under a composed screen — can land on it,
     * and focus on a text field is what summons the IME. Reading a thread is the common case and
     * typing is the deliberate one; a keyboard covering half the messages you came to read gets
     * that backwards.
     */
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(convo.guid) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    /**
     * Whether the header's Call is armed — the second-tap confirm, the same shape as Remove on
     * the contact page and for a stronger reason. `ACTION_CALL` rings from the tap with nothing
     * in between, and the header sits directly above a scrolling thread: a thumb that overshoots
     * the top of the list lands on it. One tap now asks, the next one calls.
     */
    var confirmingCall by remember(convo.guid) { mutableStateOf(false) }
    // Disarms itself. See CALL_CONFIRM_MS — nothing else on the header can disarm it, so
    // without this an armed confirm outlives the intention behind it.
    LaunchedEffect(confirmingCall) {
        if (!confirmingCall) return@LaunchedEffect
        kotlinx.coroutines.delay(CALL_CONFIRM_MS)
        confirmingCall = false
    }
    /**
     * The number the header's Call rings, or null for no Call at all.
     *
     * Three conditions, and each removes a way for the verb to lie. **One participant**,
     * because a group has no default person to ring and choosing one for the user is worse
     * than not offering it — that choice belongs on the contact page, where every member is
     * listed. **Callable**, because an iMessage handle is as often an Apple ID as a number
     * (see [Dialer.callable]). **A dialer present**, asked once here rather than discovered
     * by a launch that goes nowhere, which is the same bargain the note row makes with
     * LightNotebook.
     *
     * Keyed on the participants rather than computed per recomposition: the availability
     * check is a PackageManager IPC, and the header recomposes on every arriving message.
     */
    val callNumber = remember(convo.participants, convo.isGroup) {
        convo.participants
            .singleOrNull()
            ?.takeIf { !convo.isGroup && Dialer.callable(it) && Dialer.available(context) }
    }

    // Our own picker, not the system one: MediaStore is never current on LightOS, so
    // the system picker doesn't offer photos you just took. Drawn as an overlay for
    // the same reason as the image viewer — the thread stays composed underneath, so
    // closing it lands exactly where you were.
    // Saveable: handing off to a third-party camera is a realistic process-death
    // window on this phone, and coming back to a closed picker would orphan the photo.
    var picking by rememberSaveable(convo.guid) { mutableStateOf(false) }

    // The GIF picker, over the thread the same way. Not saveable, unlike the photo picker: there
    // is no hand-off to another app to be killed behind, and what it holds — a search, a page, an
    // armed GIF — is worth less than a re-open costs.
    var pickingGif by remember(convo.guid) { mutableStateOf(false) }

    // The wheel walks the thread. `reverse`, because the list is reverse-laid-out: the
    // scroll axis is reversed with it, so an unflipped notch up would head off towards
    // last month while the page appeared to fall downwards. Both overlays below stay
    // composed on top of this list, so the notch has to be handed to them instead —
    // otherwise the thread scrolls under a photo you're looking at.
    WheelScroll(listState, active = viewingImage == null && !picking && !pickingGif, reverse = true)

    // Which messages begin a same-speaker run (so only they get a name label).
    val labeled = remember(state.messages) {
        buildSet {
            state.messages.forEachIndexed { i, m ->
                val prev = state.messages.getOrNull(i - 1)
                if (prev == null || prev.fromMe != m.fromMe || prev.sender != m.sender) add(m.guid)
            }
        }
    }

    // The one message that shows a delivery receipt ("Delivered" / "Read 3:14 PM"):
    // your newest sent message, like iMessage — and only in a 1:1, where receipts
    // actually mean something (a group has no single read state).
    val receiptGuid = if (convo.isGroup) null else state.messages.lastOrNull { it.fromMe }?.guid

    // Guid → message, so a reply row can quote the message it points back at.
    val byGuid = remember(state.messages) { state.messages.associateBy { it.guid } }

    // The list is reverse-laid-out (newest pinned to the bottom), so opening a
    // thread shows the latest immediately — no scroll to watch.
    val newest = state.messages.lastOrNull()
    LaunchedEffect(newest?.guid) {
        val message = newest ?: return@LaunchedEffect
        // Your own send always scrolls, wherever you were: you pressed send, so you should
        // see it land. Someone else's message only nudges you if you were already at the
        // bottom — being yanked out of history you were reading is the other failure.
        if (message.fromMe || listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // The chat's background, if one has been set (see ChatBackground): the finished,
    // filtered image, drawn edge to edge behind the thread. Keyed on the version so
    // saving an edit on the details page shows up here without reopening the chat.
    // Null — the common case — costs one file-existence check and draws nothing.
    val bgVersion = ChatBackground.version.intValue
    val configuration = LocalConfiguration.current
    val background by produceState<ImageBitmap?>(null, convo.guid, bgVersion) {
        value = ChatBackground.load(
            context,
            convo.guid,
            configuration.screenWidthDp.toFloat() / configuration.screenHeightDp,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        background?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
            ScreenHeader(
                title = state.contacts.title(convo),
                onBack = viewModel::closeThread,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                // The contact page lives behind the title, for a group and a 1:1 alike.
                onTitleClick = { showDetails = true },
                // **Call, and only for a one-to-one with a number.**
                //
                // A group has no default person to ring — the choice of who is the whole
                // question, and the contact page is where it can be asked, so the header
                // stays out of it rather than picking somebody. A 1:1 over an Apple ID has
                // nobody to ring at all. Either way the slot falls back to the spacer that
                // keeps the title centred.
                trailing = if (callNumber != null) {
                    {
                        HapticIcon(
                            icon = PhoneIcon,
                            // Icon-only, but the accessibility label stays the plain verb — the
                            // visible glyph never carries the armed "Call?" question, only the tint
                            // does (see below), so a screen reader is never asked to read a "?".
                            contentDescription = "Call",
                            // Brightened once armed, exactly as Remove? is: on a greyscale panel
                            // the weight is what makes the confirm step noticeable.
                            tint = if (confirmingCall) ChatColors.onSurface else ChatColors.onSurfaceDim,
                            onClick = {
                                if (confirmingCall) {
                                    confirmingCall = false
                                    ring(callNumber)
                                } else {
                                    confirmingCall = true
                                }
                            },
                        )
                    }
                } else {
                    null
                },
            )

            if (state.messages.isEmpty() && state.threadLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text = "Loading…", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
                }
            } else {
                /**
                 * **Scrolling to the top of the conversation fetches the page under it.**
                 *
                 * The thread holds only what has been looked at — a chat is not downloaded
                 * in full just because it exists — so reaching the oldest message held is
                 * the moment to ask for more. `reverseLayout` means "up" is toward the *end*
                 * of the list, so it's the last visible index that matters, not the first.
                 *
                 * `derivedStateOf` so this recomputes on scroll without recomposing the
                 * whole thread on every frame of it, and the effect is keyed on the flag
                 * rather than on the scroll position so one crossing fires one fetch.
                 */
                val nearOldest by remember(convo.guid) {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        val total = state.messages.size
                        last != null && total > 0 && last >= total - OLDER_TRIGGER_DISTANCE
                    }
                }
                LaunchedEffect(nearOldest, convo.guid) {
                    if (nearOldest) viewModel.loadOlder()
                }
                /**
                 * **Slide the thread left to peek at every message's time.**
                 *
                 * Times aren't drawn by default — a column of timestamps is exactly the
                 * clutter this app exists to not have — but "when did this arrive" is a
                 * fair question, so iMessage's answer: drag left, the turns shift over,
                 * and each row's time slides in from the right edge; let go and it all
                 * springs back. Held as an [Animatable] and read only inside
                 * graphicsLayer blocks, so a drag moves every visible row without
                 * recomposing a single one.
                 */
                val timeReveal = remember(convo.guid) { Animatable(0f) }
                val maxRevealPx = with(LocalDensity.current) { TIME_REVEAL_WIDTH.toPx() }
                val revealScope = rememberCoroutineScope()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(convo.guid) {
                            detectHorizontalDragGestures(
                                onDragEnd = { revealScope.launch { timeReveal.animateTo(0f) } },
                                onDragCancel = { revealScope.launch { timeReveal.animateTo(0f) } },
                            ) { change, dragAmount ->
                                // Leftward drag (negative) opens; the clamp keeps a rightward
                                // one from pushing the thread off the other side.
                                val target = (timeReveal.value - dragAmount).coerceIn(0f, maxRevealPx)
                                if (target != timeReveal.value) {
                                    change.consume()
                                    revealScope.launch { timeReveal.snapTo(target) }
                                }
                            }
                        },
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Newest first so reverseLayout pins it to the bottom.
                    items(state.messages.asReversed(), key = { it.guid }) { message ->
                        MessageRow(
                            message,
                            convo,
                            state.contacts,
                            reveal = { timeReveal.value },
                            maxRevealPx = maxRevealPx,
                            showLabel = message.guid in labeled,
                            showReceipt = message.guid == receiptGuid,
                            // The quoted original when this message is an inline reply.
                            replyQuote = message.threadOriginatorGuid?.let { g ->
                                byGuid[g]?.shortDescription ?: "an earlier message"
                            },
                            loadImage = viewModel::loadImage,
                            // The undecoded file, for a GIF — see AttachmentImage.
                            loadFile = viewModel::loadImageFile,
                            onImageTap = { viewingImage = it },
                            // A video is downloaded and played in the app; everything else keeps
                            // the hand-off, which is right for a PDF or a vCard and only wrong
                            // for the one kind of file this phone has no viewer for.
                            onOpenAttachment = { attachment ->
                                if (attachment.isAudio) {
                                    // Downloaded and played here, for the same reason a video is:
                                    // there is no audio player installed on this phone, so handing
                                    // a voice memo to ACTION_VIEW ends in "No app can open this
                                    // file" after a download you have already waited for.
                                    viewModel.downloadAttachment(attachment) {
                                        listeningTo = Listening(
                                            file = it,
                                            name = attachment.transferName,
                                            attachment = attachment,
                                        )
                                    }
                                } else if (attachment.isVideo) {
                                    viewModel.downloadAttachment(attachment) { viewingVideo = it }
                                } else {
                                    viewModel.openAttachment(attachment)
                                }
                            },
                            onSaveAttachment = { viewModel.saveAttachment(it) },
                            // What this chat's backend allows, not whether the Mac's helper is up: a
                            // WhatsApp chat takes tapbacks either way. See backend/ChatBackend.
                            canReact = viewModel.caps(convo).reactions,
                            pickerOpen = reactingTo == message.guid,
                            onLongPress = { if (viewModel.caps(convo).reactions) reactingTo = message.guid },
                            onReact = { type ->
                                viewModel.sendReaction(message, type)
                                reactingTo = null
                            },
                            onReply = {
                                replyingTo = message
                                editing = null
                                reactingTo = null
                            },
                            // Only offered on a message this account sent, inside iMessage's
                            // fifteen-minute window, with words in it. Null hides the label.
                            onEdit = if (viewModel.canEdit(message)) {
                                {
                                    editing = message
                                    replyingTo = null
                                    reactingTo = null
                                }
                            } else {
                                null
                            },
                            onDismissPicker = { reactingTo = null },
                        )
                    }
                    // Drawn last so reverseLayout puts it above the oldest message — where
                    // the eye already is when this fires.
                    if (state.loadingOlder) {
                        item(key = "older-loading") {
                            Text(
                                text = "…",
                                style = ChatType.hint,
                                color = ChatColors.onSurfaceDisabled,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            state.message?.let { msg ->
                Text(
                    text = msg,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }

            if (state.typingChatGuid == convo.guid) {
                TypingIndicator()
            }

            // Reply banner: what the next send will reply to, with a cancel ×.
            replyingTo?.let { target ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Replying to ${target.shortDescription}",
                        style = ChatType.hint,
                        color = ChatColors.onSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    HapticText(
                        text = "×",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDim,
                        onClick = { replyingTo = null },
                    )
                }
            }

            // Edit banner: which message the field is rewriting, with a cancel ×. Same shape
            // as the reply banner above so the two read as one family.
            editing?.let { target ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Editing “${target.shortDescription}”",
                        style = ChatType.hint,
                        color = ChatColors.onSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    HapticText(
                        text = "×",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDim,
                        onClick = { editing = null },
                    )
                }
            }

            ComposeBar(
                onSend = { text ->
                    val target = editing
                    if (target != null) {
                        viewModel.editMessage(target, text)
                        editing = null
                    } else {
                        viewModel.sendMessage(text, replyingTo?.guid)
                        replyingTo = null
                    }
                },
                // The message's words go into the field when an edit starts, and the field is
                // emptied when it is cancelled — keyed on the guid so a second Edit on the same
                // message after a cancel refills it.
                prefill = editing?.let { it.guid to (it.bodyText ?: "") },
                sendLabel = if (editing != null) "Save" else "Send",
                onPickImage = { picking = true },
                onPickGif = { pickingGif = true },
                // A GIF or a photograph handed over by the keyboard, or pasted, goes the same way
                // a share does — already copied out of somebody else's URI by the time it gets here.
                onReceiveMedia = { files -> viewModel.sendReceivedFiles(files) },
                onTextChange = viewModel::onComposeTextChanged,
                // Offered only when there is a server to transcribe against and a microphone we are
                // allowed to open. A key that cannot work is worse than no key.
                onDictate = dictate.onTap.takeIf { dictate.available },
                dictating = dictate.listening,
            )
        }

        // The open image covers everything (opaque, gesture-consuming); the
        // thread stays composed — and visible again the instant this leaves.
        viewingImage?.let { image ->
            ImageViewerScreen(
                image,
                viewModel::loadImage,
                onClose = { viewingImage = null },
                loadFile = viewModel::loadImageFile,
                onSave = { viewModel.saveAttachment(image) },
                // The viewer covers the thread's own status line, so the answer to a hold made in
                // here has to be drawn in here.
                status = state.message,
            )
        }

        // A video plays here rather than being handed to an app that isn't installed. Same
        // overlay pattern as the image viewer: the thread stays composed underneath, so closing
        // lands where you were.
        listeningTo?.let { listening ->
            // Any transcript already fetched for this attachment is shown without asking again;
            // transcription is slow and, against a paid endpoint, billed.
            val cached = transcript
                ?: com.gios.lightchat.api.Store.transcript(context, listening.attachment.guid)
            AudioPlayerScreen(
                file = listening.file,
                name = listening.name,
                transcript = cached,
                canTranscribe = com.gios.lightchat.api.Store.canTranscribe(context),
                onTranscribe = {
                    viewModel.transcribe(listening.attachment, listening.file) { transcript = it }
                },
                onClose = {
                    listeningTo = null
                    transcript = null
                },
            )
        }

        viewingVideo?.let { file ->
            VideoPlayerScreen(file, onClose = { viewingVideo = null })
        }

        if (picking) {
            BackHandler { picking = false }
            // Surface, not a Box with a background: background() only paints. Material3's
            // Surface installs the pointerInput that stops taps falling through to the
            // thread underneath — without it the picker's centred "Photos" label sits on
            // top of the header's clickable title and opens the chat details behind it.
            Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
                PhotoPickerScreen(
                    onSend = { files ->
                        picking = false
                        viewModel.sendImageFiles(files)
                    },
                    onClose = { picking = false },
                    // The one place a clip can go: a single open thread, one upload.
                    allowVideo = true,
                )
            }
        }

        if (pickingGif) {
            BackHandler { pickingGif = false }
            // Surface for the same reason the photo picker is one: it paints *and* consumes
            // touches, so the picker's own header can't be tapped through into the thread's.
            Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
                GifPickerScreen(
                    onSend = { gif ->
                        pickingGif = false
                        viewModel.sendGif(gif)
                    },
                    onClose = { pickingGif = false },
                )
            }
        }
    }
}

/** A left-aligned animated ellipsis shown while the other party is typing — the
 *  bubble-less equivalent of iMessage's "…" indicator, just above the compose bar. */
@Composable
private fun TypingIndicator() {
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            dots = (dots % 3) + 1
        }
    }
    Text(
        // "•" is centred mid-line (unlike a baseline ".") and is in Public Sans, so
        // it reads as typing dots with no font fallback.
        text = "•".repeat(dots),
        style = ChatType.body,
        color = ChatColors.onSurfaceDim,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        textAlign = TextAlign.Start,
    )
}

/** Bottom compose row: a growing text field and a Send action. Shared with the
 *  new-message screen. When [onPickImage] is supplied (the thread, not a brand-new
 *  chat) a leading "+" opens the photo picker, and [onPickGif] adds GIF beside it. */
@Composable
fun ComposeBar(
    onSend: (String) -> Unit,
    onPickImage: (() -> Unit)? = null,
    /**
     * Opens the GIF picker. Null on the new-message screen and in the newsletter composer, which
     * is the same line the photo picker draws: the thread is where a GIF is a reply to something,
     * and a broadcast of one to forty people is not a thing this app should make easy.
     *
     * The word rather than a drawn glyph, because "GIF" *is* the icon everywhere else and Public
     * Sans has all three letters — unlike the star and the heart, which had to be drawn.
     */
    onPickGif: (() -> Unit)? = null,
    onTextChange: ((String) -> Unit)? = null,
    /**
     * An image arriving through the field itself: a keyboard's GIF or sticker, a paste, a drag.
     *
     * Null on the screens where there is nowhere to send one. Supplying it is also what tells the
     * keyboard that images are welcome here — Compose puts the declared types on the EditorInfo
     * from the content receiver, and a keyboard whose field declares nothing will not offer an
     * image at all.
     *
     * Files rather than URIs, because the copy has to happen while the grant is still alive — see
     * [mediaReceiver].
     */
    onReceiveMedia: ((List<java.io.File>) -> Unit)? = null,
    showTopDivider: Boolean = true,
    /**
     * Tap to speak, tap again and the words arrive.
     *
     * Null when there is no transcription server configured, in which case no microphone is drawn —
     * the caller decides that from [DictationControl.available], which is where the reasoning is.
     */
    onDictate: ((onWords: (String) -> Unit) -> Unit)? = null,
    dictating: Boolean = false,
    /**
     * Words to put in the field, keyed: `(key, text)`. A new key replaces the field's contents
     * with the text and puts the cursor at the end; the key going back to null clears the
     * field. For editing a sent message — the field has to open holding the words being
     * changed, and a cancelled edit must not leave them behind as a draft of a new message.
     */
    prefill: Pair<String, String>? = null,
    /** What the action on the right says. "Send", or "Save" while editing. */
    sendLabel: String = "Send",
) {
    // A TextFieldState rather than a String, because `Modifier.contentReceiver` only works on the
    // state-based BasicTextField — and receiving an image is the whole reason this field exists on
    // a messaging app. Everything else about it is unchanged.
    val state = rememberTextFieldState()
    LaunchedEffect(prefill?.first) {
        if (prefill != null) state.setTextAndPlaceCursorAtEnd(prefill.second) else state.clearText()
    }
    val context = LocalContext.current
    // Only blankness is read in composition, not the text itself. Reading the text would recompose
    // the whole bar — and rebuild the content receiver — on every keystroke, which is the cost the
    // state-based field exists to avoid; the placeholder and the Send colour want no more than this.
    val blank by remember(state) { derivedStateOf { state.text.isBlank() } }
    // Held in a state so the effect below, which never restarts, cannot capture a stale one.
    val notifyTextChange by rememberUpdatedState(onTextChange)
    LaunchedEffect(state) {
        // drop(1): snapshotFlow emits the current value the moment it is collected, so without it
        // opening a thread with a draft still in the field reports typing before a key is touched.
        snapshotFlow { state.text.toString() }.drop(1).collect { notifyTextChange?.invoke(it) }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Suppressed when the caller already draws a divider right above us (the
        // new-message screen's "To" line) — otherwise it reads as a double line.
        if (showTopDivider) HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (onPickImage != null) {
                HapticText(
                    text = "+",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    onClick = onPickImage,
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            if (onPickGif != null) {
                HapticText(
                    text = "GIF",
                    // The same style as the "+" beside it, not the smaller `hint` this started
                    // as. The row is `Alignment.Bottom`, so two different text sizes line their
                    // *boxes* up rather than their baselines — and the smaller one's baseline
                    // sits inside a shorter box, which is why GIF looked like it had slipped
                    // down. Matching the style is what puts them on one line; nudging it with
                    // padding would have been a number that only looked right at one font scale.
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    onClick = onPickGif,
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Box(modifier = Modifier.weight(1f)) {
                if (blank) {
                    Text(text = "Message", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
                }
                BasicTextField(
                    state = state,
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onReceiveMedia == null) {
                                Modifier
                            } else {
                                Modifier.contentReceiver(mediaReceiver(context, onReceiveMedia))
                            },
                        ),
                )
            }
            if (onDictate != null) {
                Spacer(modifier = Modifier.width(16.dp))
                // A drawn microphone — see [MicKey] for why it is neither a glyph nor an emoji.
                //
                // The words are appended to whatever is already typed rather than replacing it, so a
                // sentence can be half typed and half spoken — and so a mis-heard dictation does not
                // throw away the part that was right.
                MicKey(
                    listening = dictating,
                    onClick = {
                        onDictate { words ->
                            val now = state.text.toString()
                            state.setTextAndPlaceCursorAtEnd(
                                if (now.isBlank()) words else "${now.trimEnd()} $words",
                            )
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            HapticText(
                text = sendLabel,
                style = ChatType.body,
                color = if (blank) ChatColors.onSurfaceDisabled else ChatColors.onSurface,
                onClick = {
                    val text = state.text.toString()
                    if (text.isNotBlank()) {
                        onSend(text)
                        state.clearText()
                    }
                },
            )
        }
    }
}

/**
 * Takes the pictures out of something dropped, pasted, or handed over by a keyboard.
 *
 * `consume` returns what is *left* — a receiver hands back whatever it did not deal with, so the
 * platform can pass it on. Items with a URI are taken and everything else is left alone, which is
 * what lets a paste of mixed content still put its text in the field.
 *
 * ## The copy happens here, on this thread, on purpose
 *
 * The URI belongs to whoever sent it and the grant behind it lasts exactly as long as this call. A
 * keyboard's `commitContent` grant is scoped to the `InputContentInfo` and Compose releases it the
 * moment the listener returns; a drag-and-drop grant ends with the drop. Handing the URI to a
 * coroutine and reading it later works for a clipboard paste — where the grant happens to last the
 * session — and fails for the keyboard GIF this feature exists for, with nothing to show but
 * "Couldn't read that".
 *
 * So the bytes are copied before returning. It is a blocking read on the UI thread, which is
 * ordinarily the wrong thing: it is a few megabytes at most, once, in response to a deliberate
 * action, and the alternative is a feature that does not work.
 *
 * ## Only what this app can actually send
 *
 * A clip is taken only when it says it holds an image or a video. The send path names a file's type
 * from its extension and falls back to `image/jpeg`, so a consumed PDF or a `content://` text URI —
 * which Gmail and Docs both put on the clipboard — would arrive at the other end as a photograph
 * that will not open. Left unconsumed, it goes wherever it would have gone before.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun mediaReceiver(
    context: android.content.Context,
    onReceive: (List<java.io.File>) -> Unit,
) = ReceiveContentListener { content ->
    val description = content.clipMetadata.clipDescription
    val sendable = (0 until description.mimeTypeCount).any {
        val type = description.getMimeType(it)
        type.startsWith("image/") || type.startsWith("video/")
    }
    if (!sendable) return@ReceiveContentListener content

    val files = mutableListOf<java.io.File>()
    val remaining = content.consume { item ->
        val uri = item.uri
        if (uri == null) {
            false
        } else {
            // Consumed only if the bytes were actually got. A clip taken and then failed on is a
            // paste that vanished.
            SharedFiles.copyIntoCache(context, uri)?.let { files.add(it) } != null
        }
    }
    // One send for the lot, not one per file: iMessage has no batch send, and racing them lands
    // them out of order in the other person's thread.
    if (files.isNotEmpty()) onReceive(files)
    remaining
}

/** Fraction of width a single turn may span. No bubbles signal who's talking, so
 *  capping the width leaves an empty gutter on the opposite side as the cue. */
private const val MESSAGE_MAX_WIDTH = 0.8f

/** How far the time-peek slides the thread — enough for "12:44 PM" in the hint
 *  style, and little enough that the turns stay readable while it's open. */
private val TIME_REVEAL_WIDTH = 76.dp

/** A dim sender label (only when needed), then the text — no bubbles, just a
 *  width-capped column hugging its side. */
@Composable
private fun MessageRow(
    message: ChatMessage,
    convo: Conversation,
    contacts: Contacts,
    /** The time-peek offset in px, read at draw time only (see the thread's Animatable). */
    reveal: () -> Float,
    maxRevealPx: Float,
    showLabel: Boolean,
    showReceipt: Boolean,
    replyQuote: String?,
    loadImage: suspend (Attachment) -> ImageBitmap?,
    loadFile: suspend (Attachment) -> java.io.File?,
    onImageTap: (Attachment) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    /** Hold an attachment to keep it. Null where there is nothing to save it with. */
    onSaveAttachment: ((Attachment) -> Unit)? = null,
    canReact: Boolean,
    pickerOpen: Boolean,
    onLongPress: () -> Unit,
    onReact: (ReactionType) -> Unit,
    onReply: () -> Unit,
    /** Null when this message cannot be edited from here; the menu then shows no Edit. */
    onEdit: (() -> Unit)? = null,
    onDismissPicker: () -> Unit,
) {
    // A group-system row (rename, member change) is an event line, not a turn —
    // centered and dim, with no label, gutter, or tapback affordances.
    if (message.isGroupEvent) {
        GroupEventRow(message, contacts)
        return
    }
    // Name labels on both sides — "You" for your turns, the sender's name for
    // incoming (falling back to the 1:1 counterpart when a message has no handle) —
    // but only on the first message of a same-speaker run.
    val label = when {
        !showLabel -> null
        message.fromMe -> "You"
        message.sender != null -> contacts.sender(message.sender)
        convo.participants.size == 1 -> contacts.sender(convo.participants[0])
        else -> null
    }
    // This message's moment, shown only while the thread is slid over: delivery
    // time for your own turns once the server has reported one, arrival for the
    // rest. Same wording as the conversation list, so time never reads two ways.
    val context = LocalContext.current
    val timeLine = remember(message.guid, message.dateDelivered) {
        val ts = if (message.fromMe && message.dateDelivered > 0) message.dateDelivered else message.date
        listTime(context, ts)
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        if (timeLine.isNotEmpty()) {
            Text(
                text = timeLine,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        // Off the right edge until the drag brings it in; fading with the
                        // slide keeps a half-open peek from reading as overlap.
                        val r = reveal()
                        translationX = maxRevealPx - r
                        alpha = (r / maxRevealPx).coerceIn(0f, 1f)
                    },
            )
        }
    // The name label sits above the turn (not inside the content column) so the
    // gutter reaction lines up with the message's first line, not the label.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = -reveal() },
        horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
    ) {
        if (label != null) {
            Text(text = label, style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
            Spacer(modifier = Modifier.height(4.dp))
        }
        // An inline reply points back at what it answers: a dim quote line above
        // the turn, hugging the same side.
        if (replyQuote != null) {
            Text(
                text = "↳ $replyQuote",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(MESSAGE_MAX_WIDTH),
                textAlign = if (message.fromMe) TextAlign.End else TextAlign.Start,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        // Content is width-capped; the leftover gutter on the opposite side carries
        // any tapbacks (`<- ♥` / `♥ ->`), pointing back at the turn. 0.8/0.2 weights
        // keep the same cap whether or not there's a reaction.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (message.fromMe) ReactionGutter(message, Modifier.weight(1f - MESSAGE_MAX_WIDTH))
            MessageContent(message, loadImage, loadFile, onImageTap, onOpenAttachment, onSaveAttachment, canReact, pickerOpen, onLongPress, onReact, onReply, onEdit, onDismissPicker, Modifier.weight(MESSAGE_MAX_WIDTH))
            if (!message.fromMe) ReactionGutter(message, Modifier.weight(1f - MESSAGE_MAX_WIDTH))
        }
        // "Not delivered" on any sent message the Mac later failed to deliver
        // (`error` set on the echo or a message-send-error event) — the failure the
        // app used to swallow. Full white: this line matters.
        if (message.fromMe && message.error != 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = "Not delivered", style = ChatType.hint, color = ChatColors.onSurface)
        } else {
            // The delivery receipt under your newest sent message — nothing until
            // the server reports it delivered, then "Read <when>" once read. Live
            // updates arrive as updated-message socket events through the merge.
            // "Edited" rides the same line, on any message that was, in either
            // direction: the words on screen are not the words that were first sent,
            // and Messages says so too.
            val receipt = if (showReceipt) receiptText(message) else null
            val edited = if (message.dateEdited > 0) "Edited" else null
            listOfNotNull(receipt, edited).takeIf { it.isNotEmpty() }?.let { parts ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = parts.joinToString(" · "), style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
            }
        }
    }
    }
}

/** A group event ("Liz named the conversation “X”") as a centered dim line —
 *  the row form for messages that are system actions rather than speech. */
@Composable
private fun GroupEventRow(message: ChatMessage, contacts: Contacts) {
    val actor = when {
        message.fromMe -> "You"
        message.sender != null -> contacts.sender(message.sender)
        else -> "Someone"
    }
    val line = message.groupEventText(actor) ?: return
    Text(
        text = line,
        style = ChatType.hint,
        color = ChatColors.onSurfaceDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/** The receipt line for a sent message: "Read 3:14 PM" (or "Read Yesterday" for
 *  older) once read, else "Delivered" once delivered, else nothing. */
@Composable
private fun receiptText(message: ChatMessage): String? {
    val context = LocalContext.current
    return when {
        message.dateRead > 0 -> "Read " + readTime(context, message.dateRead)
        message.dateDelivered > 0 -> "Delivered"
        else -> null
    }
}

private fun readTime(context: Context, ts: Long): String =
    if (DateUtils.isToday(ts)) {
        DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME)
    } else {
        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
    }

/** The gutter cell beside a turn — its tapbacks (if any) hugging the message edge
 *  at the top, so they read as belonging to the first line. */
@Composable
private fun ReactionGutter(message: ChatMessage, modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = if (message.fromMe) Alignment.TopEnd else Alignment.TopStart,
    ) {
        if (message.reactions.isNotEmpty()) {
            GutterReactions(message.reactions, message.fromMe, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** The message itself: the long-press tapback picker, inline images, tappable file
 *  rows, then the text — no bubbles, just a column hugging its side. */
@Composable
private fun MessageContent(
    message: ChatMessage,
    loadImage: suspend (Attachment) -> ImageBitmap?,
    loadFile: suspend (Attachment) -> java.io.File?,
    onImageTap: (Attachment) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    /** Hold an attachment to keep it. Null where there is nothing to save it with. */
    onSaveAttachment: ((Attachment) -> Unit)? = null,
    canReact: Boolean,
    pickerOpen: Boolean,
    onLongPress: () -> Unit,
    onReact: (ReactionType) -> Unit,
    onReply: () -> Unit,
    onEdit: (() -> Unit)?,
    onDismissPicker: () -> Unit,
    modifier: Modifier,
) {
    val align = if (message.fromMe) Alignment.End else Alignment.Start
    val textAlign = if (message.fromMe) TextAlign.End else TextAlign.Start
    val body = message.bodyText
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = canReact,
            onClick = { if (pickerOpen) onDismissPicker() },
            onLongClick = onLongPress,
            // Same picker as long-press. Holding is fiddly on a small matte panel, and
            // a double-tap on a message is a gesture nothing else here uses.
            onDoubleClick = onLongPress,
        ),
        horizontalAlignment = align,
    ) {
        // The tapback menu — the six tapbacks plus Reply — sits above the turn.
        if (pickerOpen) {
            ReactionPicker(
                selected = message.reactions.firstOrNull { it.fromMe }?.type,
                onReact = onReact,
                onReply = onReply,
                onEdit = onEdit,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        message.images.forEach { image ->
            AttachmentImage(
                attachment = image,
                load = loadImage,
                loadFile = loadFile,
                // A tap while the tapback picker is open dismisses it (matching a
                // tap anywhere else on the turn); otherwise it opens the viewer.
                onTap = { if (pickerOpen) onDismissPicker() else onImageTap(image) },
                // The image sits on top of the column's combinedClickable, so
                // re-offer the long-press here or images couldn't be reacted to.
                onLongPress = if (canReact) onLongPress else null,
            )
            Spacer(modifier = Modifier.height(if (body != null) 6.dp else 4.dp))
        }
        message.files.forEach { file ->
            AttachmentFile(file, textAlign, onSave = onSaveAttachment?.let { save -> { save(file) } }) {
                onOpenAttachment(file)
            }
            Spacer(modifier = Modifier.height(if (body != null) 6.dp else 4.dp))
        }
        if (body != null) {
            Text(
                text = linkify(body),
                style = ChatType.body,
                color = ChatColors.onSurface,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Turns any http/https URLs in [text] into tappable links (underlined, opened by
 * the platform's default handler — a browser), leaving the rest as plain text.
 * A message with no URL just renders verbatim.
 */
private fun linkify(text: String): AnnotatedString {
    val matches = URL_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)
    val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var last = 0
        for (m in matches) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            withLink(LinkAnnotation.Url(m.value, linkStyle)) { append(m.value) }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }
}

/** The six tapbacks as a row of the drawn glyphs; the user's current one (if any)
 *  shows bright so re-tapping it reads as "remove". */
@Composable
private fun ReactionPicker(
    selected: ReactionType?,
    onReact: (ReactionType) -> Unit,
    onReply: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    // Two rows, not one. The six glyphs alone are ~220 dp, and the message column is 80% of a
    // 3.9-inch panel, so with Reply and Edit on the same line the words were given a few pixels
    // each and wrapped letter by letter — "E / Repld / y i", per a screenshot from Discord. A word
    // that cannot be read cannot be tapped either. The verbs sit under the glyphs, in the body
    // size, with air between them: they are the two things you can do to a message besides
    // react, and they should read like it.
    Column(horizontalAlignment = Alignment.Start) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReactionType.entries.forEach { type ->
                Box(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReact(type)
                    },
                ) {
                    TapbackGlyph(
                        type = type,
                        color = if (type == selected) ChatColors.onSurface else ChatColors.onSurfaceDim,
                        size = 22.dp,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Inline reply rides the same menu (both are Private-API sends).
            HapticText(
                text = "Reply",
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                onClick = onReply,
            )
            // And so does editing, for the sender's own recent messages — the third thing the
            // Private API can do to a message that has already gone.
            if (onEdit != null) {
                HapticText(
                    text = "Edit",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceVariant,
                    maxLines = 1,
                    onClick = onEdit,
                )
            }
        }
    }
}

/** One inline image: loads (download + cache + decode) off-thread via [load],
 *  showing a dim placeholder until the bitmap is ready. Height-capped so a tall
 *  photo can't swallow the thread; width fills the message column. Tapping it
 *  opens the full-screen viewer ([onTap]); long-press still reaches the tapback
 *  picker via [onLongPress] (the image's own gesture handler would otherwise
 *  swallow it).
 *
 *  A **GIF takes the other branch**: it is played from its file rather than decoded to a
 *  bitmap, because BitmapFactory silently hands back the first frame — which is what every
 *  GIF anybody sent this app used to look like. See [AttachmentGif]. */
@Composable
private fun AttachmentImage(
    attachment: Attachment,
    load: suspend (Attachment) -> ImageBitmap?,
    loadFile: suspend (Attachment) -> java.io.File?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    if (attachment.isGif) {
        AttachmentGif(attachment, loadFile, onTap, onLongPress)
        return
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.guid) {
        value = load(attachment)
    }
    val image = bitmap
    if (image != null) {
        val haptics = LocalHapticFeedback.current
        Image(
            bitmap = image,
            contentDescription = attachment.transferName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTap()
                    },
                    onLongClick = onLongPress,
                    // A photo is a message, so double-tap reacts to it too. Children win
                    // hit-testing, so without this the double-tap on an image would just
                    // fire onTap twice and open the viewer. The cost is that opening a
                    // photo now waits out the double-tap timeout — the same trade every
                    // gallery with double-tap-to-zoom makes.
                    onDoubleClick = onLongPress,
                ),
        )
    } else {
        Text(text = "[Image]", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
    }
}

/**
 * One inline GIF, playing.
 *
 * The same gestures as a still — tap for the viewer, long-press and double-tap for the tapback
 * picker — and the same size cap, so a tall GIF can't take the thread over. The file arrives
 * through the same download and cache as everything else ([loadFile]); an outgoing one renders
 * from the bytes the send seeded there, so a GIF you have just sent animates in your own thread
 * before the server has echoed it back.
 *
 * `ContentScale.Fit` and a height cap rather than the intrinsic size: GIFs come in every shape,
 * including a 900px-tall one somebody's cousin made in 2011.
 */
@Composable
private fun AttachmentGif(
    attachment: Attachment,
    loadFile: suspend (Attachment) -> java.io.File?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val file by produceState<java.io.File?>(initialValue = null, attachment.guid) {
        value = loadFile(attachment)
    }
    val painter = rememberGifPainter(file)
    if (painter != null) {
        val haptics = LocalHapticFeedback.current
        Image(
            painter = painter,
            contentDescription = attachment.transferName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTap()
                    },
                    onLongClick = onLongPress,
                    onDoubleClick = onLongPress,
                ),
        )
    } else {
        Text(text = "[GIF]", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
    }
}

/** A non-image attachment as a tappable, underlined "Type · filename" row —
 *  tapping downloads it and hands off to an external app. */
@Composable
private fun AttachmentFile(
    attachment: Attachment,
    textAlign: TextAlign,
    onSave: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    HapticText(
        text = attachment.fileLabel,
        style = ChatType.body,
        color = ChatColors.onSurface,
        underline = true,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
        // Tap opens it in whatever can read it, hold keeps it. A hold on a *picture* still opens
        // the tapback picker, because a picture is something you react to and a file is not — so
        // the gesture does read differently on a message carrying both, which is the lesser of the
        // two oddities against a file row whose hold did nothing at all.
        onLongClick = onSave,
        onClick = onOpen,
    )
}

/**
 * How close to the oldest message held counts as "reached the end".
 *
 * Two rows of slack rather than exactly the last one, so the page is already being fetched
 * by the time the scroll gets there and history appears without a stall. More slack than
 * this and a thread fetches a page nobody was going to read.
 */
private const val OLDER_TRIGGER_DISTANCE = 3

/**
 * A sound being listened to: the local file, the name it arrived under, and the attachment it came
 * from.
 *
 * The attachment as well as the file, because a transcript is remembered against the attachment's
 * guid — the file is a cache entry that can be evicted while the words are still worth keeping.
 */
private data class Listening(
    val file: java.io.File,
    val name: String?,
    val attachment: Attachment,
)
