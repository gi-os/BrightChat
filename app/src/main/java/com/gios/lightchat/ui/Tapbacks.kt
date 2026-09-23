@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.gios.lightchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightchat.Reaction
import com.gios.lightchat.ReactionType
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.PublicSans
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * Tapback rendering — monochrome and dependency-free, matching the house style.
 * Public Sans has no heart/thumb glyphs (they'd fall back to a different font), so
 * the three iconic tapbacks are drawn as vector paths and tinted white, while the
 * three text ones (HA / ‼ / ?) render in Public Sans — exactly iMessage's own
 * icon+text split. Used by both the badge on a message and the long-press picker.
 */

private const val DEFAULT_GLYPH_DP = 17

/** Material's filled favorite / thumb paths, parsed once. `Icon` re-tints them, so
 *  the baked fill colour is irrelevant — we just need the geometry. */
private val HeartVector: ImageVector by lazy {
    vector("M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 " +
        "3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 " +
        "6.86-8.55 11.54L12 21.35z")
}
private val ThumbUpVector: ImageVector by lazy {
    vector("M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06" +
        "L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05" +
        "c.09-.23.14-.47.14-.73v-2z")
}
private val ThumbDownVector: ImageVector by lazy {
    vector("M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31" +
        "l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2z" +
        "m4 0v12h4V3h-4z")
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
 * One tapback mark in [color] at [size]. Heart/thumbs are drawn vectors; laugh,
 * emphasize, and question are Public Sans text sized to sit level with the icons.
 */
@Composable
fun TapbackGlyph(type: ReactionType, color: Color, size: Dp = DEFAULT_GLYPH_DP.dp, emoji: String? = null) {
    when (type) {
        // The emoji itself, in the phone's emoji font. Not tinted: the panel is greyscale anyway,
        // and a tinted emoji is a silhouette.
        ReactionType.EMOJI -> EmojiGlyph(emoji ?: "?", size)
        ReactionType.LOVE -> Icon(HeartVector, contentDescription = "love", tint = color, modifier = Modifier.size(size))
        ReactionType.LIKE -> Icon(ThumbUpVector, contentDescription = "like", tint = color, modifier = Modifier.size(size))
        ReactionType.DISLIKE -> Icon(ThumbDownVector, contentDescription = "dislike", tint = color, modifier = Modifier.size(size))
        ReactionType.LAUGH -> GlyphText("haha", color, size)
        ReactionType.EMPHASIZE -> GlyphText("!!", color, size)
        ReactionType.QUESTION -> GlyphText("?", color, size)
    }
}

@Composable
private fun EmojiGlyph(emoji: String, size: Dp) {
    Box(modifier = Modifier.height(size), contentAlignment = Alignment.Center) {
        Text(
            text = emoji,
            style = TextStyle(fontSize = (size.value * 0.9f).sp, textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}

/** A "+" in the picker's row: every other emoji, one tap away. */
@Composable
fun MoreEmojiGlyph(color: Color, size: Dp) {
    GlyphText("+", color, size)
}

/** A text tapback (HA / !! / ?) matched to the icon's height (width free, so two
 *  characters like "HA" aren't clipped) so a row of mixed marks lines up. Sized in
 *  dp→sp so it tracks [size] rather than the font scale. */
@Composable
private fun GlyphText(text: String, color: Color, size: Dp) {
    Box(modifier = Modifier.height(size), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = PublicSans,
                // Extra-bold so a text tapback (!! / haha / ?) carries the same visual
                // weight as the solid filled glyphs beside it.
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.85f).sp,
                textAlign = TextAlign.Center,
            ),
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * The tapbacks for a message, rendered in the empty gutter beside it with an ASCII
 * arrow pointing back at the turn — leaning into the text-terminal feel. Received
 * messages (gutter on the right) read `<- ♥`; your messages (gutter on the left)
 * read `♥ ->`.
 *
 * In a group several people can react, so same-type tapbacks collapse to one glyph
 * with a count (`♥3`); a type renders full white if you're among its reactors, 70%
 * otherwise. The row wraps within the gutter when many distinct types pile up.
 */
@Composable
fun GutterReactions(reactions: List<Reaction>, fromMe: Boolean, modifier: Modifier = Modifier) {
    val groups = remember(reactions) {
        reactions.groupBy { it.type to it.emoji }
            .map { (key, rs) -> ReactionTally(key.first, rs.size, rs.any { it.fromMe }, key.second) }
            .sortedBy { it.type.ordinal }
    }
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // FlowRow top-aligns items within a line, so each child is explicitly
        // centered on the cross axis to sit level with the (taller) glyphs.
        if (!fromMe) Arrow(Modifier.align(Alignment.CenterVertically), pointingLeft = true)
        groups.forEach { g ->
            val color = if (g.mine) ChatColors.onSurface else ChatColors.onSurfaceVariant
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                TapbackGlyph(type = g.type, color = color, emoji = g.emoji)
                if (g.count > 1) {
                    Text(
                        text = g.count.toString(),
                        style = TextStyle(fontFamily = PublicSans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
        if (fromMe) Arrow(Modifier.align(Alignment.CenterVertically), pointingLeft = false)
    }
}

private data class ReactionTally(val type: ReactionType, val count: Int, val mine: Boolean, val emoji: String? = null)

/** A small drawn arrow pointing back at the turn — same vector treatment as the
 *  glyphs, so it's crisp and centers cleanly (the ASCII `<-`/`->` rode high off the
 *  text mid-line and read as two broken characters). Dim, so the reaction is the
 *  subject and the arrow just the connector. */
@Composable
private fun Arrow(modifier: Modifier, pointingLeft: Boolean) {
    Canvas(modifier = modifier.size(width = 15.dp, height = DEFAULT_GLYPH_DP.dp)) {
        val midY = size.height / 2f
        val stroke = 1.6.dp.toPx()
        val head = size.height * 0.26f
        val tip = if (pointingLeft) 0f else size.width
        val tail = if (pointingLeft) size.width else 0f
        val inX = if (pointingLeft) head else size.width - head
        drawLine(ChatColors.onSurfaceDim, Offset(tail, midY), Offset(tip, midY), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ChatColors.onSurfaceDim, Offset(tip, midY), Offset(inX, midY - head), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ChatColors.onSurfaceDim, Offset(tip, midY), Offset(inX, midY + head), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
