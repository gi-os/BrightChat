package com.gios.lightchat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Files arriving from, and leaving for, the rest of the phone.
 *
 * Two directions, one file, because they are the same problem read backwards. Something shared or
 * pasted *in* is a URI this app cannot keep, so it is copied into the cache under a name the send
 * path can read a mime type back off. Something saved *out* is a cache file that has to reach a
 * folder other apps look in, which on any modern Android means MediaStore and not a path.
 */
object SharedFiles {

    /**
     * Copy [uri] into `cacheDir/shared-in`, or null if it cannot be read.
     *
     * The name only has to carry a plausible extension — the send reads the mime type off it — and
     * be unique enough that two shares in a row do not collide. The extension has to be one
     * [MediaKind.mimeOf] knows: a mime *subtype* is not an extension.
     */
    fun copyIntoCache(context: Context, uri: Uri): File? = runCatching {
        val dir = File(context.cacheDir, "shared-in").apply { mkdirs() }
        val extension = MediaKind.extensionOf(context.contentResolver.getType(uri))
        val out = File(dir, "share-" + System.nanoTime() + "." + extension)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * Copy [file] into shared storage where the rest of the phone can find it.
     *
     * MediaStore rather than a path, because writing into `Pictures` directly has not been allowed
     * for years — an insert is the only way in, and it both writes the bytes and creates the entry.
     *
     * Worth knowing: the file it writes is a real one at `Pictures/BrightChat/…`, and [Gallery]
     * scans that tree rather than trusting MediaStore, so a photograph saved out of a chat turns up
     * in this app's own picker afterwards. That is a good outcome and not one anybody asked for.
     *
     * `IS_PENDING` while the bytes are going in, so nothing else sees a half-written photo, and it
     * is cleared in a `finally`: a row left pending is invisible to every gallery on the phone and
     * there is nothing in the interface that would explain why.
     *
     * Returns the message to show, or null when it could not be saved.
     */
    fun saveToGallery(context: Context, file: File, target: SaveTo.Target): String? = runCatching {
        val resolver = context.contentResolver
        val collection = when (target.collection) {
            SaveTo.Collection.IMAGES -> MediaStore.Images.Media.getContentUri(VOLUME)
            SaveTo.Collection.VIDEO -> MediaStore.Video.Media.getContentUri(VOLUME)
            SaveTo.Collection.DOWNLOADS -> MediaStore.Downloads.getContentUri(VOLUME)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val item = resolver.insert(collection, values) ?: return@runCatching null
        try {
            resolver.openOutputStream(item)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run {
                resolver.delete(item, null, null)
                return@runCatching null
            }
        } catch (e: Exception) {
            // A half-written row is worse than none: it is a file other apps can open and find
            // truncated. Taken back out before the failure is reported.
            runCatching { resolver.delete(item, null, null) }
            throw e
        } finally {
            runCatching {
                resolver.update(item, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
        }
        SaveTo.message(target)
    }.getOrElse {
        Log.w(TAG, "could not save an attachment", it)
        null
    }

    private const val TAG = "SharedFiles"
    private const val VOLUME = MediaStore.VOLUME_EXTERNAL_PRIMARY
}
