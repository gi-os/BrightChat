package com.gios.lightchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightchat.beeper.BeeperEngine
import com.gios.lightchat.beeper.BeeperPush
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import kotlinx.coroutines.launch

/**
 * The Beeper account, on the Settings page and on the first-run screen.
 *
 * Three steps, each one line: an email address, the six-digit code Beeper sends to it, then the
 * account's recovery key. The last is what lets this phone read encrypted history; without it
 * new messages still arrive, but older ones say they can't be decrypted. Beta, and says so: the
 * log below is there so a failure can be read off the phone and sent back.
 */
@Composable
fun BeeperSection() {
    val status by BeeperEngine.status.collectAsState()
    val log by BeeperEngine.log.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            block().onFailure { error = it.message }
            busy = false
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (val s = status) {
            is BeeperEngine.Status.SignedOut, is BeeperEngine.Status.Failed -> {
                if (s is BeeperEngine.Status.Failed) Line(s.message)
                Line("WhatsApp, Signal, Telegram and more, through your Beeper account.")
                Spacer(modifier = Modifier.height(12.dp))
                Field(
                    hint = "Beeper email",
                    keyboard = KeyboardType.Email,
                    onDone = { email -> run { BeeperEngine.requestCode(email) } },
                )
            }
            is BeeperEngine.Status.CodeSent -> {
                Line("Beeper emailed a code to ${s.email}.")
                Spacer(modifier = Modifier.height(12.dp))
                Field(
                    hint = "Six-digit code",
                    keyboard = KeyboardType.Number,
                    onDone = { code -> run { BeeperEngine.signIn(code) } },
                )
                Spacer(modifier = Modifier.height(12.dp))
                HapticText(
                    text = "Use a different email",
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                    onClick = { run { BeeperEngine.signOut(); Result.success(Unit) } },
                )
            }
            is BeeperEngine.Status.Working -> Line("Signing in…")
            is BeeperEngine.Status.Ready -> {
                Line(s.userId.removePrefix("@").substringBefore(":"))
                Line("${s.rooms} chats · ${s.sync}")
                if (!s.verified) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Line("This phone isn’t verified yet, so older messages can’t be decrypted.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Field(
                        hint = "Recovery key",
                        keyboard = KeyboardType.Password,
                        onDone = { key -> run { BeeperEngine.verifyWithRecoveryKey(key) } },
                    )
                } else {
                    Line("Verified")
                }
                PushRows(act = { block -> run(block) })
                Spacer(modifier = Modifier.height(18.dp))
                HapticText(
                    text = "Sign out of Beeper",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDim,
                    onClick = { run { BeeperEngine.signOut(); Result.success(Unit) } },
                )
            }
        }
        if (busy) Line("Working…")
        error?.let { Line(it) }
        Spacer(modifier = Modifier.height(14.dp))
        HapticText(
            text = if (showLog) "Hide log" else "Show log",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            onClick = { showLog = !showLog },
        )
        HapticText(
            text = "Send log",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            onClick = { run { BeeperEngine.sendLogNow(); Result.success(Unit) } },
        )
        if (showLog) {
            Text(
                text = log.takeLast(40).joinToString("\n").ifEmpty { "Nothing yet." },
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/**
 * Push status and the server it goes through. ntfy.sh by default; a self-hosted ntfy is one line
 * to type. Re-read every few seconds, since the stream's state changes with no event the UI hears.
 */
@Composable
private fun PushRows(act: (suspend () -> Result<Unit>) -> Unit) {
    val context = LocalContext.current
    var line by remember { mutableStateOf(BeeperEngine.pushLine(context)) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            line = BeeperEngine.pushLine(context)
            delay(5_000)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Line(line)
    HapticText(
        text = if (editing) "Cancel" else "Change push server",
        style = ChatType.hint,
        color = ChatColors.onSurfaceDisabled,
        onClick = { editing = !editing },
    )
    if (editing) {
        Spacer(modifier = Modifier.height(8.dp))
        Field(
            hint = "ntfy server, e.g. push.example.com",
            keyboard = KeyboardType.Uri,
            onDone = { server ->
                editing = false
                act { BeeperEngine.setPushServer(context, server) }
            },
        )
        if (BeeperPush.server(context) != BeeperPush.DEFAULT_SERVER) {
            HapticText(
                text = "Use ntfy.sh",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                onClick = {
                    editing = false
                    act { BeeperEngine.setPushServer(context, BeeperPush.DEFAULT_SERVER) }
                },
            )
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(
        text = text,
        style = ChatType.hint,
        color = ChatColors.onSurfaceDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** One line to type into, committed on Done, then cleared. */
@Composable
private fun Field(hint: String, keyboard: KeyboardType, onDone: (String) -> Unit) {
    var draft by remember(hint) { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(ChatColors.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val value = draft.trim()
                if (value.isNotEmpty()) {
                    onDone(value)
                    draft = ""
                }
            }),
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
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
    }
}
