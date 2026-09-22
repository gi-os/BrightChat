package com.gios.lightchat

/**
 * Folding a live message into the open thread's raw list — **one row per guid, always.**
 *
 * A send puts an optimistic row up straight away, keyed by the client `tempGuid` (see
 * `ChatViewModel.sendPicked`), and the real message arrives twice afterwards: once as the
 * HTTP send's return value, once as the socket's `new-message` event, followed by an
 * `updated-message` for each delivery/read stamp. Any of those can land first, so the merge
 * has to match an incoming message against *either* the guid it now carries or the tempGuid
 * the optimistic row is still keyed by.
 *
 * The trap this exists to close: matching by tempGuid finds the optimistic row even when the
 * real row is already in the list — the socket echo appended it while the blocking send was
 * still uploading. Writing the real message over the optimistic slot then leaves **two rows
 * under one guid**, and the thread's `LazyColumn` does not tolerate that; it throws
 * `Key "…" was already used` and takes the app down. So the merge writes the matched slot and
 * drops every other row carrying that guid, which makes a duplicate key unrepresentable
 * rather than unlikely.
 *
 * No Android imports on purpose — this is the part worth testing, and it is tested in
 * `ThreadMergeTest`.
 */
/** The guid prefix a not-yet-acknowledged send carries — see `ChatViewModel.newTempGuid`. */
internal const val PENDING_PREFIX = "temp-"

/**
 * Replacing the thread with a freshly-fetched page **without throwing away sends still in
 * flight.**
 *
 * A fetch — opening the thread, the delta sync landing, scrolling back a page — comes from the
 * store, and the store only knows about messages the server has acknowledged. Assigning its
 * result straight over the thread therefore deletes every optimistic row currently uploading,
 * which is what made a batch of photos disappear mid-send: the bubbles vanished, and the send's
 * own reconcile then had no row left to swap the real message into, so the photo did not come
 * back until a later socket event or a reopen. Scrolling up while photos upload (which is what
 * you do while waiting) triggers it every time, through `loadOlder`.
 *
 * So a pending row survives the replacement — unless the fetch already contains the real message
 * it was standing in for, which is recognised by the server echoing our `tempGuid` back on it.
 * Without that second half the same photo would sit in the thread twice, once as the optimistic
 * row and once as the real one, under two different guids (so no crash, just a phantom).
 *
 * Pending rows go at the end because they are the newest thing in the thread by definition —
 * `foldReactions` re-sorts by date anyway, and they carry `System.currentTimeMillis()`.
 */
internal fun replaceKeepingPending(
    current: List<ChatMessage>,
    fetched: List<ChatMessage>,
): List<ChatMessage> {
    val pending = current.filter { it.guid.startsWith(PENDING_PREFIX) }
    if (pending.isEmpty()) return fetched
    val acknowledged = HashSet<String>(fetched.size * 2)
    for (m in fetched) {
        acknowledged.add(m.guid)
        m.tempGuid?.let(acknowledged::add)
    }
    return fetched + pending.filterNot { it.guid in acknowledged }
}

internal fun mergeIntoThread(list: List<ChatMessage>, incoming: ChatMessage): List<ChatMessage> {
    val idx = list.indexOfFirst {
        it.guid == incoming.guid || (incoming.tempGuid != null && it.guid == incoming.tempGuid)
    }
    if (idx < 0) return list + incoming
    val out = ArrayList<ChatMessage>(list.size)
    for ((i, existing) in list.withIndex()) {
        when {
            // The message's place in the thread is where it already sits — writing it
            // here rather than appending keeps a send from jumping to the bottom twice.
            //
            // **An update that does not know about an edit must not undo it.** After an
            // edit the message arrives several more times — the edit's own echo, then a
            // delivery stamp, then a read stamp — and a server or a path that serialized
            // one of them before the edit landed in chat.db carries the old words with no
            // `dateEdited`. Taking that row whole would flip the text back for a frame or
            // for good. So the words and the edit stamp only move forward.
            i == idx -> out.add(keepNewerEdit(existing, incoming))
            // The same message under a second row: the echo that arrived while the
            // optimistic copy was still up. It is the one being written above.
            existing.guid == incoming.guid -> Unit
            else -> out.add(existing)
        }
    }
    return out
}

/**
 * [incoming] with [existing]'s text and `dateEdited` kept when the existing row is the more
 * recently edited of the two. Everything else — receipts, error, reactions folded later —
 * is the update's to set.
 */
internal fun keepNewerEdit(existing: ChatMessage, incoming: ChatMessage): ChatMessage =
    if (existing.dateEdited > incoming.dateEdited) {
        incoming.copy(text = existing.text, dateEdited = existing.dateEdited)
    } else {
        incoming
    }
