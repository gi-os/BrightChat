package com.gios.lightchat

/**
 * Bare http/https URLs in message text.
 *
 * One matcher for the whole app: the thread makes these tappable inline (`linkify`) and the
 * contact page lists them. Two regexes would drift, and then a link would be tappable in a
 * message but missing from the list of links in that same conversation.
 *
 * The trailing character class is what stops "have a look at https://example.com/a." from
 * carrying the full stop into the link. It always did, and underlining a sentence's
 * punctuation was only ugly; now that the same match is a de-duplication key, `…/a` and
 * `…/a.` would list the same article twice.
 */
internal val URL_REGEX = Regex("""https?://[^\s]*[^\s.,;:!?)\]'"”’]""")

/**
 * A conversation — BlueBubbles calls it a "chat". The [guid] (e.g.
 * `iMessage;+;chat36268974474180030`) is the key used to fetch its messages and,
 * later, to send into it.
 */
data class Conversation(
    val guid: String,
    val displayName: String,
    val participants: List<String>, // raw handle addresses (phone / email)
    val isGroup: Boolean,
    val lastText: String,
    val lastDate: Long,             // epoch millis; 0 when unknown
    val lastFromMe: Boolean,
    // Set when the newest activity is a tapback, so the list can show "Liz loved an
    // image" instead of the older real message ([lastText] still holds that fallback).
    // Cleared the moment a normal message arrives. See BlueBubblesApi.conversations.
    val lastReaction: ReactionPreview? = null,
    // Every chat-room guid this conversation spans. Usually just [guid]; for a group
    // that iMessage has forked into sibling rooms with the same name + participants,
    // it lists all of them ordered by newest *non-reaction* message, so [guid] is the
    // live room — the send target. (Newest message of any kind isn't safe: a tapback can
    // land in a dead old room, and AppleScript can't send there.) The thread
    // fetches/merges messages across all of them. See BlueBubblesApi.conversations.
    val guids: List<String> = listOf(guid),
    // The newest incoming message hasn't been read anywhere on the account (derived
    // from its dateRead — chat.db stamps it when the chat is read on any device, and
    // Messages-in-iCloud syncs that to the Mac). Cleared live by opening the thread
    // here, or by a `chat-read-status-changed` socket event when read elsewhere.
    val unread: Boolean = false,
    // Handle of whoever sent [lastText] — null when it was me, or when the row was
    // written by a build from before this field existed. What a group notification
    // needs and could not get: there the title is the room, so the body is the only
    // place the sender's name can go. Set wherever [lastText] is set and never apart
    // from it, or a row names the wrong person.
    val lastSender: String? = null,
    // An AI agent chat (see Agent.kt) masquerading as a conversation row — a synthetic
    // guid "agent:<id>", never a BlueBubbles chat, never sent to the server. The list
    // renders it like any other row; open()/deleteConversation() branch on this flag.
    val isAgent: Boolean = false,
    // Local per-phone nickname overriding the display name (see Store.nicknames).
    val nickname: String? = null,
) {
    /** Human title: an explicit group name if set, otherwise the participants. Prefer
     *  `Contacts.title`, which resolves names; this is the nameless fallback. `"null"` is
     *  guarded because BlueBubbles sends a JSON null for an unnamed chat and org.json
     *  stringifies that (see `JSONObject.string`). A local [nickname] wins over all of it. */
    val title: String
        get() = nickname?.takeIf { it.isNotBlank() }
            ?: when {
                displayName.isNotBlank() && displayName != "null" -> displayName
                participants.isNotEmpty() -> participants.joinToString(", ")
                else -> "Unknown"
            }
}

/**
 * One file riding along with a message. [guid] keys the download endpoint; the
 * raw bytes are fetched + cached lazily (see [com.gios.lightchat.Attachments]).
 * Images render inline; other files render as a tappable [fileLabel] row that opens
 * them externally (see `ChatViewModel.openAttachment`).
 */
data class Attachment(
    val guid: String,
    val mimeType: String?,
    val transferName: String?,
    val width: Int,           // pixels per the server's metadata; 0 when unknown
    val height: Int,
) {
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true

    /**
     * A GIF, which is drawn by a different path from every other image.
     *
     * The mime type, not the filename, like [isVideo] beside it — iMessage renames attachments and
     * a GIF arrives called all sorts of things. It matters because BitmapFactory decodes a GIF to
     * its **first frame** with no error: for as long as this app had no notion of a GIF, every one
     * anybody sent looked like a still photograph of one. See
     * [com.gios.lightchat.ui.rememberGifPainter].
     */
    val isGif: Boolean get() = mimeType?.startsWith("image/gif") == true

    /**
     * Playable here rather than handed to another app.
     *
     * The mime type only — not the file extension — because it is what the server said the file
     * is, and a `.mov` from an iPhone arrives as `video/quicktime` whatever the name says.
     */
    val isVideo: Boolean get() = mimeType?.startsWith("video/") == true

    /**
     * Playable here too, and for the same reason [isVideo] is.
     *
     * The mime type only, not the filename. An iPhone voice memo arrives as `audio/x-caf` whatever
     * it is called, and a moment shared out of BrightRecorder arrives as `audio/x-wav`.
     */
    val isAudio: Boolean get() = mimeType?.startsWith("audio/") == true

    /** A short human type for a non-image file, e.g. "Video", "Audio", "Contact". */
    val typeLabel: String
        get() = when {
            mimeType == null -> "File"
            mimeType.startsWith("video/") -> "Video"
            mimeType.startsWith("audio/") -> "Audio"
            mimeType.contains("vcard") -> "Contact"
            mimeType == "application/pdf" -> "PDF"
            mimeType.startsWith("image/") -> "Photo"
            else -> "File"
        }

    /** The row shown for a non-image file in the thread: type plus the filename. */
    val fileLabel: String
        get() = transferName?.takeIf { it.isNotBlank() }?.let { "$typeLabel · $it" } ?: typeLabel
}

/**
 * The six iMessage tapbacks. [apiValue] is the string both the `message/react`
 * endpoint takes *and* the value the server reports in `associatedMessageType` —
 * it transforms the raw iMessage code to a word (2000→`love`, 2003→`laugh`,
 * 3000→`-love` for a removal). The visual mark for each is drawn/typeset in
 * `ui/Tapbacks.kt` (heart/thumbs as vectors, HA/‼/? as Public Sans text).
 */
enum class ReactionType(val apiValue: String) {
    LOVE("love"),
    LIKE("like"),
    DISLIKE("dislike"),
    LAUGH("laugh"),
    EMPHASIZE("emphasize"),
    QUESTION("question");

    /** Past-tense verb for the conversation-list summary ("Liz loved an image"). */
    val verb: String
        get() = when (this) {
            LOVE -> "loved"
            LIKE -> "liked"
            DISLIKE -> "disliked"
            LAUGH -> "laughed at"
            EMPHASIZE -> "emphasized"
            QUESTION -> "questioned"
        }

    companion object {
        /** Maps a server `associatedMessageType` string (`love` or `-love`) to a
         *  type, ignoring the add/remove sign. Null if it isn't a reaction (e.g.
         *  `sticker`, or null/blank on a normal message). */
        fun fromApiValue(raw: String?): ReactionType? {
            if (raw.isNullOrBlank()) return null
            val base = raw.removePrefix("-")
            return entries.firstOrNull { it.apiValue == base }
        }
    }
}

/** A tapback folded onto its target message: who reacted and with what. */
data class Reaction(
    val type: ReactionType,
    val fromMe: Boolean,
    val sender: String?,      // reactor's handle address; null when from me
)

/**
 * Conversation-list descriptor for a chat whose newest activity is a tapback, so
 * the row reads "Liz loved an image" the way iMessage does (rather than falling
 * back to the older real message). The reactor's name is resolved at render time
 * (via [Contacts]) — [reactor] is their raw handle, null when the tapback is mine.
 * [target] already describes what was reacted to ("an image", a quoted text, or
 * "a message" when the target isn't to hand). See [ChatMessage.reactionPreview].
 */
data class ReactionPreview(
    val type: ReactionType,
    val fromMe: Boolean,
    val reactor: String?,
    val target: String,
) {
    /** The one-line summary, resolving the reactor to a name ("You" when mine). */
    fun summary(contacts: Contacts): String {
        val who = if (fromMe) "You" else reactor?.let { contacts.sender(it) } ?: "Someone"
        return "$who ${type.verb} $target"
    }
}

/**
 * The group-system actions we render as centered event lines in a thread
 * (a rename, a member change) rather than as message turns. Derived from the
 * message's `itemType`/`groupActionType`; see [ChatMessage.groupEvent].
 */
enum class GroupEvent { RENAMED, MEMBER_ADDED, MEMBER_REMOVED, MEMBER_LEFT, PHOTO_CHANGED }

/** One message within a conversation. */
data class ChatMessage(
    val guid: String,
    val text: String,
    val date: Long,           // epoch millis
    val fromMe: Boolean,
    val sender: String?,      // handle address; null when from me (shown in groups)
    val attachments: List<Attachment> = emptyList(),
    // Delivery receipts on a message *I* sent: when it reached the recipient and
    // when they read it (0 = unknown / hasn't happened). Both arrive on the initial
    // fetch and again via `updated-message` socket events as the status changes.
    val dateDelivered: Long = 0,
    val dateRead: Long = 0,
    // Group-system rows: non-zero itemType marks a rename / member change / etc.
    // rather than actual speech. `groupTitle` carries the new name on a rename;
    // `groupActionType` disambiguates within an itemType (see [groupEvent]).
    val itemType: Int = 0,
    val groupTitle: String? = null,
    val groupActionType: Int = 0,
    // Non-zero when a message *I* sent failed to deliver (e.g. the address isn't
    // on iMessage). The failure can land after the send call succeeds — the server
    // flags it via `updated-message`/`message-send-error` — so without this a
    // dead send looks identical to a good one.
    val error: Int = 0,
    // Set when this message is an inline reply: the guid of the message it replies
    // to (what the Private API's `selectedMessageGuid` send param names).
    val threadOriginatorGuid: String? = null,
    // Set only on reaction messages: the message this tapback targets (raw, may be
    // prefixed `p:0/` or `bp:`) and its `associatedMessageType` (the server's word
    // form, e.g. `love`/`-love`). Such messages aren't shown as rows — the ViewModel
    // folds them onto their target as [reactions].
    val associatedMessageGuid: String? = null,
    val associatedMessageType: String? = null,
    // Tapbacks folded onto this (normal) message for display. Never serialized;
    // populated by ChatViewModel.foldReactions from the reaction messages.
    val reactions: List<Reaction> = emptyList(),
    // The client tempGuid we sent this message with, echoed back by the server. Lets
    // the socket echo of our own send reconcile against the optimistic bubble (whose
    // guid IS the tempGuid) instead of rendering a second row. Null for messages we
    // didn't send / weren't sent with a tempGuid.
    val tempGuid: String? = null,
    // When this message was last edited on the Mac's side (epoch millis, 0 = never).
    // Ventura and later only; older servers never send the field. Read for the
    // "Edited" mark under a turn and to keep our own edit from being undone by the
    // socket echo that follows it — see ChatViewModel.editMessage.
    val dateEdited: Long = 0,
) {
    val images: List<Attachment> get() = attachments.filter { it.isImage }

    /** Non-image attachments — rendered as tappable file rows, not inline. */
    val files: List<Attachment> get() = attachments.filter { !it.isImage }

    /** A group-system row (rename, member change) rather than actual speech. Such
     *  rows have no text, so without special rendering they'd show as blank turns. */
    val isGroupEvent: Boolean get() = itemType != 0

    /** Which group event this row is — or null for an itemType we don't render
     *  (those rows are dropped in `ChatViewModel.foldReactions`). The itemType/
     *  groupActionType pairs match the Messages database's own encoding. */
    val groupEvent: GroupEvent?
        get() = when {
            itemType == 2 -> GroupEvent.RENAMED
            itemType == 1 && groupActionType == 0 -> GroupEvent.MEMBER_ADDED
            itemType == 1 && groupActionType == 1 -> GroupEvent.MEMBER_REMOVED
            itemType == 3 && groupActionType == 0 -> GroupEvent.MEMBER_LEFT
            itemType == 3 -> GroupEvent.PHOTO_CHANGED
            else -> null
        }

    /**
     * The centered line for a group-event row, with [actor] already resolved to a
     * display name ("You" / "Liz"). The affected member of an add/remove is only a
     * handle ROWID in the payload, so it stays "someone". Null for events we
     * don't render.
     */
    fun groupEventText(actor: String): String? = when (groupEvent) {
        GroupEvent.RENAMED ->
            groupTitle?.takeIf { it.isNotBlank() }?.let { "$actor named the conversation “$it”" }
                ?: "$actor removed the conversation name"
        GroupEvent.MEMBER_ADDED -> "$actor added someone to the conversation"
        GroupEvent.MEMBER_REMOVED -> "$actor removed someone from the conversation"
        GroupEvent.MEMBER_LEFT -> "$actor left the conversation"
        GroupEvent.PHOTO_CHANGED -> "$actor changed the group photo"
        null -> null
    }

    /** This message is itself a tapback (folded onto its target, not shown alone). */
    val isReaction: Boolean
        get() = !associatedMessageGuid.isNullOrBlank() && reactionType != null

    /** The tapback this message carries, if it is one. */
    val reactionType: ReactionType? get() = ReactionType.fromApiValue(associatedMessageType)

    /** Whether this tapback *removes* a reaction (server prefixes the word with `-`). */
    val isReactionRemoval: Boolean get() = associatedMessageType?.startsWith("-") == true

    /** The plain guid of the message this tapback targets, stripping iMessage's
     *  `p:<n>/` (part) and `bp:` (body) prefixes. */
    val reactionTargetGuid: String?
        get() {
            val raw = associatedMessageGuid ?: return null
            raw.indexOf('/').let { if (it >= 0) return raw.substring(it + 1) }
            if (raw.startsWith("bp:")) return raw.substring(3)
            return raw
        }

    /** The body line to render, or null when the text is just the attachment
     *  placeholder for attachment(s) we render ourselves (images inline, other
     *  files as their own tappable rows). */
    val bodyText: String?
        get() = if (text == ATTACHMENT_PLACEHOLDER && attachments.isNotEmpty()) null else text.ifEmpty { null }

    /**
     * One-line summary for the conversation list: the message text when there is
     * any, or — for an attachment-only message — a bracketed description of it
     * (`[Photo]`, `[3 Photos]`, `[Attachment]`). The brackets mark it as a descriptor
     * so it can't be mistaken for someone literally texting "photo". Empty only for a
     * genuinely empty message.
     */
    val previewText: String
        get() {
            val t = if (text == ATTACHMENT_PLACEHOLDER) "" else text
            if (t.isNotBlank()) return t
            val imageCount = images.size
            if (imageCount > 0) return if (imageCount == 1) "[Photo]" else "[$imageCount Photos]"
            // No images here, so any attachments are non-image files; a single one
            // gets its type ("[Video]"), several get a count.
            if (attachments.size == 1) return "[${attachments.first().typeLabel}]"
            if (attachments.isNotEmpty()) return "[${attachments.size} Attachments]"
            // Placeholder text but no parsed attachments (e.g. an optimistic fallback).
            return if (text == ATTACHMENT_PLACEHOLDER) "[Attachment]" else ""
        }

    /**
     * If this message is a tapback (and not a *removal*), a [ReactionPreview] for the
     * conversation list, resolving its target via [findTarget] to describe what was
     * reacted to ("an image" / a quoted text / "a message" when the target isn't
     * cached). Null for normal messages and reaction removals (those revert the list
     * to the underlying real message).
     */
    fun reactionPreview(findTarget: (String) -> ChatMessage?): ReactionPreview? {
        val type = reactionType ?: return null
        if (isReactionRemoval) return null
        val desc = reactionTargetGuid?.let(findTarget)?.shortDescription ?: "a message"
        return ReactionPreview(type, fromMe, sender, desc)
    }

    /** A one-phrase stand-in for this message when another line points at it —
     *  a tapback summary's target, or a reply's quoted original. */
    val shortDescription: String
        get() = when {
            images.isNotEmpty() -> "an image"
            attachments.isNotEmpty() -> "an attachment"
            text.isNotBlank() && text != ATTACHMENT_PLACEHOLDER -> "“${text.trim()}”"
            else -> "a message"
        }

    companion object {
        /** Stand-in body for an attachment-only message (no real text). */
        const val ATTACHMENT_PLACEHOLDER = "[Attachment]"
    }
}

/** A pickable recipient when starting a new message — one row per contact address. */
data class Contact(val name: String, val address: String)

/**
 * A message pushed over the live socket, carried from [com.gios.lightchat.socket.SocketService]
 * to the ViewModel via [com.gios.lightchat.socket.SocketBus]. [isNew] distinguishes
 * a brand-new message from an update (delivered/read/edited). [chatDisplayName] is
 * the embedded chat's group name, used for notifications (the service has no
 * contact index to resolve names).
 */
data class IncomingMessage(
    val chatGuid: String,
    val message: ChatMessage,
    val isNew: Boolean,
    val chatDisplayName: String,
    /** Whether the room is a group (chat `style` 43). A group's alert reads differently:
     *  the title is the room, so the body has to name the sender. */
    val isGroup: Boolean = false,
    /** The room's participant handles, so an *unnamed* group can still be titled by the
     *  people in it rather than by whoever happened to text last. */
    val participants: List<String> = emptyList(),
    /**
     * The message's own JSON, as it arrived, with the embedded `chats` array stripped.
     *
     * Carried so a live message can be written to the local store in the same form the
     * sync writes — the store keeps original JSON and re-parses it on the way out, so a
     * re-serialised [ChatMessage] would be a second, lossier encoding of the same thing.
     */
    val raw: String = "",
)

/**
 * A typing-indicator change from the live socket ([com.gios.lightchat.socket.SocketBus]):
 * the other party in [chatGuid] started ([typing] true) or stopped typing. The
 * server only emits these for 1:1 chats, and re-emits roughly every 5s while typing
 * continues — so the ViewModel auto-expires a stale "typing" if no refresh arrives.
 */
data class TypingEvent(val chatGuid: String, val typing: Boolean)

/**
 * A `chat-read-status-changed` socket event: [chatGuid] was read ([read] true)
 * somewhere on the account — the Mac, an iPhone, or our own markRead. The server
 * derives it by polling chat.db's lastReadMessageTimestamp, so it needs no
 * Private API. Used to clear the list's unread marker (and the chat's
 * notification) when the user reads the thread on another device.
 */
data class ReadStatusEvent(val chatGuid: String, val read: Boolean)

/**
 * Moves a conversation's list row forward for a newly arrived message.
 *
 * The rules are the sweep's rules, restated for one message at a time, and they have to
 * stay in step with it because between them they decide what the list says:
 *
 * - Anything bumps **recency**, a tapback included — that is how iMessage behaves.
 * - Only real speech becomes the **preview**. A group event has no text of its own and
 *   would blank the row; a tapback gets surfaced as "Liz loved an image" instead, with the
 *   older real message left underneath as the fallback.
 * - **Unread** is decided by the newest non-group-event message: incoming with no
 *   `dateRead` means unread account-wide, since chat.db stamps that when the chat is read
 *   on any device. Group events never get a stamp and would pin the mark on forever.
 *
 * An out-of-order message — the delta pages ASC, but a room can still hand back something
 * older than the row already shows — leaves the display fields alone.
 */
fun Conversation.advancedBy(
    message: ChatMessage,
    findTarget: (String) -> ChatMessage?,
): Conversation {
    if (message.date < lastDate) return this
    val speech = !message.isReaction && !message.isGroupEvent
    return copy(
        lastDate = message.date,
        lastText = if (speech) message.previewText else lastText,
        lastFromMe = if (speech) message.fromMe else lastFromMe,
        lastSender = if (speech) message.sender else lastSender,
        // Set only when the newest thing is a real tapback; a removal clears it and just
        // bumps recency.
        lastReaction = if (message.isReaction) message.reactionPreview(findTarget) else null,
        unread = if (message.isGroupEvent) unread else !message.fromMe && message.dateRead == 0L,
    )
}
