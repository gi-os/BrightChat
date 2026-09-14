package com.gios.lightchat.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gios.lightchat.api.Store

/**
 * The one switch behind every buzz this app makes on a tap.
 *
 * Nearly two hundred call sites tick the motor, through [HapticText], [HapticIcon] and a
 * handful of long-press handlers that reach for `LocalHapticFeedback` directly. Gating them
 * one at a time would mean touching every screen and would leave the next new button ungated
 * by default, which is the shape of bug that comes back.
 *
 * So the gate goes where they all already look. [GatedHaptics] wraps the real
 * [HapticFeedback] and is provided over `LocalHapticFeedback` inside
 * [com.gios.lightchat.ui.theme.LightChatTheme] — the one composable all three windows (the
 * app, the heads-up activity, the heads-up overlay) go through. Every existing call site and
 * every future one is covered without knowing this file exists.
 *
 * What this does **not** cover, on purpose: the buzz a new message makes (`HeadsUp`). That is
 * an alert, not feedback on something you just touched, and someone who wants a quiet keypad
 * still wants to know a text arrived. It stays where it belongs, under the phone's own
 * notification settings.
 */
object Haptics {

    /** Read once per process and then held, because it is consulted on every tap. */
    @Volatile private var cached: Boolean? = null

    fun enabled(context: Context): Boolean =
        cached ?: Store.haptics(context).also { cached = it }

    fun set(context: Context, value: Boolean) {
        Store.setHaptics(context, value)
        cached = value
    }
}

/**
 * The real haptic feedback with a switch in front of it.
 *
 * Delegation (`by real`) rather than a hand-written no-op: the interface can grow a member in
 * a Compose release and a delegating class keeps compiling, where an implementation from
 * scratch stops.
 */
class GatedHaptics(
    private val real: HapticFeedback,
    private val context: Context,
) : HapticFeedback by real {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (Haptics.enabled(context)) real.performHapticFeedback(hapticFeedbackType)
    }
}

/** Puts [GatedHaptics] in front of everything below. Called from the theme. */
@Composable
fun rememberGatedHaptics(): HapticFeedback {
    val real = LocalHapticFeedback.current
    val context = LocalContext.current
    return remember(real, context) { GatedHaptics(real, context) }
}
