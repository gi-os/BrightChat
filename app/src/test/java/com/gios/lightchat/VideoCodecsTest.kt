package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecsTest {

    @Test
    fun `names the codecs an iPhone sends`() {
        assertEquals("H.264", VideoCodecs.name("video/avc"))
        assertEquals("HEVC", VideoCodecs.name("video/hevc"))
        assertEquals("Dolby Vision", VideoCodecs.name("video/dolby-vision"))
    }

    @Test
    fun `HDR is the transfer function, not the codec`() {
        assertFalse(VideoCodecs.isHdr("video/hevc", null))
        assertTrue(VideoCodecs.isHdr("video/hevc", VideoCodecs.TRANSFER_HLG))
        assertTrue(VideoCodecs.isHdr("video/hevc", VideoCodecs.TRANSFER_PQ))
        assertTrue(VideoCodecs.isHdr("video/dolby-vision", null))
    }

    @Test
    fun `the message names the codec and offers the iPhone fix`() {
        val hevc = VideoCodecs.cantPlay("video/hevc", VideoCodecs.TRANSFER_HLG)
        assertTrue(hevc, hevc.startsWith("This video is HEVC HDR,"))
        assertTrue(hevc.contains("Most Compatible"))
        // "Dolby Vision HDR" says HDR twice.
        assertTrue(VideoCodecs.cantPlay("video/dolby-vision", null).startsWith("This video is Dolby Vision,"))
    }

    @Test
    fun `H264 gets no iPhone tip, since that setting is what made it H264`() {
        assertFalse(VideoCodecs.cantPlay("video/avc", null).contains("Most Compatible"))
    }

    @Test
    fun `no video track falls back to the plain line`() {
        assertEquals("This video won't play here", VideoCodecs.cantPlay(null, null))
    }
}
