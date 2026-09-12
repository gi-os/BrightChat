package com.gios.lightchat

/**
 * What a `chat-read-status-changed` event is allowed to conclude.
 *
 * The event carries no timestamp, and the server's chat.db poller will happily report
 * `read: true` describing the state *before* a message that has just arrived. So the read
 * is verified by asking the server for the chat's newest messages — and that question has
 * three answers, not two. [UNKNOWN] is the one that used to be missing: the fetch failed,
 * or the rows came back with nothing in them that can carry a `dateRead`.
 *
 * Both callers act only on [READ]. Before this existed they each folded the third answer
 * into their own boolean, and folded it in opposite directions — `SocketService.newestIsRead`
 * returned `false` ("keep the alert", safe) while `ChatViewModel.applyReadStatus` returned
 * `false` for `stillUnread` ("drop the dot", wrong). Same literal, inverted meaning, so an
 * unreachable server cleared the unread mark on chats nobody had read.
 */
enum class ReadVerdict { READ, UNREAD, UNKNOWN }

object ReadReceipt {

    /**
     * How many of the chat's newest messages to judge on. More than one, because the newest
     * row is easily our own reply or a group event and both are silent on the question:
     * chat.db stamps `dateRead` on incoming messages only, and never on group-system rows.
     */
    const val VERIFY_LIMIT = 10

    /**
     * The verdict for a chat, given its newest messages as the server returned them.
     *
     * @param newest rows from `chat/:guid/message?sort=DESC`, or null if the fetch failed.
     *   Order does not matter — the newest qualifying row is picked by date.
     */
    fun verdict(newest: List<ChatMessage>?): ReadVerdict {
        if (newest == null) return ReadVerdict.UNKNOWN
        val incoming = newest
            .filterNot { it.fromMe || it.isGroupEvent }
            .maxByOrNull { it.date }
            ?: return ReadVerdict.UNKNOWN
        return if (incoming.dateRead == 0L) ReadVerdict.UNREAD else ReadVerdict.READ
    }
}
