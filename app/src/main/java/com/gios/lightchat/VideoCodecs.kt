package com.gios.lightchat

/**
 * What to tell someone when a video won't play, named by its codec.
 *
 * "This video won't play here" was true and useless. Almost every clip that fails on this phone is
 * an iPhone `.mov`, and what an iPhone records decides whether it plays: H.264 always does, HEVC
 * usually does, and HDR (Dolby Vision or HLG, the default on recent iPhones) depends on decoders
 * the Light Phone may not have. Naming the codec says which of those it was, and the sender can
 * fix it at their end.
 *
 * Free of Android on purpose, so it has a test. The transfer constants are the values of
 * `MediaFormat.COLOR_TRANSFER_ST2084` and `COLOR_TRANSFER_HLG`.
 */
object VideoCodecs {

    const val TRANSFER_PQ = 6
    const val TRANSFER_HLG = 7

    fun name(mime: String?): String = when (mime?.lowercase()) {
        null, "" -> "in a format this phone doesn't know"
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/dolby-vision" -> "Dolby Vision"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        else -> mime.removePrefix("video/").uppercase()
    }

    fun isHdr(mime: String?, transfer: Int?): Boolean =
        mime.equals("video/dolby-vision", ignoreCase = true) ||
            transfer == TRANSFER_PQ || transfer == TRANSFER_HLG

    /**
     * The line the player shows. [mime] is null when the file has no video track at all, or when
     * it couldn't be read.
     */
    fun cantPlay(mime: String?, transfer: Int?): String {
        if (mime == null) return "This video won't play here"
        val hdr = isHdr(mime, transfer) && !mime.equals("video/dolby-vision", ignoreCase = true)
        val what = name(mime) + if (hdr) " HDR" else ""
        val tip = if (mime.equals("video/avc", ignoreCase = true)) "" else
            "\n\nOn an iPhone, Settings › Camera › Formats › Most Compatible records video that plays here."
        return "This video is $what, which this phone can't decode.$tip"
    }
}
