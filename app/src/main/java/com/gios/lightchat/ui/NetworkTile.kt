package com.gios.lightchat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.PublicSans

/**
 * The small mark that says which network a chat is on: two letters in an outlined tile with one
 * sharp corner, the corner a speech bubble's tail would be.
 *
 * Letters, not logos. The panel is greyscale and a few millimetres tall at this size, a brand
 * mark would be a smudge, and the marks are not ours to draw. Two letters read at a glance and
 * never need a legend once seen next to a name.
 */
object NetworkMarks {
    private val CODES = mapOf(
        "iMessage" to "iM",
        "SMS" to "SMS",
        "WhatsApp" to "WA",
        "Signal" to "SG",
        "Telegram" to "TG",
        "Instagram" to "IG",
        "Messenger" to "MS",
        "Discord" to "DC",
        "Slack" to "SL",
        "LinkedIn" to "IN",
        "Twitter" to "TW",
        "Google Chat" to "GC",
        "Bluesky" to "BS",
        "Beeper" to "BE",
    )

    /** The tile's letters for a network name; the first two letters of an unknown one. */
    fun code(network: String): String =
        CODES[network] ?: network.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "?" }
}

private val TileShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomEnd = 4.dp, bottomStart = 1.dp)

private val TileText = TextStyle(
    fontFamily = PublicSans,
    fontWeight = FontWeight.Medium,
    fontSize = 9.sp,
    letterSpacing = 0.3.sp,
)

@Composable
private fun Tile(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            .border(1.3.dp, color, TileShape)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A network's mark. [dim] for an unselected choice (the thread's strip). */
@Composable
fun NetworkTile(network: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    val color = if (dim) ChatColors.onSurfaceDisabled else ChatColors.onSurface
    Tile(color, modifier) {
        Text(text = NetworkMarks.code(network), style = TileText, color = color, maxLines = 1)
    }
}

/** Phone calls: the tile with the app's one phone glyph in it. */
@Composable
fun CallsTile(modifier: Modifier = Modifier, dim: Boolean = false) {
    val color = if (dim) ChatColors.onSurfaceDisabled else ChatColors.onSurface
    Tile(color, modifier) {
        Icon(imageVector = PhoneIcon, contentDescription = "Calls", tint = color, modifier = Modifier.size(9.dp))
    }
}

/** Every network at once: two tiles, one behind the other. */
@Composable
fun AllTile(modifier: Modifier = Modifier, dim: Boolean = false) {
    val color = if (dim) ChatColors.onSurfaceDisabled else ChatColors.onSurface
    Box(modifier = modifier.size(width = 19.dp, height = 18.dp)) {
        Box(
            modifier = Modifier
                .offset(x = 4.dp, y = 0.dp)
                .size(width = 15.dp, height = 13.dp)
                .border(1.3.dp, color.copy(alpha = color.alpha * 0.6f), TileShape),
        )
        Box(
            modifier = Modifier
                .offset(x = 0.dp, y = 4.dp)
                .size(width = 15.dp, height = 13.dp)
                .border(1.3.dp, color, TileShape),
        )
    }
}
