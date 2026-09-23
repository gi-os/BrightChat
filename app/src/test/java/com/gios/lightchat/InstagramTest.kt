package com.gios.lightchat

import com.gios.lightchat.links.Instagram
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramTest {

    @Test fun findsLinks() {
        val reel = Instagram.find("lol https://www.instagram.com/reel/C8CaBfWs1mr/?igsh=abc123 watch")!!
        assertEquals("C8CaBfWs1mr", reel.code)
        assertTrue(reel.isReel)
        assertEquals("https://www.instagram.com/reel/C8CaBfWs1mr/?igsh=abc123", reel.url)
        val post = Instagram.find("https://instagram.com/someone/p/DAbc_12-x/")!!
        assertEquals("DAbc_12-x", post.code)
        assertTrue(!post.isReel)
        assertNull(Instagram.find("https://www.instagram.com/someone/"))
        assertNull(Instagram.find("no link here"))
        assertEquals("https://www.instagram.com/reel/C8CaBfWs1mr/embed/captioned/", Instagram.embedUrl(reel))
    }

    /** The embed page carries the post as a JSON string inside a script, escaped twice over. */
    private fun page(media: JSONObject): String {
        val context = JSONObject().put("gql_data", JSONObject().put("shortcode_media", media)).toString()
        val script = JSONObject().put("isRichEmbed", true).put("contextJSON", context).toString()
        return "<html><script>require([[\"PolarisEmbedSimple\",\"init\",[],[$script]]])</script></html>"
    }

    @Test fun readsAReel() {
        val media = JSONObject()
            .put("is_video", true)
            .put("display_url", "https://cdn/x.jpg")
            .put("video_url", "https://cdn/x.mp4?a=1&b=2")
            .put("dimensions", JSONObject().put("width", 720).put("height", 1280))
            .put("owner", JSONObject().put("username", "mastercard"))
            .put("edge_media_to_caption", JSONObject().put("edges", JSONArray().put(JSONObject().put("node", JSONObject().put("text", "hi")))))
        val post = Instagram.parseEmbed("C8", page(media))!!
        assertEquals("mastercard", post.owner)
        assertEquals("hi", post.caption)
        assertEquals("https://cdn/x.mp4?a=1&b=2", post.items.single().videoUrl)
        assertEquals(720, post.items.single().width)
    }

    @Test fun readsACarousel() {
        val child = { url: String -> JSONObject().put("node", JSONObject().put("display_url", url).put("is_video", false)) }
        val media = JSONObject()
            .put("display_url", "https://cdn/cover.jpg")
            .put("edge_sidecar_to_children", JSONObject().put("edges", JSONArray().put(child("https://cdn/1.jpg")).put(child("https://cdn/2.jpg"))))
        val post = Instagram.parseEmbed("D1", page(media))!!
        assertEquals(listOf("https://cdn/1.jpg", "https://cdn/2.jpg"), post.items.map { it.imageUrl })
        assertTrue(post.items.none { it.isVideo })
    }

    @Test fun fallsBackToOpenGraph() {
        val html = "<meta property=\"og:image\" content=\"https://cdn/og.jpg?a=1&amp;b=2\" /><meta property=\"og:title\" content=\"Someone on Instagram: &quot;hey&quot;\" />"
        val post = Instagram.parseEmbed("E1", html)
        assertNotNull(post)
        assertEquals("https://cdn/og.jpg?a=1&b=2", post!!.items.single().imageUrl)
        assertEquals("Someone on Instagram: \"hey\"", post.caption)
        assertNull(Instagram.parseEmbed("E2", "<html></html>"))
    }
}
