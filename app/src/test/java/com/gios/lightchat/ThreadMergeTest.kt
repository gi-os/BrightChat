package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One row per guid in the open thread, whatever order the send's three answers arrive in
 * (HTTP return, socket `new-message`, socket `updated-message`). Two rows under one guid
 * is not a cosmetic double — the thread's LazyColumn throws on it.
 */
class ThreadMergeTest {

    private fun message(
        guid: String,
        text: String = "hi",
        date: Long = 1_000L,
        tempGuid: String? = null,
        dateDelivered: Long = 0L,
        dateEdited: Long = 0L,
    ) = ChatMessage(
        guid = guid,
        text = text,
        date = date,
        fromMe = true,
        sender = null,
        tempGuid = tempGuid,
        dateDelivered = dateDelivered,
        dateEdited = dateEdited,
    )

    @Test
    fun `a receipt that predates an edit keeps the edited words`() {
        val edited = listOf(message("g1", text = "see you at 7", dateEdited = 5_000L))
        val lateReceipt = message("g1", text = "see you at 6", dateDelivered = 6_000L)
        val out = mergeIntoThread(edited, lateReceipt)
        assertEquals(1, out.size)
        assertEquals("see you at 7", out[0].text)
        assertEquals(5_000L, out[0].dateEdited)
        assertEquals(6_000L, out[0].dateDelivered)
    }

    @Test
    fun `a newer edit from the Mac replaces the words`() {
        val edited = listOf(message("g1", text = "see you at 7", dateEdited = 5_000L))
        val again = message("g1", text = "see you at 8", dateEdited = 9_000L)
        assertEquals("see you at 8", mergeIntoThread(edited, again)[0].text)
    }

    @Test
    fun `an unseen message is appended`() {
        val list = listOf(message("A"))
        val out = mergeIntoThread(list, message("B"))
        assertEquals(listOf("A", "B"), out.map { it.guid })
    }

    @Test
    fun `the echo replaces the optimistic row in place`() {
        val list = listOf(message("A"), message("temp-1-2", text = "[Photo]"))
        val out = mergeIntoThread(list, message("REAL", tempGuid = "temp-1-2"))
        assertEquals(listOf("A", "REAL"), out.map { it.guid })
    }

    @Test
    fun `an update to a message already held replaces it, once`() {
        val list = listOf(message("A"), message("REAL"))
        val out = mergeIntoThread(list, message("REAL", dateDelivered = 5L))
        assertEquals(listOf("A", "REAL"), out.map { it.guid })
        assertEquals(5L, out.last().dateDelivered)
    }

    /**
     * light-reports#21. Sending several photos: the socket echo appended the real row while
     * the blocking upload was still running, so the optimistic row and the real row were both
     * in the list. The delivery update then matched the optimistic row by tempGuid and wrote
     * the real message over it — two rows, one guid, and the thread died on the next frame.
     */
    @Test
    fun `an update matching by tempGuid collapses the row the echo already appended`() {
        val list = listOf(
            message("A"),
            message("temp-1-2", text = "[Photo]"),
            message("REAL", tempGuid = "temp-1-2"),
        )
        val out = mergeIntoThread(list, message("REAL", tempGuid = "temp-1-2", dateDelivered = 5L))
        assertEquals(listOf("A", "REAL"), out.map { it.guid })
        assertEquals(out.map { it.guid }.distinct(), out.map { it.guid })
        assertEquals(5L, out.last().dateDelivered)
    }

    /**
     * light-reports#22. A fetch lands while photos are still uploading — opening the thread, the
     * delta sync, or (most easily) scrolling up to read while you wait, which fires `loadOlder`.
     * The fetched page comes from the store and cannot know about a send the server has not
     * acknowledged, so assigning it straight over the thread deleted every optimistic row: the
     * bubbles vanished, and the send's own reconcile then had no row left to swap the real
     * message into.
     */
    @Test
    fun `a landing fetch keeps sends that are still in flight`() {
        val current = listOf(message("OLD"), message("temp-1-2", text = "[Photo]"))
        val fetched = listOf(message("OLD"), message("NEWER"))
        val out = replaceKeepingPending(current, fetched)
        assertEquals(listOf("OLD", "NEWER", "temp-1-2"), out.map { it.guid })
    }

    /** …but not once the real message is in the page, or the same photo would sit in the
     *  thread twice under two different guids. The server echoes our tempGuid back on it. */
    @Test
    fun `a pending row the fetch has already acknowledged is dropped`() {
        val current = listOf(message("temp-1-2", text = "[Photo]"), message("temp-3-4"))
        val fetched = listOf(message("REAL", tempGuid = "temp-1-2"))
        val out = replaceKeepingPending(current, fetched)
        assertEquals(listOf("REAL", "temp-3-4"), out.map { it.guid })
    }

    @Test
    fun `a fetch with nothing in flight is taken as-is`() {
        val fetched = listOf(message("A"), message("B"))
        assertEquals(fetched, replaceKeepingPending(listOf(message("A")), fetched))
        assertEquals(fetched, replaceKeepingPending(emptyList(), fetched))
    }

    /** A pending row that is somehow also in the page by its own guid isn't duplicated. */
    @Test
    fun `replacement never repeats a guid`() {
        val current = listOf(message("temp-1-2"))
        val out = replaceKeepingPending(current, listOf(message("temp-1-2"), message("X")))
        assertEquals(out.map { it.guid }.distinct(), out.map { it.guid })
    }

    @Test
    fun `several photos in flight keep one row each`() {
        var list = listOf<ChatMessage>()
        // Three optimistic rows, then each one's echo and delivery update, interleaved.
        for (i in 1..3) list = mergeIntoThread(list, message("temp-$i", text = "[Photo]"))
        for (i in 1..3) list = mergeIntoThread(list, message("G$i", tempGuid = "temp-$i"))
        for (i in 1..3) list = mergeIntoThread(list, message("G$i", tempGuid = "temp-$i", dateDelivered = 9L))
        assertEquals(listOf("G1", "G2", "G3"), list.map { it.guid })
    }
}
