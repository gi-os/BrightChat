package com.gios.lightchat

/**
 * Where a saved attachment belongs, and what to call it when it gets there.
 *
 * Saving is one MediaStore insert, and every decision that can go wrong is made before it: which
 * collection, which folder inside shared storage, and a filename that will not collide with the last
 * one. None of that needs Android to decide, so it is decided here and tested, and the file that
 * does the insert holds nothing but the insert.
 *
 * The rule is the one the system's own folders imply: a picture goes where pictures go, a video
 * where videos go, and everything else is a download. An attachment from iMessage may arrive with
 * no name and a mime type of nothing in particular, so both have a fallback and neither is trusted.
 */
object SaveTo {

    enum class Collection { IMAGES, VIDEO, DOWNLOADS }

    /** The folder inside shared storage. One name, so everything this app saves lands together. */
    const val FOLDER = "BrightChat"

    data class Target(
        val collection: Collection,
        /** `Pictures/BrightChat`, `Movies/BrightChat` or `Download/BrightChat`. */
        val relativePath: String,
        val displayName: String,
        val mimeType: String,
    )

    fun of(mimeType: String?, transferName: String?, fallbackId: String): Target {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        val collection = when {
            mime.startsWith("image/") -> Collection.IMAGES
            mime.startsWith("video/") -> Collection.VIDEO
            else -> Collection.DOWNLOADS
        }
        val parent = when (collection) {
            Collection.IMAGES -> "Pictures"
            Collection.VIDEO -> "Movies"
            Collection.DOWNLOADS -> "Download"
        }
        return Target(
            collection = collection,
            relativePath = "$parent/$FOLDER",
            displayName = nameFor(mime, transferName, fallbackId),
            mimeType = mime.ifBlank { "application/octet-stream" },
        )
    }

    /**
     * A filename that is safe to put in a shared folder.
     *
     * Everything outside a small alphabet becomes an underscore, because this string is going into
     * a filesystem somebody else's apps will read, and iMessage transfer names carry spaces,
     * slashes and the occasional emoji. An extension is added when the name has none, worked out
     * from the mime type — a file called `IMG_0042` with no suffix is opened by nothing.
     */
    fun nameFor(mimeType: String?, transferName: String?, fallbackId: String): String {
        val raw = transferName?.trim().orEmpty().ifBlank { fallbackId }
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifBlank { "attachment" }
        val ext = safe.substringAfterLast('.', "")
        val hasExtension = ext.isNotBlank() && ext.length in 2..5 && ext.all { it.isLetter() }
        // The stem is shortened, never the extension. Capping the whole string first could cut
        // `photo….jpeg` down to `photo….jp`, which still looks like an extension — so the name kept
        // it and the file was saved as `.jp`, which opens with nothing.
        return if (hasExtension) {
            val stem = safe.dropLast(ext.length + 1).take(MAX_NAME)
            "$stem.$ext"
        } else {
            "${safe.take(MAX_NAME)}.${extensionFor(mimeType)}"
        }
    }

    /**
     * An extension for [mimeType].
     *
     * Deliberately short and deliberately not clever: these are the types that actually arrive in a
     * message. Anything else keeps `bin`, which is honest — a wrong extension is worse than an
     * uninformative one, because it makes the system open the file with the wrong thing.
     */
    fun extensionFor(mimeType: String?): String = when (mimeType?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "video/quicktime" -> "mov"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "audio/x-wav", "audio/wav" -> "wav"
        "audio/mp4", "audio/m4a" -> "m4a"
        "audio/mpeg" -> "mp3"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        else -> "bin"
    }

    /** What to tell the user once it has landed. The folder is what they will go looking in. */
    fun message(target: Target): String = when (target.collection) {
        Collection.IMAGES -> "Saved to Pictures/$FOLDER"
        Collection.VIDEO -> "Saved to Movies/$FOLDER"
        Collection.DOWNLOADS -> "Saved to Download/$FOLDER"
    }

    /** Long enough for any real filename, short enough that no filesystem refuses it. */
    private const val MAX_NAME = 96
}
