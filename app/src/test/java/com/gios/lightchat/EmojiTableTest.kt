package com.gios.lightchat

import com.gios.lightchat.emoji.Emoji
import com.gios.lightchat.emoji.RecentEmoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiTableTest {

    private val emoji: Emoji = File("src/main/res/raw/emoji.bin").inputStream().use { Emoji.load(it) }

    @Test fun loadsTheWholeSet() {
        assertTrue(emoji.size > 1500)
        assertTrue(emoji.groups.isNotEmpty())
    }

    @Test fun searchFindsByNameAndKeyword() {
        assertEquals("🍕", emoji.glyph(emoji.search("pizza").first()))
        assertTrue(emoji.search("fire").map { emoji.glyph(it) }.contains("🔥"))
    }

    @Test fun recentsMoveToTheFront() {
        val r = RecentEmoji.EMPTY.used("🔥").used("😂").used("🔥")
        assertEquals(listOf("🔥", "😂"), r.entries)
    }
}
