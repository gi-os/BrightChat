package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a saved attachment goes.
 *
 * Worth a test rather than a phone because every input here is somebody else's: iMessage chooses
 * the transfer name and the mime type, and both arrive missing, blank, or full of characters a
 * shared folder will not take.
 */
class SaveToTest {

    @Test
    fun `pictures, videos and everything else are told apart by mime`() {
        assertEquals(SaveTo.Collection.IMAGES, SaveTo.of("image/jpeg", "a.jpg", "g").collection)
        assertEquals(SaveTo.Collection.VIDEO, SaveTo.of("video/quicktime", "a.mov", "g").collection)
        assertEquals(SaveTo.Collection.DOWNLOADS, SaveTo.of("application/pdf", "a.pdf", "g").collection)
        assertEquals(SaveTo.Collection.DOWNLOADS, SaveTo.of(null, null, "g").collection)
    }

    @Test
    fun `the folder is the same one every time`() {
        assertEquals("Pictures/BrightChat", SaveTo.of("image/png", "a", "g").relativePath)
        assertEquals("Movies/BrightChat", SaveTo.of("video/mp4", "a", "g").relativePath)
        assertEquals("Download/BrightChat", SaveTo.of("text/plain", "a", "g").relativePath)
    }

    /** A transfer name is somebody else's string and is going into a shared folder. */
    @Test
    fun `a name is made safe for a filesystem`() {
        assertEquals("holiday_2024.jpg", SaveTo.nameFor("image/jpeg", "holiday 2024.jpg", "g"))
        assertEquals("a_b_c.png", SaveTo.nameFor("image/png", "a/b\\c.png", "g"))
        assertTrue(SaveTo.nameFor("image/jpeg", "🎉🎉🎉", "guid-1").endsWith(".jpg"))
    }

    @Test
    fun `a missing name falls back to the guid, and never to nothing`() {
        assertEquals("guid-1.jpg", SaveTo.nameFor("image/jpeg", null, "guid-1"))
        assertEquals("guid-1.jpg", SaveTo.nameFor("image/jpeg", "   ", "guid-1"))
        assertEquals("attachment.jpg", SaveTo.nameFor("image/jpeg", "...", ""))
    }

    /** A file with no suffix is opened by nothing, so one is added from the mime type. */
    @Test
    fun `an extension is added when there is none, and kept when there is`() {
        assertEquals("IMG_0042.jpg", SaveTo.nameFor("image/jpeg", "IMG_0042", "g"))
        assertEquals("IMG_0042.heic", SaveTo.nameFor("image/heic", "IMG_0042.heic", "g"))
        // Not an extension: too long to be one, so it keeps the name and adds a real one.
        assertEquals("notes.something.mp4", SaveTo.nameFor("video/mp4", "notes.something", "g"))
    }

    /** A wrong extension is worse than an uninformative one — it opens with the wrong app. */
    @Test
    fun `an unknown type gets bin rather than a guess`() {
        assertEquals("bin", SaveTo.extensionFor("application/x-who-knows"))
        assertEquals("bin", SaveTo.extensionFor(null))
        assertEquals("mov", SaveTo.extensionFor("VIDEO/QUICKTIME"))
    }

    @Test
    fun `the message names the folder to go looking in`() {
        assertTrue("Pictures" in SaveTo.message(SaveTo.of("image/png", "a", "g")))
        assertTrue("Download" in SaveTo.message(SaveTo.of("application/zip", "a", "g")))
    }

    @Test
    fun `a blank mime still yields something openable`() {
        val t = SaveTo.of("", "a", "g")
        assertEquals("application/octet-stream", t.mimeType)
    }
}
