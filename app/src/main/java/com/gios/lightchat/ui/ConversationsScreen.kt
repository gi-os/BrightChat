@file:OptIn(ExperimentalFoundationApi::class)

package com.gios.lightchat.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Conversation
import com.gios.lightchat.Pins
import com.gios.lightchat.Status
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The conversation list — newest activity first, tap to open, long-press to star,
 * tap the title for settings. Split across three tabs (see [ConversationTab]) with
 * a LightFog-style icon bar at the bottom.
 *
 * [tab] and [listState] are hoisted into `LightChatApp` rather than remembered here:
 * this composable leaves the composition entirely while a thread is open, so a
 * local scroll position would be discarded and every exit would land back at the
 * top of the list.
 */
@Composable
fun ConversationsScreen(
    viewModel: ChatViewModel,
    tab: ConversationTab,
    listState: LazyListState,
    onSelectTab: (ConversationTab) -> Unit,
    onOpenSettings: () -> Unit,
    onNewMessage: () -> Unit,
    /**
     * Whether the title should still play its one-time "there's a Settings page here" hint.
     * Hoisted to [com.gios.lightchat.LightChatApp] rather than remembered locally: this screen
     * leaves the composition entirely every time a thread, settings or the composer is open (see
     * the class doc above), so a flag remembered here would replay the hint on every return to
     * the list — which reads as a loop, not a one-time nudge.
     */
    playTitleHint: Boolean = false,
    onTitleHintPlayed: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    // The wheel scrolls whichever tab is showing. The state is the one hoisted in
    // LightChatApp, so a notch moves the list you can see and not one of its siblings.
    WheelScroll(listState)

    // Partitioned once per list/contacts/favorites change, not per row.
    val visible = remember(state.conversations, state.contacts, state.favorites, state.pins, tab) {
        val onTab = state.conversations.filter { tabOf(it, state.contacts, state.favorites) == tab }
        // Pins only mean anything on Favorites. A pin is an order *within* the starred list, and
        // applying it to Messages would move a chat above conversations that are simply newer —
        // which is the one thing that list promises not to do.
        if (tab == ConversationTab.Favorites) Pins.order(onTab, state.pins) else onTab
    }
    val unreadTabs = remember(state.conversations, state.contacts, state.favorites) {
        state.conversations
            .filter { it.unread }
            .mapTo(mutableSetOf()) { tabOf(it, state.contacts, state.favorites) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                HapticIcon(
                    icon = PlusIcon,
                    contentDescription = "New conversation",
                    tint = ChatColors.onSurfaceVariant,
                    onClick = onNewMessage,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            AnimatedTitle(
                title = tab.title,
                playHint = playTitleHint,
                onHintPlayed = onTitleHintPlayed,
                onClick = onOpenSettings,
            )
            Spacer(modifier = Modifier.weight(1f))
            HapticText(
                text = "Refresh",
                style = ChatType.hint,
                color = if (state.status == Status.Loading) ChatColors.onSurfaceDisabled else ChatColors.onSurfaceVariant,
                onClick = viewModel::refresh,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }

        when {
            visible.isEmpty() -> {
                val label = when {
                    // Only the first load is worth a spinner-ish line; once the list
                    // has arrived an empty tab is a fact about the tab, not a state.
                    state.conversations.isEmpty() && state.status == Status.Loading -> "Loading…"
                    state.conversations.isEmpty() -> state.message ?: "No conversations"
                    tab == ConversationTab.Favorites -> "No favorites yet.\nLong-press a chat to star it."
                    tab == ConversationTab.Unknown -> "No unknown senders"
                    else -> "No conversations"
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDisabled,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(visible, key = { it.guid }) { convo ->
                    // A tapback as the newest activity shows as "Liz loved an image";
                    // otherwise the real message text, prefixed "You: " when it's ours.
                    val subtitle = convo.lastReaction?.summary(state.contacts)
                        ?: ((if (convo.lastFromMe) "You: " else "") + convo.lastText)
                    ConversationRow(
                        convo = convo,
                        title = state.contacts.title(convo),
                        subtitle = subtitle,
                        // Deleting a chat needs the Private API (server gate); only then
                        // do we let the row swipe to reveal Delete. Agents always can —
                        // they're deleted locally, no server involved.
                        canDelete = state.privateApi || convo.isAgent,
                        onDelete = { viewModel.deleteConversation(convo) },
                        onClick = { viewModel.open(convo) },
                        onToggleFavorite = { viewModel.toggleFavorite(convo) },
                        // Only on Favorites, and only there because that is the only list a pin
                        // has an opinion about. Toggled by the swipe reveal on that tab, so it
                        // has no gesture of its own to remember.
                        pinned = if (tab == ConversationTab.Favorites) {
                            Pins.isPinned(state.pins, convo.guid)
                        } else {
                            null
                        },
                        onTogglePin = { viewModel.togglePin(convo) },
                    )
                }
            }
        }

        ConversationNavbar(
            current = tab,
            unread = unreadTabs,
            onSelect = onSelectTab,
            // MainActivity hides the system bars, so this is usually zero — but it
            // keeps the glyphs off the gesture strip on the swipe that brings the
            // bars back transiently.
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun ConversationRow(
    convo: Conversation,
    title: String,
    subtitle: String,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    /** Whether this row is pinned, or null on a tab where pinning means nothing. */
    pinned: Boolean? = null,
    onTogglePin: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    // How far the row slides left to reveal Delete. Keyed to the guid so a recycled
    // row for a different chat starts closed.
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    val offsetX = remember(convo.guid) { Animatable(0f) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Behind the row, uncovered as it slides left. Tapping acts immediately — the swipe is
        // its own confirm.
        //
        // **On Favorites this is Pin/Unpin, not Delete.** Long-press is star/unstar on every
        // tab, so pinning needs a home of its own and the swipe is where the secondary verb
        // goes: Pin here, Delete on Messages. Deleting a chat you starred is rarer than pinning
        // or unpinning one, and Delete is still one tab away.
        val revealable = pinned != null || canDelete
        if (revealable) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                HapticText(
                    text = when {
                        pinned != null -> if (pinned) "Unpin" else "Pin"
                        else -> "Delete"
                    },
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    onClick = if (pinned != null) onTogglePin else onDelete,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // Opaque so the Delete action stays hidden under the row until swiped.
                .background(ChatColors.background)
                .then(
                    if (revealable) {
                        Modifier.pointerInput(convo.guid) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, drag ->
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + drag).coerceIn(-revealPx, 0f))
                                    }
                                },
                                // Settle open past the halfway point, else snap closed.
                                onDragEnd = {
                                    val target = if (offsetX.value < -revealPx / 2f) -revealPx else 0f
                                    scope.launch { offsetX.animateTo(target) }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // While open, a tap just closes the row rather than opening it.
                        if (offsetX.value < -1f) scope.launch { offsetX.animateTo(0f) } else onClick()
                    },
                    /**
                     * **Long-press is always star / unstar.**
                     *
                     * The gesture that starred a chat unstars it too, so a star is never a
                     * one-way trip. On Favorites that removes the chat to Messages — the row
                     * moving to another tab is its own confirmation — and drops any pin with it
                     * (see [Pins.prune]). Pinning lives on the swipe, where the "↑" on the title
                     * is its confirmation.
                     *
                     * The first attempt put pin on the long-press here, which hijacked the one
                     * gesture that reverses a star and stranded unstarring on a swipe nobody
                     * found. Pinning also spent a stretch as its own text verb in the row; it
                     * shared the line with the title and the timestamp on a 3.92" panel, so the
                     * title lost a third of its width to a word only one tab cares about — a tap
                     * target that small inside a row that is itself clickable and swipeable is a
                     * coin toss. The swipe the row already has costs nothing.
                     */
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (offsetX.value < -1f) {
                            scope.launch { offsetX.animateTo(0f) }
                        } else {
                            onToggleFavorite()
                        }
                    },
                )
                .padding(vertical = 14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Text-only unread marker, in keeping with the B&W style; the
                    // brighter subtitle below reinforces it.
                    // Two markers, both text, both prefixes, because that is how this app says
                    // "something is true about this row" everywhere else. A pin is an anchor:
                    // "↑" reads as "held up here" without needing a legend.
                    text = (if (pinned == true) "↑ " else "") + (if (convo.unread) "• " else "") + title,
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = listTime(LocalContext.current, convo.lastDate),
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                )
            }
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = ChatType.meta,
                    color = if (convo.unread) ChatColors.onSurface else ChatColors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


/**
 * The list header's title — normally the current tab's name, tappable through to Settings.
 *
 * **The hint.** First time this ever shows (see [playTitleHint]'s doc at the call site), it
 * fades from the tab title to "Settings" and back — a quick, silent "there's a page behind this
 * word" nudge for the one piece of navigation on this screen with no visible affordance at all
 * (no icon, no chevron — just a tappable word, which is easy to never discover). It runs once
 * and never loops: [onHintPlayed] fires after the fade back completes, and the caller's flag
 * keeps it from running again for the rest of this app session.
 */
@Composable
private fun AnimatedTitle(
    title: String,
    playHint: Boolean,
    onHintPlayed: () -> Unit,
    onClick: () -> Unit,
) {
    var showingHint by remember { mutableStateOf(false) }
    LaunchedEffect(playHint) {
        if (!playHint) return@LaunchedEffect
        delay(TITLE_HINT_DELAY_MS)
        showingHint = true
        delay(TITLE_HINT_HOLD_MS)
        showingHint = false
        // Let the fade back finish before telling the caller it's done, so a recomposition
        // mid-fade (e.g. the tab changing) can't cut the return trip short.
        delay(TITLE_HINT_FADE_MS)
        onHintPlayed()
    }
    Crossfade(targetState = if (playHint) showingHint else false, label = "titleHint") { hint ->
        HapticText(
            text = if (hint) "Settings" else title,
            style = ChatType.body,
            color = ChatColors.onSurface,
            onClick = onClick,
        )
    }
}

/** Before the hint starts fading in — a beat to let the screen settle first. */
private const val TITLE_HINT_DELAY_MS = 600L

/** How long "Settings" stays up before fading back. */
private const val TITLE_HINT_HOLD_MS = 1_100L

/** Roughly Crossfade's own default animation length, given as a wait so [onHintPlayed] never
 *  fires mid-fade. */
private const val TITLE_HINT_FADE_MS = 400L
