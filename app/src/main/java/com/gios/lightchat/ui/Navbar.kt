package com.gios.lightchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.gios.lightchat.Contacts
import com.gios.lightchat.Conversation
import com.gios.lightchat.ui.theme.ChatColors

/**
 * The conversation list's three tabs. [title] is what the header shows; Known is
 * titled "Messages" because it's the default view and the app's own name for
 * itself — renaming it to "Known" would make the common case read like a filter.
 */
enum class ConversationTab(val title: String) {
    Favorites("Favorites"),
    Known("Messages"),
    Unknown("Unknown"),

    /**
     * The dialer, which is also the contacts list.
     *
     * In the same enum as the three message tabs because it is the same bottom bar and the same
     * "which page am I on" question — not because it holds conversations. [tabOf] never returns
     * it, which is the honest expression of that: no conversation belongs here.
     */
    Dial("Dial"),
}

/**
 * Icons-only bottom bar, the same pattern as LightFog's navbar: Material glyphs at
 * 48dp, no labels, active white and inactive mid-grey, evenly spread with the
 * list's own 20dp gutter so the outer two icons line up with the rows above them.
 *
 * The glyphs are hand-parsed Material paths rather than a material-icons
 * dependency, matching how [TapbackGlyph] draws its heart and thumbs — the
 * artifact would be several megabytes for three shapes.
 *
 * A tab with anything unread carries a small filled dot at its top-right, the same
 * "something is pending here" language as the `• ` marker on an unread row.
 */
@Composable
fun ConversationNavbar(
    current: ConversationTab,
    unread: Set<ConversationTab>,
    onSelect: (ConversationTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in ConversationTab.entries) {
            TabIcon(
                tab = tab,
                active = tab == current,
                unread = tab in unread,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun TabIcon(tab: ConversationTab, active: Boolean, unread: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        ),
    ) {
        Icon(
            imageVector = tab.glyph,
            contentDescription = tab.title,
            tint = if (active) ChatColors.onSurface else ChatColors.onSurfaceInactive,
            modifier = Modifier.size(ICON_DP.dp),
        )
        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Out past the glyph's own transparent margin so it reads as a
                    // badge rather than part of the shape.
                    .offset(x = 2.dp, y = 2.dp)
                    .size(7.dp)
                    .background(ChatColors.onSurface, CircleShape),
            )
        }
    }
}

/** Matches LightFog's `n(48)`, which resolves to 48dp at the LPIII's 2.55 density. */
private const val ICON_DP = 48

private val ConversationTab.glyph: ImageVector
    get() = when (this) {
        ConversationTab.Favorites -> StarVector
        ConversationTab.Known -> PersonVector
        ConversationTab.Unknown -> PersonOutlineVector
        ConversationTab.Dial -> PhoneIcon
    }

// Material `star`, `person` and `person_outline`. Filled vs outlined carries the
// Known/Unknown split without a second concept — the same filled-dot/ring language
// LightGlance uses for its notification glyphs.
private val StarVector: ImageVector by lazy {
    vector("M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
}
private val PersonVector: ImageVector by lazy {
    vector("M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z")
}
private val PersonOutlineVector: ImageVector by lazy {
    vector("M12 5.9c1.16 0 2.1.94 2.1 2.1s-.94 2.1-2.1 2.1S9.9 9.16 9.9 8s.94-2.1 2.1-2.1m0 9c2.97 0 6.1 1.46 6.1 2.1v1.1H5.9V17c0-.64 3.13-2.1 6.1-2.1M12 4C9.79 4 8 5.79 8 8s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 9c-2.67 0-8 1.34-8 4v3h16v-3c0-2.66-5.33-4-8-4z")
}

private fun vector(pathData: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.White),
    ).build()

/**
 * Which tab a conversation belongs to. One function rather than three filters, so
 * the three lists are exhaustive and disjoint by construction — a starred chat is
 * in Favorites and *only* Favorites, and nothing can appear twice or vanish.
 */
fun tabOf(conversation: Conversation, contacts: Contacts, favorites: Set<String>): ConversationTab =
    when {
        conversation.guid in favorites -> ConversationTab.Favorites
        contacts.knows(conversation) -> ConversationTab.Known
        else -> ConversationTab.Unknown
    }
