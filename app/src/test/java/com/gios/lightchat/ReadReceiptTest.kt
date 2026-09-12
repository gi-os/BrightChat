package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A `chat-read-status-changed` may only clear an unread dot when the server actually
 * confirms the read. Everything else — an unreachable server, a chat whose newest rows
 * carry no `dateRead` to read — is [ReadVerdict.UNKNOWN], and unknown must leave the dot
 * where it is. The dot is written through to disk when it goes, so clearing one on a
 * failed fetch loses it for good.
 */
class ReadReceiptTest {

    private fun message(
        guid: String = "m",
        date: Long = 1_000L,
        fromMe: Boolean = false,
        dateRead: Long = 0L,
        itemType: Int = 0,
    ) = ChatMessage(
        guid = guid,
        text = "hi",
        date = date,
        fromMe = fromMe,
        sender = if (fromMe) null else "+15550001111",
        dateRead = dateRead,
        itemType = itemType,
    )

    @Test
    fun `a failed fetch decides nothing`() {
        assertEquals(ReadVerdict.UNKNOWN, ReadReceipt.verdict(null))
    }

    @Test
    fun `an empty answer decides nothing`() {
        assertEquals(ReadVerdict.UNKNOWN, ReadReceipt.verdict(emptyList()))
    }

    @Test
    fun `messages that are all mine decide nothing`() {
        val mine = (1..5).map { message(guid = "mine$it", date = it * 1_000L, fromMe = true) }
        assertEquals(ReadVerdict.UNKNOWN, ReadReceipt.verdict(mine))
    }

    @Test
    fun `group events decide nothing`() {
        val events = listOf(message(guid = "rename", itemType = 2))
        assertEquals(ReadVerdict.UNKNOWN, ReadReceipt.verdict(events))
    }

    @Test
    fun `an incoming message with no dateRead is still unread`() {
        assertEquals(ReadVerdict.UNREAD, ReadReceipt.verdict(listOf(message())))
    }

    @Test
    fun `an incoming message with a dateRead is read`() {
        assertEquals(ReadVerdict.READ, ReadReceipt.verdict(listOf(message(dateRead = 2_000L))))
    }

    @Test
    fun `the newest incoming message decides, not the first row`() {
        // DESC from the server, but the verdict is taken by date either way: an older
        // read message must not vouch for a newer unread one.
        val rows = listOf(
            message(guid = "new", date = 5_000L, dateRead = 0L),
            message(guid = "old", date = 1_000L, dateRead = 1_500L),
        )
        assertEquals(ReadVerdict.UNREAD, ReadReceipt.verdict(rows))
        assertEquals(ReadVerdict.UNREAD, ReadReceipt.verdict(rows.reversed()))
    }

    @Test
    fun `my newer reply does not hide their read message`() {
        val rows = listOf(
            message(guid = "mine", date = 9_000L, fromMe = true),
            message(guid = "theirs", date = 5_000L, dateRead = 6_000L),
        )
        assertEquals(ReadVerdict.READ, ReadReceipt.verdict(rows))
    }
}
