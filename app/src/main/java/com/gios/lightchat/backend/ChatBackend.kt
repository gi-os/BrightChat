package com.gios.lightchat.backend

import com.gios.lightchat.Conversation
import com.gios.lightchat.beeper.BeeperMapping

/**
 * Which service a conversation belongs to, and what that service lets you do in it.
 *
 * ### Why this exists
 *
 * Until v2.44 there was one backend, so "can I react here?" was spelled `state.privateApi` in a
 * dozen places: whether the Mac's BlueBubbles server had its Private API helper running. A second
 * backend makes that question per conversation. A WhatsApp chat can take a tapback while the
 * Mac's helper is down, and an iMessage chat can't while it is.
 *
 * ### How routing works
 *
 * By guid. BlueBubbles guids look like `iMessage;-;+15551234567` and are stored exactly as they
 * always were, so pins, nicknames, notes and cached rows need no migration. Beeper guids are
 * `mx:!room:beeper.com`, the same trick `agent:<id>` already uses. [of] is the one place that
 * reads the prefix.
 */
enum class Backend {
    /** iMessage, through the Mac's BlueBubbles server. */
    BLUEBUBBLES,

    /** WhatsApp, Signal, Telegram, Instagram and the rest, through Beeper's bridges. */
    BEEPER,

    /** The AI agents: local rows that never reach a server. */
    AGENT,
    ;

    companion object {
        fun of(guid: String): Backend = when {
            guid.startsWith("agent:") -> AGENT
            BeeperMapping.isBeeper(guid) -> BEEPER
            else -> BLUEBUBBLES
        }

        fun of(conversation: Conversation): Backend = of(conversation.guid)
    }
}

/**
 * What a conversation's backend can do. Screens read this rather than a backend flag, so a
 * third backend only has to say what it supports.
 */
data class Caps(
    val reactions: Boolean,
    val replies: Boolean,
    val edits: Boolean,
    val typing: Boolean,
    val groupRename: Boolean,
    val groupMembers: Boolean,
    val deleteChat: Boolean,
) {
    companion object {
        /** BlueBubbles without the Private API: plain sends only (AppleScript). */
        val BASIC = Caps(
            reactions = false,
            replies = false,
            edits = false,
            typing = false,
            groupRename = false,
            groupMembers = false,
            deleteChat = false,
        )

        /** BlueBubbles with the Private API helper live. */
        val PRIVATE_API = Caps(
            reactions = true,
            replies = true,
            edits = true,
            typing = true,
            groupRename = true,
            groupMembers = true,
            deleteChat = true,
        )

        /**
         * Beeper. Everything Matrix carries, gated by the bridge at send time: a network that
         * can't edit (Instagram) refuses the edit and the bubble is put back. Adding people to a
         * bridged group needs the network's own identifiers and is not wired yet. Deleting a chat
         * would leave the room on every device, which is too much for a swipe.
         */
        val BEEPER = Caps(
            reactions = true,
            replies = true,
            edits = true,
            typing = true,
            groupRename = true,
            groupMembers = false,
            deleteChat = false,
        )

        fun of(conversation: Conversation?, privateApi: Boolean): Caps = when {
            conversation == null -> if (privateApi) PRIVATE_API else BASIC
            conversation.isAgent -> BASIC.copy(deleteChat = true)
            conversation.isBeeper -> BEEPER
            privateApi -> PRIVATE_API
            else -> BASIC
        }
    }
}
