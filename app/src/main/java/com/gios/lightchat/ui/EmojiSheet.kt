package com.gios.lightchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ColorMode
import com.gios.lightchat.emoji.Emoji
import com.gios.lightchat.emoji.EmojiRecents
import com.gios.lightchat.emoji.EmojiTable
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every emoji, for reacting to a Beeper message with something other than the six tapbacks.
 *
 * Search on top (CLDR keywords, so `hungry` finds 🍕), then Recent, then Unicode's own groups in
 * Unicode's order. The group row jumps the grid to a group rather than filtering it, so scrolling
 * still passes through everything. One tap picks: a reaction is cheap to take back, and the
 * picker's two-tap arm-then-send (the GIF picker's) would only slow the common case.
 *
 * Colour while open, the same hold the GIF and photo pickers take: an emoji chosen in greyscale is
 * half chosen. A no-op without the one-time WRITE_SECURE_SETTINGS grant.
 */
@Composable
fun EmojiSheet(onPick: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val loaded by produceState<Pair<Emoji, IntArray>?>(null) {
        value = withContext(Dispatchers.Default) { EmojiTable.get(context) }
    }
    val recents = remember { EmojiRecents.load(context).entries }
    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        onDispose { ColorMode.release(context) }
    }
    WheelScroll(gridState)

    val pick: (String) -> Unit = { glyph ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onPick(glyph)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HapticText(
                text = "‹",
                style = ChatType.title,
                color = ChatColors.onSurface,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Start,
                onClick = onClose,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "React", style = ChatType.body, color = ChatColors.onSurfaceVariant)
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(56.dp))
        }

        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(ChatColors.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search emoji",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDisabled,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                inner()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        val table = loaded
        if (table == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading…", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
            }
            return@Column
        }
        val (emoji, usable) = table
        val usableSet = remember(usable) { usable.toHashSet() }
        val searching = query.trim().length >= Emoji.MIN_QUERY
        val sections: List<Pair<String, List<String>>> = remember(emoji, usable, searching, query, recents) {
            if (searching) {
                listOf("" to emoji.search(query, limit = 64).filter { it in usableSet }.map { emoji.glyph(it) })
            } else {
                buildList {
                    if (recents.isNotEmpty()) add("Recent" to recents)
                    val byGroup = usable.groupBy { emoji.group(it) }
                    emoji.groups.forEachIndexed { g, name ->
                        byGroup[g]?.let { add(name to it.map { i -> emoji.glyph(i) }) }
                    }
                }
            }
        }

        // The group row: a jump, not a filter. First index of each section in the grid below,
        // counting one header cell per section.
        if (!searching) {
            val starts = remember(sections) {
                var at = 0
                sections.map { (name, items) -> (name to at).also { at += 1 + items.size } }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                starts.forEach { (name, index) ->
                    HapticText(
                        text = shortLabel(name),
                        style = ChatType.hint,
                        color = ChatColors.onSurfaceInactive,
                        onClick = { scope.launch { gridState.scrollToItem(index) } },
                    )
                }
            }
        }

        if (searching && sections.first().second.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nothing for “${query.trim()}”.", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(COLUMNS),
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
            ) {
                sections.forEach { (name, glyphs) ->
                    if (name.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h:$name", contentType = "header") {
                            Text(
                                text = name,
                                style = ChatType.hint,
                                color = ChatColors.onSurfaceDisabled,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                            )
                        }
                    }
                    // A glyph can appear in Recent and in its group; the section name keeps keys unique.
                    items(glyphs, key = { "$name:$it" }, contentType = { "emoji" }) { glyph ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { pick(glyph) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = glyph, style = GlyphStyle, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private const val COLUMNS = 7

private val GlyphStyle = TextStyle(fontSize = 26.sp, textAlign = TextAlign.Center)

/** Unicode's group names are too long for the jump row; these are one short word each. */
private fun shortLabel(group: String): String = when {
    group == "Recent" -> "Recent"
    group.startsWith("Smileys") -> "Faces"
    group.startsWith("People") -> "People"
    group.startsWith("Animals") -> "Nature"
    group.startsWith("Food") -> "Food"
    group.startsWith("Travel") -> "Travel"
    group.startsWith("Activities") -> "Play"
    group.startsWith("Objects") -> "Things"
    group.startsWith("Symbols") -> "Symbols"
    group.startsWith("Flags") -> "Flags"
    else -> group.substringBefore(' ')
}
