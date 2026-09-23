package com.gios.lightchat.beeper

import com.gios.lightchat.ReactionType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure half of the Beeper backend: how a Matrix room and its events are spelled in the shape
 * the rest of BrightChat already reads.
 *
 * ### Why Beeper messages are written as BlueBubbles JSON
 *
 * [com.gios.lightchat.db.MessageStore] keeps every message as its original BlueBubbles JSON and
 * re-parses it with `BlueBubblesApi.parseMessage` on the way out. The list, the thread, the
 * contact page, tapback folding, the share providers and the alert wording all read that one
 * parse. Writing a Beeper event in the same shape means all of that works for WhatsApp on the day
 * it lands, and there is still exactly one message parser in the app. The alternative, a second
 * model and a second store, is how two chat backends end up disagreeing about what a message is.
 *
 * Nothing here touches Trixnity or Android, so it runs under plain JUnit.
 */
object BeeperMapping {

    /** Every Beeper room guid starts with this; iMessage guids never do (`iMessage;-;…`). */
    const val GUID_PREFIX = "mx:"

    /** Attachment guids carry this, so `Attachments` knows to ask Beeper for the bytes. */
    const val ATTACHMENT_PREFIX = "mxa:"

    fun roomGuid(roomId: String): String = GUID_PREFIX + roomId

    fun roomIdOf(guid: String): String? = guid.takeIf { it.startsWith(GUID_PREFIX) }?.removePrefix(GUID_PREFIX)

    fun isBeeper(guid: String?): Boolean = guid?.startsWith(GUID_PREFIX) == true

    /** A Matrix event id (it starts with a dollar sign), as opposed to an iMessage guid. */
    fun isMatrixEvent(guid: String?): Boolean = guid?.startsWith("${'$'}") == true

    // ---------------------------------------------------------------- networks

    /**
     * Beeper's bridge ghosts are named after their bridge (`@whatsapp_lid-…:beeper.local`,
     * `@signal_…`, `@instagramgo_…`), so the part before the first underscore names the network.
     * Keyed without a trailing `go`, which marks the Go rewrites of the Meta, Discord and Slack
     * bridges.
     */
    private val NETWORKS = linkedMapOf(
        "whatsapp" to "WhatsApp",
        "signal" to "Signal",
        "telegram" to "Telegram",
        "instagram" to "Instagram",
        "facebook" to "Messenger",
        "messenger" to "Messenger",
        "meta" to "Messenger",
        "discord" to "Discord",
        "slack" to "Slack",
        "linkedin" to "LinkedIn",
        "twitter" to "Twitter",
        "gmessages" to "SMS",
        "googlechat" to "Google Chat",
        "imessage" to "iMessage",
        "bluesky" to "Bluesky",
    )

    /** The network a user id belongs to, or null for a plain Matrix user or anything unknown. */
    fun networkOfUser(userId: String): String? {
        val local = userId.removePrefix("@").substringBefore(":")
        val bridge = local.substringBefore("_", missingDelimiterValue = "").lowercase().removeSuffix("go")
        if (bridge.isEmpty()) return null
        return NETWORKS[bridge]
    }

    /**
     * The network of a room, from the other people in it. The first ghost that names a network
     * wins. A room of plain Beeper users is "Beeper".
     */
    fun networkOfRoom(memberIds: Collection<String>, ownUserId: String?): String {
        val others = memberIds.filter { it != ownUserId }
        others.firstNotNullOfOrNull { networkOfUser(it) }?.let { return it }
        others.firstNotNullOfOrNull { botNetwork(it) }?.let { return it }
        return "Beeper"
    }

    /** A bridge bot (`@whatsappbot:beeper.local`) is not a person to name a room after. */
    fun isBridgeBot(userId: String): Boolean {
        val local = userId.removePrefix("@").substringBefore(":")
        return !local.contains("_") && local.endsWith("bot")
    }

    private fun botNetwork(userId: String): String? {
        if (!isBridgeBot(userId)) return null
        val local = userId.removePrefix("@").substringBefore(":").removeSuffix("bot").removeSuffix("go")
        return NETWORKS[local.lowercase()]
    }

    // ---------------------------------------------------------------- reactions

    /**
     * The six tapbacks as the emoji a Matrix reaction carries. Outgoing, a tapback becomes its
     * emoji. Incoming, the emoji becomes the nearest tapback. An emoji with no tapback is left out
     * of the thread rather than drawn wrong; the full emoji set is a later release.
     */
    private val OUTGOING = mapOf(
        ReactionType.LOVE to "❤️",
        ReactionType.LIKE to "👍",
        ReactionType.DISLIKE to "👎",
        ReactionType.LAUGH to "😂",
        ReactionType.EMPHASIZE to "‼️",
        ReactionType.QUESTION to "❓",
    )

    private val INCOMING: Map<String, ReactionType> = mapOf(
        "❤" to ReactionType.LOVE,
        "😍" to ReactionType.LOVE,
        "🥰" to ReactionType.LOVE,
        "👍" to ReactionType.LIKE,
        "👎" to ReactionType.DISLIKE,
        "😂" to ReactionType.LAUGH,
        "🤣" to ReactionType.LAUGH,
        "😆" to ReactionType.LAUGH,
        "‼" to ReactionType.EMPHASIZE,
        "❗" to ReactionType.EMPHASIZE,
        "❓" to ReactionType.QUESTION,
        "❔" to ReactionType.QUESTION,
    )

    fun emojiFor(type: ReactionType): String = OUTGOING.getValue(type)

    /**
     * The tapback for a reaction key. Variation selectors and skin tones are stripped first:
     * bridges hand WhatsApp's heart back as U+2764 with or without U+FE0F, and a thumbs-up in any
     * skin tone is still a like.
     */
    fun reactionOf(key: String?): ReactionType? {
        if (key.isNullOrBlank()) return null
        return INCOMING[normalizeKey(key)]
    }

    fun normalizeKey(key: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < key.length) {
            val cp = key.codePointAt(i)
            val skip = cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF
            if (!skip) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- message JSON

    /** One file on a Matrix message. */
    data class File(
        val mimeType: String?,
        val name: String?,
        val width: Int = 0,
        val height: Int = 0,
    )

    /** Everything [messageJson] needs about one Matrix event, already decrypted. */
    data class Event(
        val eventId: String,
        val sender: String,
        val senderName: String?,
        val fromMe: Boolean,
        val timestamp: Long,
        val text: String,
        val files: List<File> = emptyList(),
        val replyTo: String? = null,
        /** For a reaction: the event it lands on, and its key (an emoji). */
        val reactionTarget: String? = null,
        val reactionKey: String? = null,
        /** A removed reaction: the tapback it took away, recovered from the stored original. */
        val reactionRemoval: ReactionType? = null,
        /** The emoji a removed [ReactionType.EMOJI] reaction carried. */
        val reactionRemovalEmoji: String? = null,
        val editedAt: Long = 0,
        val readAt: Long = 0,
        val failed: Boolean = false,
        /** The client transaction id of our own send, so the optimistic bubble reconciles. */
        val tempGuid: String? = null,
        /** A call notice from the bridge. */
        val isCall: Boolean = false,
    )

    /**
     * An event as BlueBubbles message JSON. See the class comment for why.
     *
     * The sender goes in `handle.address` as a display name, not an address: nothing on the
     * Beeper side resolves through the phone's address book yet, and a name is what a group thread
     * and a notification need to show.
     */
    fun messageJson(e: Event, roomId: String): JSONObject = JSONObject().apply {
        put("guid", e.eventId)
        put("text", e.text)
        put("dateCreated", e.timestamp)
        put("isFromMe", e.fromMe)
        if (!e.fromMe) {
            put("handle", JSONObject().put("address", e.senderName?.takeIf { it.isNotBlank() } ?: e.sender))
        }
        // A Matrix send that has an event id has reached the server; that is as far as
        // "delivered" can be known for a bridged network.
        put("dateDelivered", if (e.fromMe && !e.failed) e.timestamp else 0L)
        put("dateRead", e.readAt)
        if (e.editedAt > 0) put("dateEdited", e.editedAt)
        if (e.failed) put("error", 1)
        e.replyTo?.let { put("threadOriginatorGuid", it) }
        e.tempGuid?.let { put("tempGuid", it) }
        if (e.isCall) put("isCallEvent", true)
        // The six tapbacks keep their own marks; any other emoji is an `emoji` reaction carrying it.
        val key = e.reactionKey?.takeIf { it.isNotBlank() }
        val reactionType = e.reactionRemoval ?: reactionOf(key) ?: key?.let { ReactionType.EMOJI }
        if (e.reactionTarget != null && reactionType != null) {
            put("associatedMessageGuid", e.reactionTarget)
            put(
                "associatedMessageType",
                if (e.reactionRemoval != null) "-${reactionType.apiValue}" else reactionType.apiValue,
            )
            if (reactionType == ReactionType.EMOJI) {
                (if (e.reactionRemoval != null) e.reactionRemovalEmoji else key)?.let { put("associatedMessageEmoji", it) }
            }
        }
        if (e.files.isNotEmpty()) {
            put(
                "attachments",
                JSONArray().apply {
                    e.files.forEachIndexed { i, f ->
                        put(
                            JSONObject().apply {
                                put("guid", attachmentGuid(roomId, e.eventId, i))
                                f.mimeType?.let { put("mimeType", it) }
                                f.name?.let { put("transferName", it) }
                                if (f.width > 0) put("width", f.width)
                                if (f.height > 0) put("height", f.height)
                            },
                        )
                    }
                },
            )
        }
    }

    /** Where an attachment lives: its room, its event, and its place among the event's files. */
    data class AttachmentRef(val roomId: String, val eventId: String, val index: Int)

    /**
     * An attachment's guid: its room, event and position. The bytes are fetched later by looking
     * the event up again, so the guid only has to find it. Stable per file, which is what the
     * on-disk cache in `Attachments` keys on. Room ids never contain `|`.
     */
    fun attachmentGuid(roomId: String, eventId: String, index: Int): String =
        ATTACHMENT_PREFIX + roomId + "|" + eventId + "#" + index

    /** Splits an attachment guid back into where it lives. */
    fun parseAttachmentGuid(guid: String): AttachmentRef? {
        if (!guid.startsWith(ATTACHMENT_PREFIX)) return null
        val body = guid.removePrefix(ATTACHMENT_PREFIX)
        val bar = body.indexOf('|')
        val hash = body.lastIndexOf('#')
        if (bar <= 0 || hash <= bar + 1) return null
        val index = body.substring(hash + 1).toIntOrNull() ?: return null
        return AttachmentRef(body.substring(0, bar), body.substring(bar + 1, hash), index)
    }

    fun isBeeperAttachment(guid: String): Boolean = guid.startsWith(ATTACHMENT_PREFIX)

    /**
     * Cuts the quote a Matrix client puts in front of a reply (`> <@alex> original` lines and a
     * blank line). BrightChat draws the quoted message itself.
     */
    fun stripReplyFallback(body: String): String {
        if (!body.startsWith("> ")) return body
        val lines = body.lines()
        val firstReal = lines.indexOfFirst { !it.startsWith(">") }
        if (firstReal < 0) return body
        return lines.drop(firstReal).dropWhile { it.isBlank() }.joinToString("\n")
    }

    private val CALL_WORDS = Regex("(?i)\\b(call|calling|llamada|appel|anruf)\\b")

    /** Whether a bridge notice is about a call ("Missed voice call", "Incoming video call"). */
    fun isCallNotice(body: String): Boolean = CALL_WORDS.containsMatchIn(body)

    /** The "* " an edit carries in its fallback body, for clients that don't read edits. */
    fun stripEditFallback(body: String): String = body.removePrefix("* ")
}
