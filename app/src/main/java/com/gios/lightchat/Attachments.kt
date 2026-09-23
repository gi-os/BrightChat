package com.gios.lightchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gios.lightchat.api.BlueBubblesApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Inline-image loader for message attachments — deliberately dependency-free (no
 * Coil/Glide), matching the rest of the app: [BlueBubblesApi.downloadAttachment]
 * streams the bytes over the same single Tailscale socket, we cache the raw file
 * under [Context.getCacheDir] so reopening a thread doesn't refetch, and decode a
 * downsampled [android.graphics.Bitmap] so a full-res photo can't OOM the phone.
 * A small in-memory [LruCache] keyed by attachment guid keeps scrolling smooth.
 */
object Attachments {
    /** Cap the decoded bitmap's longest edge — the Light Phone's screen is small,
     *  and downsampling at decode time is what keeps memory in check. */
    private const val MAX_DIM = 1080

    /**
     * The same for a grid thumbnail.
     *
     * The contact page shows fifteen to eighteen cells at once, each about 120px wide. At
     * [MAX_DIM] every one of those is a four-megabyte bitmap, which is both more memory
     * than this phone has to spare and more than the cache below can hold — so the grid
     * would evict and re-decode continuously while it scrolled.
     */
    private const val THUMB_DIM = 256

    /** Decoded images held in memory; bitmaps are already downsampled, so a modest
     *  count keeps the working set tiny while smoothing re-scroll. */
    private val memory = object : LruCache<String, ImageBitmap>(16) {}

    /** Thumbnails, cached separately: they are ~18x smaller, so many more fit, and a
     *  grid cell must never evict the full-size photo the viewer is showing. */
    private val thumbnails = object : LruCache<String, ImageBitmap>(96) {}

    /**
     * At most this many downloads at once.
     *
     * Every visible grid cell asks for its own image on the frame it composes, and they
     * all go down one Tailscale tunnel to a Mac. Unbounded, opening the contact page on a
     * photo-heavy conversation fires twenty simultaneous requests and none of them
     * finishes quickly.
     */
    private val downloads = Semaphore(4)

    /**
     * The decoded image for [attachment], or null if it isn't an image, the
     * download fails, or the bytes don't decode. Runs entirely off the main thread.
     */
    suspend fun image(context: Context, api: BlueBubblesApi?, attachment: Attachment): ImageBitmap? =
        load(context, api, attachment, MAX_DIM, memory)

    /** As [image], but decoded small for the contact page's grid. Shares the cached file
     *  on disk, so a photo opened full-screen afterwards does not download again. */
    suspend fun thumbnail(context: Context, api: BlueBubblesApi?, attachment: Attachment): ImageBitmap? =
        load(context, api, attachment, THUMB_DIM, thumbnails)

    /**
     * The cached local file for [attachment], downloading it on first use — no decoding.
     *
     * Split out of [load] for the one attachment this app draws without decoding it to a bitmap:
     * an **animated GIF**, which [android.graphics.BitmapFactory] flattens to its first frame and
     * which [com.gios.lightchat.ui.rememberGifPainter] plays from the file instead. Same cache
     * entry as the still path, so a GIF opened full-screen after being seen in the thread is not
     * fetched twice — and so an outgoing GIF renders from the bytes [cacheLocal] seeded, exactly
     * as an outgoing photograph does.
     */
    suspend fun file(context: Context, api: BlueBubblesApi?, attachment: Attachment): File? =
        withContext(Dispatchers.IO) { fetch(context, api, attachment) }

    private suspend fun load(
        context: Context,
        api: BlueBubblesApi?,
        attachment: Attachment,
        maxDim: Int,
        cache: LruCache<String, ImageBitmap>,
    ): ImageBitmap? {
        if (!attachment.isImage) return null
        cache.get(attachment.guid)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = fetch(context, api, attachment) ?: return@withContext null
            val image = decode(file, maxDim)?.asImageBitmap() ?: return@withContext null
            cache.put(attachment.guid, image)
            image
        }
    }

    /** The bytes on disk, downloaded if they aren't there yet. Caller is on IO. */
    private suspend fun fetch(context: Context, api: BlueBubblesApi?, attachment: Attachment): File? {
        val file = File(context.cacheDir, "att_" + safeName(attachment.guid))
        if (file.exists() && file.length() > 0L) return file
        val ok = downloads.withPermit {
            // Re-checked inside the permit: a full-size and a thumbnail request for
            // the same photo queue together, and the second would otherwise
            // re-download over a file the first had just written.
            if (file.exists() && file.length() > 0L) {
                true
            } else {
                runCatching {
                    // A Beeper attachment is fetched (and decrypted) through the Matrix client;
                    // everything else is a BlueBubbles download from the Mac.
                    if (com.gios.lightchat.beeper.BeeperMapping.isBeeperAttachment(attachment.guid)) {
                        check(com.gios.lightchat.beeper.BeeperEngine.download(attachment.guid, file)) { "Beeper download failed" }
                    } else {
                        checkNotNull(api) { "Not connected" }.downloadAttachment(attachment.guid, file)
                    }
                }
                    .onFailure { file.delete() } // never trust a truncated file later
                    .isSuccess
            }
        }
        return file.takeIf { ok }
    }

    /**
     * Seeds the cache with a locally-picked image's bytes under [guid], so an
     * optimistic outgoing message can render it through the normal [image] path
     * (which then finds the file and skips the download). Keyed by the send's temp
     * guid; the real server attachment downloads separately once the echo lands.
     */
    fun cacheLocal(context: Context, guid: String, bytes: ByteArray) {
        runCatching { File(context.cacheDir, "att_" + safeName(guid)).writeBytes(bytes) }
    }

    /** Decode the file with `inSampleSize` chosen to bring it under [maxDim], then
     *  apply the EXIF orientation — BitmapFactory ignores it, so a portrait phone
     *  photo (commonly tagged "rotate 90°") would otherwise render sideways.
     *  Internal because [Gallery] decodes local files through the same path rather
     *  than keeping a second copy of the orientation handling. */
    internal fun decode(file: File, maxDim: Int = MAX_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, opts) }.getOrNull() ?: return null
        return applyOrientation(bitmap, file)
    }

    /** Rotate/flip [bitmap] per the file's EXIF orientation tag (a no-op for the
     *  common upright case, so we don't needlessly copy the bitmap). */
    private fun applyOrientation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap // ORIENTATION_NORMAL / undefined — already upright
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** Attachment guids can contain `/`, `:`, etc. — flatten to a safe filename. */
    private fun safeName(guid: String): String = guid.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}
