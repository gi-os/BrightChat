package com.gios.lightchat.api

import com.gios.lightchat.Attachment
import com.gios.lightchat.ChatMessage
import com.gios.lightchat.Conversation
import com.gios.lightchat.IncomingMessage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Carries the HTTP status so the ViewModel can treat 401/403 as a bad password. */
class ApiException(val code: Int, message: String) : IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

/**
 * What `GET /server/info` tells us. [reachable] is the password/connectivity check
 * setup relies on; [privateApiReady] is true only when the server has the Private
 * API enabled *and* the Messages helper is actually connected — the gate for
 * offering tapbacks (sending needs both; the SIP/helper setup is in the README).
 */
data class ServerInfo(val reachable: Boolean, val privateApiReady: Boolean)

/**
 * The result of a message sweep: the collapsed conversation list, the oldest message date
 * the sweep actually saw, and whether it reached back past the date it was asked to cover.
 *
 * [complete] is the part that matters to the catch-up poll. False means there is a gap
 * between the watermark and the oldest thing looked at — messages that exist, are unread,
 * and were not examined — so the watermark must not be advanced as if they'd been handled.
 */
data class Sweep(
    val conversations: List<Conversation>,
    val oldestDate: Long,
    val complete: Boolean,
)

/**
 * Minimal BlueBubbles Server REST client — plain [HttpURLConnection] + `org.json`,
 * no networking dependency. Auth is the server password passed as
 * the `password` query param on every call. The base URL is the user-configured
 * server (`Store.baseUrl`), typically reached over Tailscale Serve, passed into
 * the constructor. The live event feed is separate (Socket.IO, see the `socket`
 * package); message parsing is shared with it via the [companion object].
 *
 * Timestamps come back as epoch millis and are carried through unchanged — the UI
 * sorts on them, which is the whole point of doing this from scratch.
 */
class BlueBubblesApi(private val baseUrl: String, private val password: String) {

    /**
     * `GET /api/v1/server/info` — validates the URL/password (setup relies on
     * [ServerInfo.reachable]) and reads whether the Private API is live
     * (`private_api` && `helper_connected`), so the app can offer tapbacks only
     * when the server can actually send them.
     */
    fun serverInfo(): ServerInfo {
        val (code, text) = request("GET", "/api/v1/server/info", null)
        if (code !in 200..299) return ServerInfo(reachable = false, privateApiReady = false)
        val data = dataObject(text)
        val privateApi = data?.optBoolean("private_api", false) == true
        val helper = data?.optBoolean("helper_connected", false) == true
        return ServerInfo(reachable = true, privateApiReady = privateApi && helper)
    }

    /**
     * The conversation list, in true most-recent-activity order.
     *
     * Deliberately NOT built from `chat/query`: that endpoint sorts by an
     * unreliable `lastmessage` cache, so on a large account (this one has ~2700
     * chats) a freshly-active chat can fall outside its first page entirely and
     * never appear. Instead we drive the list from a single `message/query` DESC
     * sweep — the newest [limit] messages globally — taking the first (newest)
     * message seen per chat and reading each chat's metadata from the embedded
     * chat object (`with:["chats","chats.participants"]`). The result is already
     * newest-first; the ViewModel re-sorts defensively.
     *
     * Trade-off: only chats with activity inside the sweep window appear — i.e.
     * the recently-active ones, which is exactly what a messages list shows.
     *
     * A group that iMessage has forked into sibling rooms (same name + participants,
     * different guid — messages split across rooms by "era") is collapsed into one
     * conversation spanning all those guids, so it shows as a single row and its
     * thread merges messages from every room (see [groupIdentity] + ChatViewModel.open).
     */
    fun conversations(limit: Int = 1000): List<Conversation> = sweep(limit).conversations

    /**
     * `POST /api/v1/message/query` — one page of the DESC message sweep, raw.
     */
    private fun messagePage(limit: Int, offset: Int, timeoutMs: Int = READ_TIMEOUT_MS): JSONArray {
        val body = JSONObject()
            .put("limit", limit)
            .put("offset", offset)
            // `attachment` so an attachment-only message gets a real list preview
            // ("Photo" etc.) rather than a blank line; without it the sweep can't
            // tell text-less messages apart from genuinely empty ones.
            .put("with", JSONArray().put("chats").put("chats.participants").put("attachment"))
            .put("sort", "DESC")
        val respText = requestChecked(
            "POST",
            "/api/v1/message/query",
            body,
            what = "message/query",
            connectTimeoutMs = minOf(CONNECT_TIMEOUT_MS, timeoutMs),
            readTimeoutMs = timeoutMs,
        )
        return JSONObject(respText).optJSONArray("data") ?: JSONArray()
    }

    /**
     * The newest message date on the account, and nothing else.
     *
     * This exists because [sweep] is too expensive to be the *question* "is there anything
     * new". A Doze alarm grants roughly ten seconds of network (see [com.gios.lightchat.PollAlarm]),
     * and a full sweep — dozens of messages, every chat's participants, attachment metadata
     * — over a Tailscale tunnel whose radio just woke up can spend all of that on the
     * handshake alone. When it does, the connection is cut mid-response, the failure is
     * indistinguishable from "nothing new", and the missed message stays missed for another
     * interval. So the catch-up asks this first: three messages, no embedded objects, one
     * small response that fits the window, and it only pays for the sweep when the answer
     * says something actually arrived — by which point the radio is warm.
     *
     * Three rather than one because the newest row can be a tapback or a group event, and
     * the max date across a few is the same answer for less than a packet more.
     */
    fun newestMessageDate(): Long {
        val body = JSONObject()
            .put("limit", 3)
            .put("offset", 0)
            .put("with", JSONArray())
            .put("sort", "DESC")
        val respText = requestChecked(
            "POST",
            "/api/v1/message/query",
            body,
            what = "message/query probe",
            connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PROBE_READ_TIMEOUT_MS,
        )
        val data = JSONObject(respText).optJSONArray("data") ?: JSONArray()
        var newest = 0L
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            newest = maxOf(newest, messageDate(m))
        }
        return newest
    }

    /**
     * The conversation list, plus how far back the sweep that built it actually reached.
     *
     * [coverBackTo] is the caller's watermark. A single page is a fixed number of
     * *messages*, not chats, so one busy group can fill it entirely and hide every other
     * chat's activity behind it — and a catch-up that then advances its watermark to the
     * newest date it saw has silently written off the messages it never looked at. That is
     * the failure mode of a phone left off for a few hours: the backlog overflows the page
     * and the older half of it is skipped rather than delivered. So when a watermark is
     * given, keep paging until the sweep reaches past it (or [maxPages] runs out), and
     * report in [Sweep.complete] whether it got there.
     *
     * The default single page is the UI's behaviour, unchanged: the list only ever needs
     * the recent end of history.
     */
    fun sweep(
        pageSize: Int,
        coverBackTo: Long = 0L,
        maxPages: Int = 1,
        budgetMs: Long = Long.MAX_VALUE,
        pageTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Sweep {
        val started = System.currentTimeMillis()
        val data = JSONArray()
        var oldest = Long.MAX_VALUE
        var pages = 0
        var complete = coverBackTo <= 0L
        while (pages < maxPages) {
            val page = if (pages == 0) {
                // The first page is the whole result when there's no backlog, so its
                // failure is the caller's failure and must propagate.
                messagePage(pageSize, offset = 0, timeoutMs = pageTimeoutMs)
            } else {
                // Later pages are best-effort. Throwing here would discard every page
                // already fetched and, on a slow link, would do so on every single poll —
                // a permanent retry loop that never delivers anything. Stopping early
                // leaves a gap the caller is told about instead.
                runCatching { messagePage(pageSize, offset = pageOffset(pages, pageSize), timeoutMs = pageTimeoutMs) }
                    .getOrNull() ?: break
            }
            pages++
            for (i in 0 until page.length()) {
                val m = page.optJSONObject(i) ?: continue
                data.put(m)
                oldest = minOf(oldest, messageDate(m))
            }
            // Short page means we reached the start of history: there is nothing older to
            // miss, so the sweep is complete by definition however deep the watermark is.
            if (page.length() < pageSize) { complete = true; break }
            if (coverBackTo <= 0L) break
            if (oldest <= coverBackTo) { complete = true; break }
            // A Doze alarm's network window is finite and paging is the thing most likely
            // to overrun it. Better a known-partial sweep now than a cut connection and a
            // recorded failure.
            if (System.currentTimeMillis() - started > budgetMs) break
        }
        return Sweep(collapse(data), if (oldest == Long.MAX_VALUE) 0L else oldest, complete)
    }

    /**
     * Page offsets, overlapping by [PAGE_OVERLAP] rows.
     *
     * A plain `page * pageSize` offset is a moving window over a list that grows at the
     * newest end: a message arriving mid-sweep shifts everything down by one, and the row
     * on the page boundary is never returned by either page. The caller then advances its
     * watermark past a message it never saw. The overlap re-reads the last few rows of the
     * previous page, which costs nothing (the collapse is keyed by chat, so duplicates
     * fold) and absorbs up to [PAGE_OVERLAP] arrivals per page.
     */
    private fun pageOffset(page: Int, pageSize: Int): Int = page * (pageSize - PAGE_OVERLAP)

    private fun collapse(data: JSONArray): List<Conversation> {
        // Parse every swept message once, indexed by guid, so a tapback can describe
        // its target ("an image" vs a quote) for the list. The target is an *older*
        // message (later in this DESC sweep), so we need the full index up front.
        val rows = ArrayList<Pair<JSONArray, ChatMessage>>(data.length())
        val msgByGuid = HashMap<String, ChatMessage>()
        // Newest non-reaction message date per room — the signal for which sibling of a
        // forked group is the *live* one (the send target). A tapback can land in a dead
        // old room, so the newest message of *any* kind isn't a reliable send target.
        val realDateByGuid = HashMap<String, Long>()
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val chats = m.optJSONArray("chats") ?: continue
            val msg = parseMessage(m)
            rows.add(chats to msg)
            msgByGuid[msg.guid] = msg
        }
        // Pass 1: one row per chat room, newest activity first. The newest message
        // sets recency (lastDate) — a tapback bumps the thread, like iMessage. When
        // that newest message is a tapback we surface it as "Liz loved an image"
        // ([lastReaction]); otherwise the preview is the newest real message text, and
        // a row whose newest message is a *reaction removal* falls back to that too.
        val byGuid = LinkedHashMap<String, Conversation>()
        val previewFinal = HashSet<String>() // guids whose text preview is a real (non-reaction) message
        // Per room: is its newest incoming message still unread? Decided by the first
        // (= newest, DESC sweep) non-group-event message seen — chat.db stamps
        // dateRead on incoming messages when the chat is read on any device, so
        // `!fromMe && dateRead == 0` means unread account-wide. Group events are
        // skipped: they never get a dateRead and would pin the marker forever.
        val unreadByGuid = HashMap<String, Boolean>()
        for ((chats, msg) in rows) {
            for (j in 0 until chats.length()) {
                val chat = chats.getJSONObject(j)
                val guid = chat.optString("guid")
                if (guid.isBlank()) continue
                if (!msg.isReaction) {
                    realDateByGuid[guid] = maxOf(realDateByGuid[guid] ?: 0L, msg.date)
                }
                if (!msg.isGroupEvent && guid !in unreadByGuid) {
                    unreadByGuid[guid] = !msg.fromMe && msg.dateRead == 0L
                }
                val existing = byGuid[guid]
                // Group events (renames, member changes) bump recency like anything
                // else, but the *text* preview should be real speech — they have no
                // text of their own and would otherwise blank the row's preview.
                val isSpeech = !msg.isReaction && !msg.isGroupEvent
                if (existing == null) {
                    // [lastReaction] is set only when the newest message is a real
                    // tapback; [lastText] still gets the older real message below as a
                    // fallback. A reaction *removal* sets neither and just bumps recency.
                    val reaction = msg.reactionPreview { g -> msgByGuid[g] }
                    byGuid[guid] =
                        chatToConversation(chat, guid, msg.previewText, msg.date, msg.fromMe, msg.sender)
                            .copy(lastReaction = reaction)
                    if (isSpeech) previewFinal.add(guid)
                } else if (isSpeech && guid !in previewFinal) {
                    // Older than the row's newest message, but the first real one —
                    // upgrade the text preview while keeping the newest date / reaction.
                    // lastSender moves with lastText — they are one statement about one
                    // message, and split apart the row credits the wrong person.
                    byGuid[guid] = existing.copy(
                        lastText = msg.previewText,
                        lastFromMe = msg.fromMe,
                        lastSender = msg.sender,
                    )
                    previewFinal.add(guid)
                }
            }
        }
        // Pass 2: collapse a group that iMessage has forked into sibling rooms (same
        // name + identical participants, different guid) into a single conversation
        // spanning all their guids. Keyed only for groups; 1:1 chats are left alone.
        val groups = LinkedHashMap<String, MutableList<Conversation>>()
        for ((guid, conv) in byGuid) {
            val key = groupIdentity(conv) ?: guid // non-groups key by their own guid (never merge)
            groups.getOrPut(key) { mutableListOf() }.add(conv.copy(unread = unreadByGuid[guid] == true))
        }
        return groups.values.map { rooms ->
            if (rooms.size == 1) return@map rooms[0]
            // A forked group. Display fields (preview, recency, name) come from the
            // newest-overall room, so a tapback in any room still bumps the list. But the
            // *send target* — Conversation.guid / guids[0] — must be the live room: the
            // one iMessage routes real (non-reaction) messages to. A tapback can land in a
            // dead old room and make it newest by date, so ordering by the newest message
            // of any kind would aim sends at a room AppleScript can't send to ("Couldn't
            // send"). Order the spanned guids by newest non-reaction message instead.
            val display = rooms.maxByOrNull { it.lastDate } ?: rooms[0]
            val sendOrder = rooms.sortedByDescending { realDateByGuid[it.guid] ?: 0L }
            display.copy(guid = sendOrder.first().guid, guids = sendOrder.map { it.guid })
        }
    }

    /** A stable identity for a group, so forked sibling rooms collapse: its display
     *  name plus its sorted participant set. Null for 1:1s / participant-less chats —
     *  those never merge (keyed by their own guid). */
    private fun groupIdentity(c: Conversation): String? {
        if (!c.isGroup || c.participants.isEmpty()) return null
        return "g|${c.displayName}|${c.participants.sorted().joinToString(",")}"
    }

    /**
     * A page of raw message JSON, filtered by date — the primitive the local store syncs
     * against.
     *
     * `after` and `before` are epoch millis and both are **inclusive** on the server
     * (`message.date >= after`, `<= before`). Inclusive is the right side to err on: an
     * exclusive `after` built by adding a millisecond would silently drop a message that
     * shared its timestamp with the last one synced, and re-receiving the boundary message
     * costs nothing because the store writes by (chat, guid).
     *
     * Returned raw rather than as [ChatMessage] because the store keeps the original JSON
     * and the caller needs the embedded `chats` array to know which room each message
     * belongs to.
     */
    fun messagePageJson(
        after: Long? = null,
        before: Long? = null,
        chatGuid: String? = null,
        limit: Int = 100,
        offset: Int = 0,
        sort: String = "DESC",
        withChats: Boolean = true,
    ): JSONArray {
        val with = JSONArray().put("attachment")
        if (withChats) with.put("chats").put("chats.participants")
        val body = JSONObject()
            .put("limit", limit.coerceAtMost(MAX_QUERY_LIMIT))
            .put("offset", offset)
            .put("with", with)
            .put("sort", sort)
        if (after != null && after > 0) body.put("after", after)
        if (before != null && before > 0) body.put("before", before)
        if (chatGuid != null) body.put("chatGuid", chatGuid)
        val respText = requestChecked("POST", "/api/v1/message/query", body, what = "message/query")
        return JSONObject(respText).optJSONArray("data") ?: JSONArray()
    }

    /**
     * How many messages have had their *status* change since [after] — a delivery or read
     * stamp, or an edit.
     *
     * The reason this is a count and not a fetch is that the server exposes no route for
     * the messages themselves: `getUpdatedMessages` exists in its database layer but only
     * `count/updated` is routed. So this is a probe. Non-zero means "something you already
     * hold is now wrong", which is worth a cheap re-read of the recent window; the live
     * socket is what normally carries those updates, and this covers the stretches when
     * the socket wasn't there to hear them.
     */
    fun updatedCount(after: Long): Int {
        val text = requestChecked(
            "GET",
            "/api/v1/message/count/updated",
            null,
            what = "message/count/updated",
            extraQuery = "after=" + after,
        )
        return dataObject(text)?.optInt("total", 0) ?: 0
    }

    /** `GET /api/v1/chat/:guid/message` — messages in one conversation, newest first. */
    fun messages(chatGuid: String, limit: Int = 100, offset: Int = 0): List<ChatMessage> {
        val path = "/api/v1/chat/${enc(chatGuid)}/message"
        val query = "with=handle,attachment&sort=DESC&limit=$limit&offset=$offset"
        val text = requestChecked("GET", path, null, what = "message query", extraQuery = query)
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { parseMessage(data.getJSONObject(it)) }
    }

    /**
     * `POST /api/v1/message/text` — sends a text into a chat. Only `chatGuid` and
     * `message` are required; we also pass a client `tempGuid` so the echoed
     * new-message can be correlated. [method] is `private-api` when the server's
     * Private API is live, else `apple-script`. We prefer `private-api` for group
     * chats: the server's AppleScript path tries the group-capable `sendMessage`
     * script first, but if that fails to resolve the chat (e.g. our guids carry an
     * `any;+;chat…` service prefix that `chat id "…"` may not match) it falls back to
     * the DM-only script, which errors with "Can't use the send message (fallback)
     * script to text a group chat!". The Private API resolves the chat by its DB
     * identity and sidesteps that. (Plain AppleScript *can* text groups when the
     * standard script resolves — this isn't a hard limitation.) Returns the created
     * message (real guid) parsed from the response, falling back to a synthetic one
     * if the body is unexpected.
     */
    fun send(
        chatGuid: String,
        text: String,
        tempGuid: String,
        method: String = "apple-script",
        replyToGuid: String? = null,
    ): ChatMessage {
        val body = JSONObject()
            .put("chatGuid", chatGuid)
            .put("tempGuid", tempGuid)
            .put("message", text)
            .put("method", method)
        if (replyToGuid != null) {
            // An inline reply (`selectedMessageGuid`) — Private-API only; callers
            // gate the reply UI on the Private API being live.
            body.put("selectedMessageGuid", replyToGuid)
            body.put("partIndex", 0)
        }
        val resp = requestChecked("POST", "/api/v1/message/text", body, what = "send")
        return dataObject(resp)?.let { parseMessage(it) }
            ?: ChatMessage(tempGuid, text, System.currentTimeMillis(), fromMe = true, sender = null)
    }

    /**
     * `POST /api/v1/message/:guid/edit` — changes the text of a message this account sent.
     *
     * Private-API only, and the Mac has to be on Ventura or later; the server resolves
     * the chat from the message, so no chat guid travels. `backwardsCompatibilityMessage`
     * is what a device too old to understand edits sees as a fresh message — the edited
     * text itself, which is what Messages does. `partIndex` 0: this app never sends a
     * multipart message. Returns the message as it stands after the edit, `dateEdited`
     * set and `text` already the new words, so the caller can put the server's version of
     * the row in place of its optimistic one.
     */
    fun edit(messageGuid: String, text: String, partIndex: Int = 0): ChatMessage {
        val body = JSONObject()
            .put("editedMessage", text)
            .put("backwardsCompatibilityMessage", text)
            .put("partIndex", partIndex)
        val resp = requestChecked("POST", "/api/v1/message/${enc(messageGuid)}/edit", body, what = "edit")
        return dataObject(resp)?.let { parseMessage(it) }
            ?: throw IOException("edit: no message returned")
    }

    /**
     * `POST /api/v1/message/react` — sends a tapback onto [selectedMessageGuid].
     * Private-API only (gated in the UI on [serverInfo]); [reaction] is a
     * [ReactionType.apiValue], prefixed `-` to remove. [partIndex] is 0 for a
     * normal single-part message. Returns the created reaction message (real guid)
     * parsed from the response, so the ViewModel can reconcile its optimistic echo.
     */
    fun react(
        chatGuid: String,
        selectedMessageGuid: String,
        reaction: String,
        partIndex: Int = 0,
    ): ChatMessage {
        val body = JSONObject()
            .put("chatGuid", chatGuid)
            .put("selectedMessageGuid", selectedMessageGuid)
            .put("reaction", reaction)
            .put("partIndex", partIndex)
        val resp = requestChecked("POST", "/api/v1/message/react", body, what = "react")
        return dataObject(resp)?.let { parseMessage(it) }
            ?: throw IOException("react: no message returned")
    }

    /**
     * `POST /api/v1/chat/:guid/read` — marks the chat read (Private-API only).
     * Clears its unread state, which iMessage syncs to the account's other devices,
     * and sends a read receipt per the conversation's setting (so it just mirrors
     * reading on another device). Idempotent; callers fire it best-effort.
     */
    fun markRead(chatGuid: String) {
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/read", null, what = "mark read")
    }

    /**
     * `POST`/`DELETE /api/v1/chat/:guid/typing` — show or clear your typing bubble
     * on the other party's device (Private-API only). Best-effort; callers ignore
     * failures and gate on the Private API being live.
     */
    fun startTyping(chatGuid: String) {
        request("POST", "/api/v1/chat/${enc(chatGuid)}/typing", null)
    }

    fun stopTyping(chatGuid: String) {
        request("DELETE", "/api/v1/chat/${enc(chatGuid)}/typing", null)
    }

    /**
     * `DELETE /api/v1/chat/:guid` — permanently deletes a chat from Messages on the
     * Mac (Private-API only). The server tells the helper to delete it, then waits up
     * to ~30s for the local DB to reflect the removal, so this call can be slow.
     * Irreversible; with Messages-in-iCloud on it can also clear from other devices.
     */
    fun deleteChat(chatGuid: String) {
        requestChecked("DELETE", "/api/v1/chat/${enc(chatGuid)}", null, what = "delete chat")
    }

    /** `PUT /api/v1/chat/:guid` — renames a group (Private-API only; the server
     *  rejects renaming a 1:1). The rename shows to every member, like iMessage. */
    fun renameChat(chatGuid: String, displayName: String) {
        val body = JSONObject().put("displayName", displayName)
        requestChecked("PUT", "/api/v1/chat/${enc(chatGuid)}", body, what = "rename")
    }

    /** `POST /api/v1/chat/:guid/participant/add` — adds [address] to a group
     *  (Private-API only). iMessage may fork the group into a new room for the new
     *  membership; a refresh picks that up. */
    fun addParticipant(chatGuid: String, address: String) {
        val body = JSONObject().put("address", address)
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/participant/add", body, what = "add member")
    }

    /** `POST /api/v1/chat/:guid/participant/remove` — removes [address] from a
     *  group (Private-API only). */
    fun removeParticipant(chatGuid: String, address: String) {
        val body = JSONObject().put("address", address)
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/participant/remove", body, what = "remove member")
    }

    /** `POST /api/v1/chat/:guid/leave` — leaves a group (Private-API only). */
    fun leaveChat(chatGuid: String) {
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/leave", null, what = "leave")
    }

    /**
     * `GET /api/v1/handle/availability/imessage` — whether [address] can receive
     * iMessages (Private-API only). Used to warn before starting a chat with a
     * non-iMessage number — such a send otherwise *appears* to work and dies
     * silently on the Mac.
     */
    fun iMessageAvailable(address: String): Boolean {
        val text = requestChecked(
            "GET", "/api/v1/handle/availability/imessage", null,
            what = "availability", extraQuery = "address=${enc(address)}",
        )
        return dataObject(text)?.optBoolean("available", false) == true
    }

    /**
     * `POST /api/v1/message/attachment` — sends a file into a chat as multipart
     * form-data (the one call that isn't JSON, so it's built by hand rather than
     * via [request]). Like [send] we pass a `tempGuid` to correlate the echo and a
     * [method] (`private-api` when live, else `apple-script`); see [send] for why
     * `private-api` is preferred for group chats. Returns the created message parsed
     * from the response, falling back to a placeholder if the body is unexpected.
     */
    fun sendAttachment(
        chatGuid: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        tempGuid: String,
        method: String = "apple-script",
    ): ChatMessage = sendAttachment(chatGuid, filename, mimeType, tempGuid, method, bytes.size.toLong()) {
        it.write(bytes)
    }

    /**
     * The same send, streaming the file straight off disk.
     *
     * A photo off this phone is a couple of megabytes and holding it in a `ByteArray`
     * costs nothing worth counting. A video is not: a minute of 1080p is on the order
     * of 100MB, and `File.readBytes()` on one asks for a single contiguous allocation
     * of that size on a phone whose heap is a fraction of it. So video sends hand over
     * the [File] and the body is copied through a small buffer instead — the socket is
     * already in chunked mode, so nothing downstream had to change.
     */
    fun sendAttachment(
        chatGuid: String,
        file: File,
        filename: String,
        mimeType: String,
        tempGuid: String,
        method: String = "apple-script",
    ): ChatMessage = sendAttachment(chatGuid, filename, mimeType, tempGuid, method, file.length()) { out ->
        file.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
    }

    /**
     * Shared body of the two sends above: everything except how the bytes get onto the
     * wire, which [writeBody] supplies. [contentLength] is used only to pick a timeout
     * — see below.
     */
    private fun sendAttachment(
        chatGuid: String,
        filename: String,
        mimeType: String,
        tempGuid: String,
        method: String,
        contentLength: Long,
        writeBody: (java.io.OutputStream) -> Unit,
    ): ChatMessage {
        val safeFile = filename.replace("\"", "").ifBlank { "image.jpg" }
        val boundary = "chatBoundary" + tempGuid.filter { it.isLetterOrDigit() }
        val crlf = "\r\n"
        fun field(name: String, value: String) =
            "--$boundary$crlf" +
                "Content-Disposition: form-data; name=\"$name\"$crlf$crlf" +
                "$value$crlf"
        val preamble = buildString {
            append(field("chatGuid", chatGuid))
            append(field("tempGuid", tempGuid))
            append(field("name", safeFile))
            append(field("method", method))
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"attachment\"; filename=\"$safeFile\"$crlf")
            append("Content-Type: $mimeType$crlf$crlf")
        }
        val epilogue = "$crlf--$boundary--$crlf"

        val url = URL("$baseUrl/api/v1/message/attachment?password=" + enc(password))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            // Scaled to the payload, because this timeout is on the *response* and the
            // server only answers once it has taken the whole upload and handed it to
            // Messages. A fixed 60s is fine for a photo and is a guaranteed failure on a
            // clip over a slow link — and a timeout here doesn't cancel the send, it just
            // stops us hearing about it, so the message arrives and the app says it
            // didn't. Roughly a minute per 10MB, floored at the old value.
            readTimeout = (60_000L + contentLength / 10_000_000L * 60_000L)
                .coerceAtMost(MAX_UPLOAD_TIMEOUT_MS).toInt()
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(0) // stream the file, don't buffer it all in RAM
        }
        return try {
            conn.outputStream.use { out ->
                out.write(preamble.toByteArray(Charsets.UTF_8))
                writeBody(out)
                out.write(epilogue.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, "send attachment failed ($code)")
            dataObject(resp)?.let { parseMessage(it) }
                ?: ChatMessage(tempGuid, ChatMessage.ATTACHMENT_PLACEHOLDER, System.currentTimeMillis(), fromMe = true, sender = null)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `GET /api/v1/attachment/:guid/download` — streams an attachment's raw bytes
     * to [dest]. Used by the inline image loader; deliberately not routed through
     * [request] (which buffers a text body) since these are binary and large.
     */
    fun downloadAttachment(guid: String, dest: File) {
        val url = URL(buildString {
            append(baseUrl).append("/api/v1/attachment/").append(enc(guid)).append("/download")
            append("?password=").append(enc(password))
        })
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, "attachment download failed ($code)")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `POST /api/v1/chat/new` — starts a new chat by sending its first message.
     * macOS Big Sur+ requires a message (AppleScript can't create an empty chat),
     * so this both creates the chat and sends. Returns the new chat's guid.
     *
     * A single address goes over **AppleScript** (rock-solid, no Private API
     * needed). Two or more addresses form a **group**, which AppleScript can't do
     * reliably on modern macOS — that path requires `method:"private-api"`, so the
     * caller must gate group creation on the server's Private API being live. The
     * server assigns the group its own guid (`any;+;<hex>`, style 43), unguessable
     * client-side, so callers must use the returned guid rather than construct one.
     */
    fun newChat(addresses: List<String>, text: String, service: String = "iMessage"): String {
        val isGroup = addresses.size > 1
        val body = JSONObject()
            .put("addresses", JSONArray().apply { addresses.forEach { put(it) } })
            .put("message", text)
            .put("service", service)
            .put("method", if (isGroup) "private-api" else "apple-script")
        val resp = requestChecked("POST", "/api/v1/chat/new", body, what = "new chat")
        val guid = dataObject(resp)?.optString("guid")
        return guid?.takeIf { it.isNotBlank() } ?: throw IOException("new chat: no guid returned")
    }

    /**
     * `GET /api/v1/contact` — the Mac's whole address book, flattened to
     * (address, name) pairs (every phone number and email maps to the contact's
     * display name). [com.gios.lightchat.Contacts.from] turns this into a lookup.
     */
    fun contacts(): List<Pair<String, String>> {
        val text = requestChecked("GET", "/api/v1/contact", null, what = "contact")
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until data.length()) {
            val c = data.getJSONObject(i)
            val name = c.string("displayName").ifBlank {
                listOf(c.string("firstName"), c.string("lastName"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            if (name.isBlank()) continue
            for (field in listOf("phoneNumbers", "emails")) {
                val arr = c.optJSONArray(field) ?: continue
                for (j in 0 until arr.length()) {
                    arr.getJSONObject(j).optString("address").takeIf { it.isNotBlank() }
                        ?.let { out.add(it to name) }
                }
            }
        }
        return out
    }

    // ---- parsing ----------------------------------------------------------

    private fun chatToConversation(
        chat: JSONObject,
        guid: String,
        lastText: String,
        lastDate: Long,
        lastFromMe: Boolean,
        lastSender: String?,
    ): Conversation =
        Companion.chatToConversation(chat, guid, lastText, lastDate, lastFromMe, lastSender)

    // ---- transport --------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** [request], returning the body text and throwing an [ApiException] labelled
     *  [what] on a non-2xx status — the shape almost every endpoint wants. */
    private fun requestChecked(
        method: String,
        path: String,
        body: JSONObject?,
        what: String,
        extraQuery: String? = null,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String {
        val (code, text) = request(method, path, body, extraQuery, connectTimeoutMs, readTimeoutMs)
        if (code !in 200..299) throw ApiException(code, "$what failed ($code)")
        return text
    }

    /** The response body's `data` object, or null when the shape is unexpected. */
    private fun dataObject(resp: String): JSONObject? =
        runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull()

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        extraQuery: String? = null,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Pair<Int, String> {
        val url = buildString {
            append(baseUrl).append(path)
            append("?password=").append(enc(password))
            if (extraQuery != null) append("&").append(extraQuery)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Shared message parsing. Lives in the companion so the Socket.IO service can
     * decode `new-message`/`updated-message` payloads with the same logic the REST
     * calls use — the socket emits the same message-object shape.
     */
    companion object {
        /**
         * Ceiling on the upload response timeout. Ten minutes is longer than any send
         * this app permits (see the size cap in ChatViewModel) should ever take, and
         * short enough that a genuinely wedged connection still ends in an error
         * rather than a spinner nobody can clear.
         */
        private const val MAX_UPLOAD_TIMEOUT_MS = 10 * 60 * 1000L

        /** The server rejects anything larger (`limit: "numeric|min:1|max:1000"`). */
        const val MAX_QUERY_LIMIT = 1000

        /** Interactive defaults: the user is looking at a spinner, so waiting is fine. */
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000

        /**
         * The probe's budget. A Doze alarm's network window is about ten seconds in total,
         * so the interactive timeouts (up to 35s combined) would guarantee the connection
         * is cut before they ever expire — the request would sit there being killed rather
         * than failing fast enough to retry inside the same window.
         */
        private const val PROBE_CONNECT_TIMEOUT_MS = 4_000
        private const val PROBE_READ_TIMEOUT_MS = 4_000

        /** Rows each page of a multi-page sweep re-reads from the one before it. */
        private const val PAGE_OVERLAP = 5

        /** A raw message row's creation date. Used by the sweep and the probe, which both
         *  need only the date and shouldn't pay to parse a whole [ChatMessage] for it. */
        fun messageDate(o: JSONObject): Long = o.optLong("dateCreated", 0L)


        /**
         * A chat's participant handles.
         *
         * 1:1 chats (style 45) come back with an empty `participants` list, but the
         * `chatIdentifier` is the other party's address — use that so names resolve.
         */
        fun chatParticipants(chat: JSONObject): List<String> {
            val listed = chat.optJSONArray("participants")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    arr.getJSONObject(it).optString("address").takeIf { a -> a.isNotBlank() }
                }
            } ?: emptyList()
            return listed.ifEmpty {
                chat.optString("chatIdentifier")
                    .takeIf { it.isNotBlank() && !it.startsWith("chat") }
                    ?.let { listOf(it) }
                    ?: emptyList()
            }
        }

        /**
         * A chat object plus the message that is currently its newest, as a list row.
         *
         * In the companion because two callers build one: the full sweep, and the delta
         * sync meeting a chat for the first time. Two implementations of "what does this
         * row say" is how the list ends up disagreeing with itself.
         */
        fun chatToConversation(
            chat: JSONObject,
            guid: String,
            lastText: String,
            lastDate: Long,
            lastFromMe: Boolean,
            /** Who sent [lastText] — null when it was me. Passed rather than read off the
             *  chat because the chat object doesn't carry it; only the message does. */
            lastSender: String?,
        ): Conversation {
            return Conversation(
                guid = guid,
                displayName = chat.string("displayName"),
                participants = chatParticipants(chat),
                isGroup = chat.optInt("style") == 43, // 43 = group, 45 = one-on-one
                lastText = lastText,
                lastDate = lastDate,
                lastFromMe = lastFromMe,
                lastSender = lastSender,
            )
        }

        /** As [chatToConversation], taking the fields off [message] — the delta's form. */
        fun conversationFrom(chat: JSONObject, guid: String, message: ChatMessage): Conversation {
            val speech = !message.isReaction && !message.isGroupEvent
            return chatToConversation(
                chat = chat,
                guid = guid,
                lastText = if (speech) message.previewText else "",
                lastDate = message.date,
                lastFromMe = message.fromMe,
                lastSender = if (speech) message.sender else null,
            ).copy(
                unread = !message.isGroupEvent && !message.fromMe && message.dateRead == 0L,
            )
        }

        /** Body text, falling back to an attachment placeholder when null/blank. */
        fun messageText(o: JSONObject): String {
            // org.json's optString returns the literal "null" (not the fallback) for
            // an explicit JSON null, so guard with isNull — otherwise a text-less
            // message renders the word "null".
            val t = if (o.isNull("text")) "" else o.optString("text", "").trim()
            if (t.isNotEmpty()) return t
            val attachments = o.optJSONArray("attachments")?.length() ?: 0
            return if (attachments > 0) ChatMessage.ATTACHMENT_PLACEHOLDER else ""
        }

        fun parseMessage(o: JSONObject): ChatMessage {
            val handle = o.optJSONObject("handle")
            return ChatMessage(
                guid = o.optString("guid"),
                text = messageText(o),
                date = o.optLong("dateCreated", 0L),
                fromMe = o.optBoolean("isFromMe", false),
                sender = handle?.optString("address")?.takeIf { it.isNotBlank() },
                attachments = parseAttachments(o),
                // Delivery receipts (epoch millis, 0/null until they happen).
                dateDelivered = o.optLong("dateDelivered", 0L),
                dateRead = o.optLong("dateRead", 0L),
                // Group-system rows (renames, member changes) — no text of their
                // own; rendered as centered event lines, not message turns.
                itemType = o.optInt("itemType", 0),
                groupTitle = o.optString("groupTitle").takeIf { it.isNotBlank() && it != "null" },
                groupActionType = o.optInt("groupActionType", 0),
                // Non-zero = this sent message failed to deliver ("Not delivered").
                error = o.optInt("error", 0),
                // Present when the message is an inline reply to an earlier one.
                threadOriginatorGuid = o.optString("threadOriginatorGuid")
                    .takeIf { it.isNotBlank() && it != "null" },
                // Present only on tapbacks; the ViewModel folds such messages onto
                // their target rather than rendering them. The server reports the
                // type as a word (`love`/`-love`), not the raw iMessage int.
                associatedMessageGuid = o.optString("associatedMessageGuid").takeIf { it.isNotBlank() },
                associatedMessageType = o.optString("associatedMessageType").takeIf { it.isNotBlank() && it != "null" },
                tempGuid = o.optString("tempGuid").takeIf { it.isNotBlank() && it != "null" },
                // Explicit JSON null on an unedited message; optLong treats it as absent.
                dateEdited = o.optLong("dateEdited", 0L),
            )
        }

        private fun parseAttachments(o: JSONObject): List<Attachment> {
            val arr = o.optJSONArray("attachments") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val a = arr.getJSONObject(i)
                val guid = a.optString("guid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = a.optString("transferName").takeIf { it.isNotBlank() }
                // iMessage rich-link previews (sent for URLs — Instagram, etc.) ride
                // along as a `*.pluginPayloadAttachment` metadata blob, not a real
                // file. We can't render the preview and the URL is already in the
                // message text, so drop it rather than show a junk "File · <guid>" row.
                if (name?.endsWith(".pluginPayloadAttachment", ignoreCase = true) == true) {
                    return@mapNotNull null
                }
                Attachment(
                    guid = guid,
                    mimeType = a.optString("mimeType").takeIf { it.isNotBlank() },
                    transferName = name,
                    width = a.optInt("width", 0),
                    height = a.optInt("height", 0),
                )
            }
        }

        /**
         * Decodes a socket message event into an [IncomingMessage], pulling the
         * chat guid + display name from the embedded `chats` array. Returns null if
         * the payload has no chat (can't route it).
         */
        fun messageEvent(data: JSONObject, isNew: Boolean): IncomingMessage? {
            val chats = data.optJSONArray("chats") ?: return null
            if (chats.length() == 0) return null
            val chat = chats.getJSONObject(0)
            val guid = chat.optString("guid").takeIf { it.isNotBlank() } ?: return null
            return IncomingMessage(
                chatGuid = guid,
                message = parseMessage(data),
                isNew = isNew,
                chatDisplayName = chat.string("displayName"),
                isGroup = chat.optInt("style") == 43, // 43 = group, 45 = one-on-one
                participants = chatParticipants(chat),
                // Stripped of `chats` to match what the sync stores: the chat object is
                // held once in its own table, not repeated on every message of that chat.
                raw = runCatching {
                    JSONObject(data.toString()).apply { remove("chats") }.toString()
                }.getOrDefault(""),
            )
        }
    }
}

/**
 * `optString` for values that may be JSON null.
 *
 * `JSONObject.optString(key, "")` does **not** return the fallback for an explicit
 * `null` — org.json stores it as the `JSONObject.NULL` sentinel, whose `toString()` is
 * `"null"`, and that string is what comes back. BlueBubbles sends `"displayName": null`
 * for a chat with no name, which is how the conversation list ended up with entries
 * literally titled "null" that counted as Known because the name was non-blank. The
 * literal string is treated as absent too: no real chat is called that.
 */
internal fun JSONObject.string(key: String): String {
    if (isNull(key)) return ""
    val value = optString(key, "")
    return if (value == "null") "" else value
}
