package com.gios.lightchat.links

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Instagram posts and reels, shown in the thread instead of a bare link.
 *
 * Friends share a reel as a link, whether it comes through Beeper's Instagram bridge or pasted into
 * an iMessage. The phone has no Instagram app and no account to spare, so the post is read from
 * Instagram's public **embed page** (`/p/<code>/embed/captioned/`), the one other websites put in
 * an iframe. For a public post it carries the post's data as JSON (`contextJSON`): the owner, the
 * caption, the image or images, and for a video a direct `video_url` on Instagram's CDN, which
 * plays without signing in. For anything the embed will not describe (a private account, a
 * removed post) the page's `og:image` and `og:title` still give a picture and a line.
 *
 * Two things matter about the embed page:
 *
 * - **It needs a desktop browser's User-Agent.** Sent a phone's, it answers with the 600 KB app
 *   shell and no post data at all.
 * - **Its media URLs are signed and expire** after a day or so. So nothing here keeps a URL; it
 *   keeps the downloaded files, and a post whose files are gone is simply read again.
 *
 * Nothing is fetched until a message with a link is on screen.
 */
object Instagram {

    /** `instagram.com/p/…`, `/reel/…`, `/reels/…`, `/tv/…`, with or without a username in front. */
    private val LINK = Regex(
        "https?://(?:www\\.|m\\.)?(?:instagram\\.com|instagr\\.am)/(?:[A-Za-z0-9_.]+/)?(p|reels?|tv)/([A-Za-z0-9_-]{5,})[^\\s]*",
        RegexOption.IGNORE_CASE,
    )

    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"

    data class Link(val url: String, val kind: String, val code: String) {
        val isReel: Boolean get() = kind.startsWith("reel", ignoreCase = true) || kind.equals("tv", true)
    }

    data class Item(val imageUrl: String, val videoUrl: String?, val width: Int, val height: Int) {
        val isVideo: Boolean get() = videoUrl != null
    }

    data class Post(val code: String, val owner: String?, val caption: String?, val items: List<Item>)

    /** The first Instagram link in [text], if any. */
    fun find(text: String?): Link? {
        if (text.isNullOrBlank() || !text.contains("instagr", ignoreCase = true)) return null
        val m = LINK.find(text) ?: return null
        return Link(url = m.value, kind = m.groupValues[1].lowercase(), code = m.groupValues[2])
    }

    fun embedUrl(link: Link): String =
        "https://www.instagram.com/${if (link.isReel) "reel" else "p"}/${link.code}/embed/captioned/"

    /**
     * The post an embed page describes. Pure, so it is tested on a saved page; null when the page
     * carries neither the post JSON nor an `og:image`.
     */
    fun parseEmbed(code: String, html: String): Post? {
        parseContext(code, html)?.let { return it }
        val image = meta(html, "og:image") ?: return null
        return Post(code, owner = null, caption = meta(html, "og:title"), items = listOf(Item(image, null, 0, 0)))
    }

    private fun parseContext(code: String, html: String): Post? {
        val key = "\"contextJSON\":"
        val at = html.indexOf(key).takeIf { it >= 0 } ?: return null
        val inner = runCatching { JSONTokener(html.substring(at + key.length)).nextValue() as? String }.getOrNull() ?: return null
        val media = runCatching { JSONObject(inner).optJSONObject("gql_data")?.optJSONObject("shortcode_media") }
            .getOrNull() ?: return null
        val items = ArrayList<Item>()
        val children = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (children != null && children.length() > 0) {
            for (i in 0 until children.length()) {
                children.optJSONObject(i)?.optJSONObject("node")?.let { itemOf(it) }?.let(items::add)
            }
        } else {
            itemOf(media)?.let(items::add)
        }
        if (items.isEmpty()) return null
        val caption = media.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
            ?.optJSONObject(0)?.optJSONObject("node")?.optString("text")?.takeIf { it.isNotBlank() }
        val owner = media.optJSONObject("owner")?.optString("username")?.takeIf { it.isNotBlank() }
        return Post(code, owner, caption, items)
    }

    private fun itemOf(node: JSONObject): Item? {
        val image = node.optString("display_url").takeIf { it.startsWith("http") } ?: return null
        val video = if (node.optBoolean("is_video")) node.optString("video_url").takeIf { it.startsWith("http") } else null
        val dims = node.optJSONObject("dimensions")
        return Item(image, video, dims?.optInt("width") ?: 0, dims?.optInt("height") ?: 0)
    }

    /** One `<meta property="og:…" content="…">`, HTML entities undone. */
    private fun meta(html: String, property: String): String? {
        val m = Regex("<meta[^>]+property=\"${Regex.escape(property)}\"[^>]+content=\"([^\"]*)\"").find(html)
            ?: Regex("<meta[^>]+content=\"([^\"]*)\"[^>]+property=\"${Regex.escape(property)}\"").find(html)
            ?: return null
        return m.groupValues[1]
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#x27;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ fetching

    private val posts = ConcurrentHashMap<String, Post>()

    /** The post behind [link], read from the embed page. Blocking; call off the main thread. */
    fun post(link: Link, refresh: Boolean = false): Post? {
        if (!refresh) posts[link.code]?.let { return it }
        val html = get(embedUrl(link))?.toString(Charsets.UTF_8) ?: return null
        return parseEmbed(link.code, html)?.also { posts[link.code] = it }
    }

    private fun dir(context: Context) = File(context.cacheDir, "instagram").apply { mkdirs() }

    /** Item [index]'s picture, downloaded once, scaled to about [maxWidth] pixels. */
    fun image(context: Context, link: Link, index: Int, maxWidth: Int = 720): Bitmap? {
        val file = File(dir(context), "${link.code}_$index.jpg")
        if (!file.exists() || file.length() == 0L) {
            val bytes = fetchMedia(link, index) { it.imageUrl } ?: return null
            file.writeBytes(bytes)
        }
        return decode(file, maxWidth)
    }

    /** Item [index]'s video, downloaded once. Null for a picture, or when it can't be fetched. */
    fun video(context: Context, link: Link, index: Int): File? {
        val file = File(dir(context), "${link.code}_$index.mp4")
        if (file.exists() && file.length() > 0L) return file
        val bytes = fetchMedia(link, index) { it.videoUrl } ?: return null
        val tmp = File(file.path + ".part")
        tmp.writeBytes(bytes)
        return if (tmp.renameTo(file)) file else null
    }

    /** A media URL from the post, re-reading the embed once if the signed URL has gone stale. */
    private fun fetchMedia(link: Link, index: Int, pick: (Item) -> String?): ByteArray? {
        for (refresh in listOf(false, true)) {
            val url = post(link, refresh)?.items?.getOrNull(index)?.let(pick) ?: return null
            get(url, maxBytes = MAX_MEDIA_BYTES)?.let { return it }
        }
        return null
    }

    private fun decode(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Reels run a few MB; anything past this is not a reel. */
    private const val MAX_MEDIA_BYTES = 60L * 1024 * 1024

    private fun get(url: String, maxBytes: Long = 4L * 1024 * 1024): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            if (conn.contentLengthLong > maxBytes) return@runCatching null
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return@runCatching null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
