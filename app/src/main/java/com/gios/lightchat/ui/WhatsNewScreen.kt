package com.gios.lightchat.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatDimens
import com.gios.lightchat.ui.theme.ChatType

/**
 * Shown once, on the first launch of a build that has it: what Beeper brought to BrightChat.
 *
 * Once per [EDITION], not once per install, so a later release with news of its own bumps the
 * edition and shows its page; and never on the very first run, where the setup screen already
 * offers Beeper and there is nothing yet to be "new" against.
 */
object WhatsNew {
    /** Bump to show the page again for a later release's news. */
    const val EDITION = "2.50-beeper"
    private const val PREFS = "whats_new"
    private const val KEY_SEEN = "seen"

    fun shouldShow(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, null) != EDITION

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, EDITION).apply()
    }
}

/**
 * The page. One column of short sections, the wheel scrolls it, and one choice at the bottom:
 * set up Beeper (straight to Settings), or carry on. With Beeper already signed in there is
 * nothing to set up, so the only button is Continue.
 */
@Composable
fun WhatsNewScreen(beeperOn: Boolean, onSetUpBeeper: () -> Unit, onDone: () -> Unit) {
    val scroll = rememberScrollState()
    WheelScroll(scroll)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ChatDimens.screenPadding)
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "What’s new", style = ChatType.title, color = ChatColors.onSurface)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "WhatsApp, Signal, Telegram, Instagram and more, beside iMessage.",
            style = ChatType.body,
            color = ChatColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("iMessage", "WhatsApp", "Signal", "Telegram", "Instagram", "Messenger").forEach { NetworkTile(it) }
        }

        Section(
            "Beeper",
            "Sign in with your Beeper account in Settings. Your other chats appear in the same list, " +
                "with a small mark that says where each one is. No Mac is needed for them.",
        )
        Section(
            "One row per person",
            "Someone you talk to on iMessage and WhatsApp is one row. In their thread, the strip at the " +
                "top shows All, each app, and Calls. The line above the keyboard says where your " +
                "next message goes; tap it to switch.",
        )
        Section(
            "Notifications",
            "Beeper messages alert like texts. A chat you muted in Beeper stays quiet here, and an " +
                "alert clears when you read the chat on another device.",
        )
        Section(
            "Any emoji",
            "Hold a Beeper message and tap + after the tapbacks to react with any emoji.",
        )
        Section(
            "Calls and voicemail",
            "The Dial tab lists recent calls, and Voicemail is one tap. The keypad opens when you ask for it.",
        )

        Spacer(modifier = Modifier.height(32.dp))
        if (!beeperOn) {
            HapticText(
                text = "Set up Beeper",
                style = ChatType.button,
                color = ChatColors.onSurface,
                onClick = onSetUpBeeper,
            )
            Spacer(modifier = Modifier.height(20.dp))
            HapticText(
                text = "Not now",
                style = ChatType.body,
                color = ChatColors.onSurfaceDim,
                onClick = onDone,
            )
        } else {
            HapticText(
                text = "Continue",
                style = ChatType.button,
                color = ChatColors.onSurface,
                onClick = onDone,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(text = title, style = ChatType.body, color = ChatColors.onSurface)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = body, style = ChatType.meta, color = ChatColors.onSurfaceVariant)
    }
}
