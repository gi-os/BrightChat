@file:OptIn(ExperimentalFoundationApi::class)

package com.gios.lightchat.ui

import android.Manifest
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightchat.Dialer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.text.DateFormat
import java.util.Date

/** Tappable text with a haptic tick on press — the vandamd "button". */
@Composable
fun HapticText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    underline: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = Int.MAX_VALUE,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
        modifier = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            onLongClick = onLongClick?.let {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it()
                }
            },
        ),
    )
}

/**
 * A tappable icon with the same haptic tick as [HapticText] — the icon-only counterpart used
 * where a header action (New, Call) used to be a text word. [contentDescription] carries the
 * accessibility label the visible glyph can no longer spell out on its own.
 */
@Composable
fun HapticIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            ),
    )
}

/** Top row shared by the sub-screens: a back chevron on the left and a centred
 *  title (the trailing spacer balances the chevron so the title sits centred).
 *  A non-null [onTitleClick] makes the title itself tappable (the thread uses
 *  this to open the chat's details). */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    /**
     * An action on the right, in place of the spacer that balances the back chevron.
     *
     * Optional and nullable rather than a default empty lambda, because the spacer is
     * load-bearing: it is what keeps the title on the centre line instead of being pushed off
     * it by an icon on one side only. A caller that supplies something is responsible for it
     * being about the same width, which for a short word it is.
     */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HapticText(
            text = "‹",
            style = ChatType.title,
            color = ChatColors.onSurface,
            onClick = onBack,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onTitleClick != null) {
            HapticText(
                text = title,
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                onClick = onTitleClick,
            )
        } else {
            Text(
                text = title,
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else Spacer(modifier = Modifier.width(24.dp))
    }
}

/**
 * A timestamp in a list, iMessage-style: a clock time for today ("3:14 PM"), "Yesterday",
 * the weekday within the last week ("Monday"), then a short date ("7/1/26"). Absolute past
 * a day — "18 hours ago" stops being parseable.
 *
 * Shared by the conversation list and the contact page's links, so the two cannot drift
 * into telling the time differently on the same screenful. Blank for an unknown date, which
 * callers must not concatenate a separator onto.
 */
internal fun listTime(context: Context, ts: Long): String {
    if (ts <= 0L) return ""
    if (DateUtils.isToday(ts)) return DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME)
    if (DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS)) return "Yesterday"
    if (System.currentTimeMillis() - ts < 7 * DateUtils.DAY_IN_MILLIS) {
        return DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_WEEKDAY)
    }
    return DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
}


/**
 * Ringing somebody, permission and all, as one function the caller just calls.
 *
 * Two screens offer a Call — the thread header for a one-to-one, and every name on the contact
 * page — and both need the same three-step dance: ask for `CALL_PHONE` if it isn't held, place
 * the call if it is, and open the dialer if the user says no. Written once here because the
 * failure mode of writing it twice is the two copies disagreeing about what a refusal means,
 * and a refusal is the branch nobody exercises.
 *
 * **The request happens on the tap, not on the screen opening.** A permission dialog that
 * appears because you looked at a conversation is a permission dialog that gets refused; one
 * that appears because you pressed Call explains itself. The number is held across the dialog
 * in [pending] because the launcher's result arrives on a later frame, by which time the tap
 * that knew who to ring is long gone.
 *
 * **A refusal is remembered by Android, not by us.** Refuse twice and the system stops showing
 * the dialog, and `launch` returns immediately with `false` — which lands in the same fallback
 * as a fresh refusal, so the Call verb keeps opening the dialer forever rather than becoming
 * an inert button. That is the reason the fallback exists at all.
 */
@Composable
fun rememberCaller(): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val number = pending
        pending = null
        if (number != null) {
            // Granted, so `ring` takes the ACTION_CALL path; refused, and it falls through to
            // the dialer on its own. Either way this is the same one call.
            if (granted) Dialer.ring(context, number) else Dialer.dial(context, number)
        }
    }
    return { number ->
        if (Dialer.canCallDirectly(context)) {
            Dialer.ring(context, number)
        } else {
            pending = number
            ask.launch(Manifest.permission.CALL_PHONE)
        }
    }
}


/**
 * How long an armed "Call?" stays armed before it disarms itself.
 *
 * The Remove verb beside it needs no timeout, because it lives in a list and is disarmed by
 * tapping anything else in that list. Call in the thread header has nothing beside it to tap,
 * so an armed confirm would sit there indefinitely — and a "Call?" left over from a minute ago
 * turns the next stray touch on the header into a call. Long enough to read the question and
 * decide, short enough that it is never still waiting when you come back to the thread.
 */
const val CALL_CONFIRM_MS = 4_000L
