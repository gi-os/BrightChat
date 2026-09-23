package com.gios.lightchat.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.Attachment
import com.gios.lightchat.ChatBackground
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Contacts
import com.gios.lightchat.DetailsState
import com.gios.lightchat.Dialer
import com.gios.lightchat.NewContact
import com.gios.lightchat.NotebookLink
import com.gios.lightchat.SharedLink
import com.gios.lightchat.api.Store
import com.gios.lightchat.elideUrl
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The contact page for one conversation — reached by tapping the thread's title.
 *
 * Everything the conversation has accumulated, in the order it is worth having: who is in
 * it, the note kept about them, the photos, the links. A group additionally gets the
 * management verbs it always had (rename, add, remove, leave), and only when the server's
 * Private API is live — the server gates all four on it. Destructive taps confirm by a
 * second tap rather than a dialog, keeping the LightOS text-only style.
 *
 * **One scroller.** Photos are laid out as rows of three inside the same [LazyColumn] as
 * everything else rather than as a nested grid: a lazy grid inside a lazy column has no
 * bounded height to measure against, and the whole page has to scroll as one thing anyway
 * — reaching the bottom of it is what asks for more history.
 */
@Composable
fun ChatDetailsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val convo = state.open ?: return
    // A notification deep link can change the open thread under a composed details page.
    // `DisposableEffect` only re-keys after that frame has been laid out, so without this
    // one frame draws the new person's People list over the previous conversation's
    // photographs and links.
    val details = viewModel.details.collectAsState().value
        .takeIf { it.chatGuid == convo.guid } ?: DetailsState()
    val context = LocalContext.current

    // Rename / add / remove / leave are group verbs, and the server gates them on the
    // Private API. Both halves matter now that this screen also opens for a 1:1: without
    // the isGroup half a two-person chat would offer to be renamed and left.
    val canManage = viewModel.caps(convo).groupRename && convo.isGroup

    // Reading the store and extracting every URL in it happens here, not when the thread
    // opens: this is the only screen that wants it.
    DisposableEffect(convo.guid) {
        viewModel.openDetails()
        onDispose { viewModel.closeDetails() }
    }

    var editingName by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(convo.displayName) }
    var editingNickname by remember { mutableStateOf(false) }
    var draftNickname by remember { mutableStateOf(convo.nickname.orEmpty()) }
    var addingTo by remember { mutableStateOf(false) }
    var addQuery by remember { mutableStateOf("") }
    // The address whose Remove (or "leave") is one tap from firing; any other
    // tap resets it, so a stray touch can't remove someone.
    var confirming by remember { mutableStateOf<String?>(null) }
    // The photo being looked at full-screen, if any.
    var viewing by remember(convo.guid) { mutableStateOf<Attachment?>(null) }
    // The background editor, drawn as an overlay like the photo viewer.
    var editingBackground by remember(convo.guid) { mutableStateOf(false) }
    // Reading the version in composition is what re-asks after a save or a remove.
    val bgVersion = ChatBackground.version.intValue
    val hasBackground = remember(convo.guid, bgVersion) { ChatBackground.has(context, convo.guid) }
    // A one-line complaint from this screen (a link with nothing to open it). Drawn beside
    // the ViewModel's own, at the foot of the page.
    var notice by remember(convo.guid) { mutableStateOf<String?>(null) }

    // All of these are computed once per conversation rather than per recomposition, and
    // hoisted out of the LazyColumn: a `remember` inside a lazy item is scoped to that
    // item, so scrolling the note row off screen and back would re-run a PackageManager
    // IPC and a preferences read on the frame it reappears.
    val noteKey = remember(convo.guid) { viewModel.noteKey(convo) }
    val noteTitle = state.contacts.title(convo)
    val notebookInstalled = remember { NotebookLink.available(context) }
    // Asked once for the page, not once per person: it is a PackageManager IPC and the answer
    // cannot differ between two rows of the same list. Same reasoning as notebookInstalled
    // above, and the same reason both are hoisted out of the LazyColumn.
    val canDial = remember { Dialer.available(context) }
    val ring = rememberCaller()
    // The address whose Call is armed. Separate from `confirming` above, which arms a Remove:
    // one state for both would make tapping Call arm that person's Remove as well, and the two
    // verbs sit next to each other in the row.
    var confirmingCall by remember(convo.guid) { mutableStateOf<String?>(null) }
    LaunchedEffect(confirmingCall) {
        if (confirmingCall == null) return@LaunchedEffect
        kotlinx.coroutines.delay(CALL_CONFIRM_MS)
        confirmingCall = null
    }
    var noteOpened by remember(noteKey) { mutableStateOf(Store.noteOpened(context, noteKey)) }
    // The participant list keyed the People rows directly; a handle listed twice by the
    // server is a duplicate-key crash in a LazyColumn.
    val people = remember(convo.participants) { convo.participants.distinct() }
    val photoRows = remember(details.images) { details.images.chunked(PHOTO_COLUMNS) }

    // The page runs well past the screen, and the wheel is the only way down it that
    // doesn't put a thumb over the Remove taps. Withheld while a photo is open, or the
    // page would scroll underneath it.
    val listState = rememberLazyListState()
    WheelScroll(listState, active = viewing == null)

    // Reaching the bottom reads further back.
    //
    // `canScrollBackward` is the load-bearing half: without it a short page — a chat with
    // no photos and no links, where everything fits on screen — is *already* at its end on
    // the frame it opens, and would fire a history fetch nobody asked for. Requiring that
    // the page has been scrolled down at all makes this mean what it says. (That page can
    // still ask, by tapping the row at its foot — it just is not asked for on its behalf.)
    //
    // Collected as a flow rather than as `LaunchedEffect(atEnd)`, and the conditions that
    // would make the call a no-op are folded into the predicate rather than checked after
    // it. A keyed effect only re-runs when its key changes, so a call that bailed —
    // because the thread was still loading its first page — would never be retried: the
    // key would still be `true` and the page would never page again.
    //
    // `rememberUpdatedState`, not the `details` value itself: the effect is keyed on the
    // conversation, so it is *not* restarted when the details change, and a plain capture
    // would freeze `loading` and `exhausted` at whatever they were on the frame the page
    // opened. Read through a State, they are snapshot reads and the flow re-evaluates.
    val live = rememberUpdatedState(details)
    LaunchedEffect(convo.guid) {
        snapshotFlow {
            !listState.canScrollForward &&
                listState.canScrollBackward &&
                !live.value.loading &&
                !live.value.exhausted &&
                !state.threadLoading
        }
            .distinctUntilChanged()
            .collect { if (it) viewModel.loadMoreDetails() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
            ScreenHeader(
                title = "Details",
                onBack = onBack,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )

            // Linking this person's other chats (Beeper on, one-to-ones only). Hoisted with the rest.
            var linking by remember(convo.guid) { mutableStateOf(false) }
            val linkable = if (linking) {
                viewModel.displayed(state).filter {
                    !it.isGroup && !it.isAgent && it.guid !in convo.guids
                }
            } else {
                emptyList()
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (convo.isGroup) {
                    item(key = "name") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            SectionLabel("Name")
                            if (editingName && canManage) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    BasicTextField(
                                        value = draftName,
                                        onValueChange = { draftName = it },
                                        singleLine = true,
                                        textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                                        cursorBrush = SolidColor(ChatColors.onSurface),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = {
                                            if (draftName.isNotBlank()) viewModel.renameGroup(draftName)
                                            editingName = false
                                        }),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = ChatColors.onSurfaceDisabled,
                                    )
                                }
                            } else {
                                val name = convo.displayName.ifBlank { "No name" }
                                if (canManage) {
                                    HapticText(
                                        text = name,
                                        style = ChatType.body,
                                        color = ChatColors.onSurface,
                                        textAlign = TextAlign.Start,
                                        maxLines = 1,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            draftName = convo.displayName
                                            editingName = true
                                            confirming = null
                                        },
                                    )
                                } else {
                                    Text(
                                        text = name,
                                        style = ChatType.body,
                                        color = ChatColors.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "nickname") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SectionLabel("Nickname")
                        if (editingNickname) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                BasicTextField(
                                    value = draftNickname,
                                    onValueChange = { draftNickname = it },
                                    singleLine = true,
                                    textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                                    cursorBrush = SolidColor(ChatColors.onSurface),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        viewModel.setNickname(convo.guid, draftNickname)
                                        editingNickname = false
                                    }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
                            }
                        } else {
                            val nick = convo.nickname
                            HapticText(
                                text = nick?.takeIf { it.isNotBlank() } ?: "Tap to set",
                                style = ChatType.body,
                                color = if (nick.isNullOrBlank()) ChatColors.onSurfaceDim else ChatColors.onSurface,
                                textAlign = TextAlign.Start,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    draftNickname = nick.orEmpty()
                                    editingNickname = true
                                },
                            )
                        }
                    }
                }

                // Which networks this person is on. Only with Beeper signed in, so a phone that
                // only has iMessage sees the page exactly as it was.
                if (state.beeperOn && !convo.isGroup && !convo.isAgent) {
                    item(key = "threads-label") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(24.dp))
                            SectionLabel("Threads")
                        }
                    }
                    items(convo.members.ifEmpty { listOf(convo) }, key = { "thread:" + it.guid }) { member ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                text = com.gios.lightchat.people.People.networkOf(member),
                                style = ChatType.body,
                                color = ChatColors.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (convo.isPerson) {
                                HapticText(
                                    text = "Unlink",
                                    style = ChatType.hint,
                                    color = ChatColors.onSurfaceDim,
                                    onClick = { viewModel.unlinkChat(convo, member.guid) },
                                )
                            }
                        }
                    }
                    item(key = "thread-link") {
                        HapticText(
                            text = if (linking) "Cancel" else "Link another chat",
                            style = ChatType.hint,
                            color = ChatColors.onSurfaceDim,
                            textAlign = TextAlign.Start,
                            onClick = { linking = !linking },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                    items(linkable, key = { "link:" + it.guid }) { other ->
                        HapticText(
                            text = state.contacts.title(other) + " · " + com.gios.lightchat.people.People.networkOf(other),
                            style = ChatType.body,
                            color = ChatColors.onSurfaceDim,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            onClick = {
                                linking = false
                                val mine = convo.members.firstOrNull()?.guid ?: convo.guid
                                val theirs = other.members.firstOrNull()?.guid ?: other.guid
                                viewModel.linkChats(mine, theirs)
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }

                item(key = "people-label") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SectionLabel("People")
                    }
                }
                items(people, key = { "person:$it" }) { address ->
                    PersonRow(
                        address = address,
                        contacts = state.contacts,
                        // **The group case is the reason this row has a Call at all.** A group
                        // thread's header offers none, because picking who to ring on the
                        // user's behalf is the one thing it cannot honestly do — here every
                        // member is already listed, so the choice is just a tap.
                        confirmingCall = confirmingCall == address,
                        // **Only when there is no name.** A member the address book already knows
                        // has nothing to save, and the row is dense enough without a verb that
                        // does nothing. This is the whole of "add an unknown sender to contacts":
                        // the contact page already lists exactly the handles a conversation has,
                        // named or not.
                        onSave = if (state.contacts.name(address) == null && NewContact.savable(address)) {
                            { NewContact.create(context, address) }
                        } else {
                            null
                        },
                        onCall = if (canDial && Dialer.callable(address)) {
                            {
                                if (confirmingCall == address) {
                                    confirmingCall = null
                                    ring(address)
                                } else {
                                    // Arming one disarms any other, so two rows can never both
                                    // be asking — the same rule Remove follows.
                                    confirmingCall = address
                                }
                            }
                        } else {
                            null
                        },
                        // A group of two is one departure from being a 1:1, and the server
                        // will not remove the last other person anyway.
                        canRemove = canManage && viewModel.caps(convo).groupMembers && people.size > 2,
                        confirming = confirming == address,
                        onRemove = {
                            if (confirming == address) {
                                confirming = null
                                viewModel.removeMember(address)
                            } else {
                                confirming = address
                            }
                        },
                    )
                }
                if (canManage && viewModel.caps(convo).groupMembers) {
                    item(key = "add-member") {
                        if (addingTo) {
                            AddMemberField(
                                query = addQuery,
                                onQueryChange = { addQuery = it },
                                state = state,
                                onPick = { address ->
                                    viewModel.addMember(address)
                                    addingTo = false
                                    addQuery = ""
                                },
                            )
                        } else {
                            HapticText(
                                text = "Add someone",
                                style = ChatType.body,
                                color = ChatColors.onSurfaceVariant,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                onClick = {
                                    addingTo = true
                                    confirming = null
                                },
                            )
                        }
                    }
                }

                item(key = "note") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SectionLabel("Note")
                        NoteRow(
                            installed = notebookInstalled,
                            opened = noteOpened,
                            onOpen = {
                                notice = null
                                if (NotebookLink.open(context, noteKey, noteTitle)) {
                                    Store.setNoteOpened(context, noteKey)
                                    noteOpened = true
                                } else {
                                    notice = "Couldn’t open Notebook"
                                }
                            },
                        )
                    }
                }

                item(key = "background") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SectionLabel("Background")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The same door shape as the note row: one verb that opens the
                            // editor, which owns everything else (the photo, the filters).
                            HapticText(
                                text = if (hasBackground) "Change background" else "Set a background",
                                style = ChatType.body,
                                color = ChatColors.onSurface,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                                onClick = {
                                    editingBackground = true
                                    confirming = null
                                },
                            )
                            if (hasBackground) {
                                Spacer(modifier = Modifier.width(12.dp))
                                HapticText(
                                    text = if (confirming == BG_REMOVE) "Remove?" else "Remove",
                                    style = ChatType.hint,
                                    color = if (confirming == BG_REMOVE) ChatColors.onSurface else ChatColors.onSurfaceDim,
                                    onClick = {
                                        if (confirming == BG_REMOVE) {
                                            confirming = null
                                            ChatBackground.remove(context, convo.guid)
                                        } else {
                                            confirming = BG_REMOVE
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "photos-label") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SectionLabel("Photos")
                    }
                }
                if (photoRows.isEmpty()) {
                    item(key = "photos-empty") { EmptyLine(emptySectionText(details, "photos")) }
                } else {
                    items(photoRows, key = { "photos:${it.first().guid}" }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = PHOTO_GUTTER),
                            horizontalArrangement = Arrangement.spacedBy(PHOTO_GUTTER),
                        ) {
                            row.forEach { attachment ->
                                PhotoCell(
                                    attachment = attachment,
                                    load = viewModel::loadThumbnail,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewing = attachment },
                                )
                            }
                            // A last row of one or two keeps its cells the same size as
                            // every other row's rather than stretching across the screen.
                            repeat(PHOTO_COLUMNS - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                item(key = "links-label") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SectionLabel("Links")
                    }
                }
                if (details.links.isEmpty()) {
                    item(key = "links-empty") { EmptyLine(emptySectionText(details, "links")) }
                } else {
                    items(details.links, key = { "link:${it.url}" }) { link ->
                        LinkRow(
                            link = link,
                            contacts = state.contacts,
                            onOpen = {
                                notice = null
                                if (!openUrl(context, link.url)) {
                                    notice = "Nothing on this phone opens links"
                                }
                            },
                        )
                    }
                }

                // The end of the page, and the way to move it.
                //
                // Present whenever there is more to read, and it changes its *text* rather
                // than appearing and disappearing: a row that comes and goes with `loading`
                // changes how far the list scrolls, which flips the "am I at the bottom"
                // trigger above back and forth and turns one scroll into an unattended
                // chain of network fetches.
                //
                // It is tappable for the case the scroll trigger cannot serve: a page whose
                // content fits on screen never scrolls, so it could otherwise never ask for
                // the history that would give it something to show.
                if (details.scanned > 0 && !details.exhausted) {
                    item(key = "more") {
                        HapticText(
                            text = if (details.loading) "…" else "Look further back",
                            style = ChatType.meta,
                            color = ChatColors.onSurfaceDim,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            onClick = { if (!details.loading) viewModel.loadMoreDetails() },
                        )
                    }
                }

                if (canManage) {
                    item(key = "leave") {
                        HapticText(
                            text = if (confirming == LEAVE) {
                                "Leave the conversation?"
                            } else {
                                "Leave conversation"
                            },
                            style = ChatType.body,
                            color = if (confirming == LEAVE) {
                                ChatColors.onSurface
                            } else {
                                ChatColors.onSurfaceDim
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            onClick = {
                                if (confirming == LEAVE) {
                                    confirming = null
                                    viewModel.leaveGroup() // closes the thread; details unmount with it
                                } else {
                                    confirming = LEAVE
                                }
                            },
                        )
                    }
                }

                // Delete the whole conversation, the verb LightOS's own messenger calls
                // "clear" and reaches the same way: tap the name at the top of a thread.
                // Not inside `canManage` — that also requires a group, and a 1:1 is the
                // thread you most want to be rid of. Gated on the Private API alone
                // because that is what the server gates DELETE /chat/:guid on; the
                // ViewModel refuses without it, so offering the row would be a dead tap.
                //
                // There is deliberately no local-only "clear": the store is a cache of
                // the Mac's Messages, so emptying it here would refill on the next sync
                // and read as the delete having failed.
                if (viewModel.caps(convo).deleteChat) {
                    item(key = "delete") {
                        HapticText(
                            text = if (confirming == DELETE) {
                                "Delete this conversation?"
                            } else {
                                "Delete conversation"
                            },
                            style = ChatType.body,
                            color = if (confirming == DELETE) {
                                ChatColors.onSurface
                            } else {
                                ChatColors.onSurfaceDim
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            onClick = {
                                if (confirming == DELETE) {
                                    confirming = null
                                    // Clears `open`, so this screen and the thread under
                                    // it both unmount back to the conversation list.
                                    viewModel.deleteConversation(convo)
                                } else {
                                    confirming = DELETE
                                }
                            },
                        )
                    }
                }
            }

            // This screen's own line first: it is the newer of the two, and a stale
            // ViewModel message ("Couldn't remove them") would otherwise mask it forever.
            (notice ?: state.message)?.let {
                Text(
                    text = it,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        }

        // An overlay, never an early return: returning early here would unmount the
        // LazyColumn and lose how far down the page you had scrolled, which after a
        // hundred photos is the whole of what you were doing. It also owns ColorMode, so
        // photos open in colour. Its own BackHandler is registered after the one that
        // closes this screen, so Back closes the photo first.
        viewing?.let { attachment ->
            ImageViewerScreen(
                attachment,
                viewModel::loadImage,
                onClose = { viewing = null },
                loadFile = viewModel::loadImageFile,
            )
        }

        // The background editor, same overlay bargain as the viewer: the details page
        // (and its scroll) stays composed underneath. Surface rather than a painted Box
        // so taps can't fall through to the rows behind it. The editor registers its own
        // BackHandlers after this one, so Back walks out of the editor first.
        if (editingBackground) {
            BackHandler { editingBackground = false }
            Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
                BackgroundEditorScreen(convo.guid, onClose = { editingBackground = false })
            }
        }
    }
}

/** Photos per row. Three is Roll's roll, and the width a thumbnail stays recognisable at. */
private const val PHOTO_COLUMNS = 3

/** Between thumbnails. Hairline, so the grid reads as one sheet rather than as tiles. */
private val PHOTO_GUTTER = 1.dp

/** Sentinel for [ChatDetailsScreen]'s two-tap confirm on Leave (it shares the
 *  `confirming` slot with the per-member Remove, which stores addresses). */
// Escaped rather than a literal NUL byte: an actual 0x00 in the source made
// git and grep treat this whole file as binary, so it never showed in a diff.
private const val LEAVE = "\u0000leave"

/** Sentinel for the background row's two-tap Remove, sharing the same slot. */
private const val BG_REMOVE = "\u0000background"

/** Sentinel for the Delete conversation row's two-tap confirm, same slot again. */
private const val DELETE = "\u0000delete"

/** A section heading, plus the gap under it. */
@Composable
private fun SectionLabel(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = text, style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** What a section says when it has nothing in it. */
@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = ChatType.meta,
        color = ChatColors.onSurfaceDim,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/**
 * The two empty states are different facts and have to read differently.
 *
 * A chat whose thread has never been opened holds no messages at all, so an empty section
 * says nothing about whether there are any — and telling somebody there are none when
 * the phone has not looked is how a screen gets a reputation for being broken.
 */
private fun emptySectionText(details: DetailsState, noun: String): String = when {
    details.chatGuid == null || (details.loading && details.scanned == 0) -> "…"
    !details.threadLoaded -> "This chat hasn’t been opened yet"
    // The page has read a window, not the conversation. Saying there are none, on the
    // strength of the newest couple of hundred messages, would be a claim nothing here
    // checked — and the row at the foot of the page is how you check it.
    !details.exhausted -> "No $noun in the recent messages"
    else -> "No $noun in this conversation"
}

/** One member: their name over their address, with Remove when the group allows it. */
@Composable
private fun PersonRow(
    address: String,
    contacts: Contacts,
    canRemove: Boolean,
    confirming: Boolean,
    onRemove: () -> Unit,
    /** Null when there is nothing to ring — an Apple ID, or a phone with no dialer. */
    onCall: (() -> Unit)? = null,
    /** Whether this row's Call is one tap from ringing. */
    confirmingCall: Boolean = false,
    /** Null when the address book already knows them, or the handle is an Apple ID. */
    onSave: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contacts.name(address) ?: address,
                style = ChatType.body,
                color = ChatColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (contacts.name(address) != null) {
                Text(
                    text = address,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Save first: on an unknown number it is the verb that makes the other two better, since
        // a saved contact is one this app can name from then on.
        if (onSave != null) {
            Spacer(modifier = Modifier.width(12.dp))
            HapticText(
                text = "Save",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                onClick = onSave,
            )
        }
        // Call before Remove, and never dimmed-but-present when there is nothing to ring: a
        // verb that explains itself only after being tapped is a verb that shouldn't be there.
        if (onCall != null) {
            Spacer(modifier = Modifier.width(12.dp))
            HapticText(
                text = if (confirmingCall) "Call?" else "Call",
                style = ChatType.hint,
                color = if (confirmingCall) ChatColors.onSurface else ChatColors.onSurfaceDim,
                onClick = onCall,
            )
        }
        if (canRemove) {
            Spacer(modifier = Modifier.width(12.dp))
            HapticText(
                text = if (confirming) "Remove?" else "Remove",
                style = ChatType.hint,
                color = if (confirming) ChatColors.onSurface else ChatColors.onSurfaceDim,
                onClick = onRemove,
            )
        }
    }
}

/**
 * The conversation's note, which is kept in LightNotebook and edited there.
 *
 * Nothing is read back across the app boundary — the row is a door, not a preview. What it
 * can honestly say is whether this phone has ever opened this conversation's note, which is
 * remembered in [Store.noteOpened]; the note itself may since have been deleted in
 * LightNotebook, and "Open note" landing on a fresh empty one is a fair outcome either way.
 *
 * Whether LightNotebook is installed is *asked* by the caller, not discovered by a launch
 * failing — catching `ActivityNotFoundException` would only tell us after the tap, and this
 * decides what the row says before it.
 */
@Composable
private fun NoteRow(installed: Boolean, opened: Boolean, onOpen: () -> Unit) {
    if (!installed) {
        Text(
            text = "Install Notebook to keep a note here",
            style = ChatType.meta,
            color = ChatColors.onSurfaceDim,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        return
    }
    HapticText(
        text = if (opened) "Open note" else "Add a note",
        style = ChatType.body,
        color = ChatColors.onSurface,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        onClick = onOpen,
    )
}

/**
 * One thumbnail. Square and cropped, because a grid of mixed aspect ratios is a grid of
 * gaps; the full shape is one tap away in the viewer.
 */
@Composable
private fun PhotoCell(
    attachment: Attachment,
    load: suspend (Attachment) -> ImageBitmap?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.guid) {
        value = load(attachment)
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            // A dim square while the bytes arrive, so the grid has its final shape from the
            // first frame instead of reflowing as photos land.
            .background(ChatColors.onSurfaceDisabled.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = attachment.transferName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One link: where it goes, then who sent it and when. */
@Composable
private fun LinkRow(link: SharedLink, contacts: Contacts, onOpen: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HapticText(
            text = elideUrl(link.url),
            style = ChatType.body,
            color = ChatColors.onSurface,
            underline = true,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpen,
        )
        val who = if (link.fromMe) "You" else link.sender?.let(contacts::sender) ?: "Someone"
        // A message with no usable date would otherwise read "You · " with nothing after it.
        val sent = listTime(context, link.date)
        Text(
            text = if (sent.isBlank()) who else "$who · $sent",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDim,
            maxLines = 1,
        )
    }
}

/**
 * Hands a URL to whatever opens links.
 *
 * @return false when nothing did — a Light Phone can genuinely have no browser
 *   installed, and the row has to say so rather than swallowing the tap.
 */
private fun openUrl(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)

/** Inline add-member picker: type a name/number/email, tap a contact match (or
 *  the raw address) to add them to the group. */
@Composable
private fun AddMemberField(
    query: String,
    onQueryChange: (String) -> Unit,
    state: com.gios.lightchat.UiState,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = ChatType.body.copy(color = ChatColors.onSurface),
            cursorBrush = SolidColor(ChatColors.onSurface),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        val q = query.trim()
        if (q.isNotEmpty()) {
            val matches = state.contactList
                .filter { it.name.contains(q, true) || it.address.contains(q, true) }
                .take(5)
            matches.forEach { contact ->
                HapticText(
                    text = "${contact.name} — ${contact.address}",
                    style = ChatType.meta,
                    color = ChatColors.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    onClick = { onPick(contact.address) },
                )
            }
            if (q.length >= 3 && matches.none { it.address.equals(q, ignoreCase = true) }) {
                HapticText(
                    text = "Add “$q”",
                    style = ChatType.meta,
                    color = ChatColors.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    onClick = { onPick(q) },
                )
            }
        }
    }
}
