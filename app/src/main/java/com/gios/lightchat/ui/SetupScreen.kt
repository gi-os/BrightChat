package com.gios.lightchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatDimens
import com.gios.lightchat.ui.theme.ChatType

/** First launch: enter the BlueBubbles Server URL and password. Stored on the
 *  device only (the password encrypted at rest). */
@Composable
fun SetupScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var serverUrl by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSave = serverUrl.isNotBlank() && password.isNotBlank()

    // Hoisted out of the modifier so the wheel can reach it: setup is the one screen a
    // user meets with the keyboard already up, and the wheel is how you get to the field
    // the IME is covering without dismissing it.
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    // Scrollable so the focused password field scrolls clear of the keyboard —
    // on the Light Phone's short screen the IME otherwise covers it (BasicTextField
    // brings itself into view inside a verticalScroll; weighted spacers can't live
    // in one, so spacing here is fixed).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scroll)
            .padding(ChatDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(72.dp))
        Text(text = "chat", style = ChatType.title, color = ChatColors.onSurface)
        Spacer(modifier = Modifier.height(56.dp))
        Text(
            text = "Connect your server",
            style = ChatType.body,
            color = ChatColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your BlueBubbles Server address and password",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            if (serverUrl.isEmpty()) {
                Text(text = "your-server.example.com", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
                Spacer(modifier = Modifier.height(4.dp))
            }
            BasicTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                textStyle = ChatType.meta.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(ChatColors.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            if (password.isEmpty()) {
                Text(text = "password", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
                Spacer(modifier = Modifier.height(4.dp))
            }
            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                textStyle = ChatType.meta.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(ChatColors.onSurface),
                visualTransformation = if (password.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) viewModel.saveSetup(serverUrl, password) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        }

        state.message?.let {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = it,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(56.dp))
        HapticText(
            text = "Save",
            style = ChatType.button,
            color = if (!canSave) ChatColors.onSurfaceDisabled else ChatColors.onSurface,
            onClick = { if (canSave) viewModel.saveSetup(serverUrl, password) },
        )
        Spacer(modifier = Modifier.height(24.dp))

        // No Mac? Beeper alone is enough to use the app: WhatsApp, Signal and the rest, with
        // iMessage added later from Settings.
        var beeper by remember { mutableStateOf(false) }
        HapticText(
            text = if (beeper) "Hide Beeper" else "No Mac? Sign in with Beeper",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDim,
            onClick = { beeper = !beeper },
        )
        if (beeper) {
            Spacer(modifier = Modifier.height(16.dp))
            BeeperSection()
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
