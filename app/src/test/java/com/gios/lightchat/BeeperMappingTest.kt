package com.gios.lightchat

import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.backend.Backend
import com.gios.lightchat.backend.Caps
import com.gios.lightchat.beeper.BeeperMapping
import com.gios.lightchat.beeper.BeeperReports
import com.gios.lightchat.beeper.ClaimFixEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeperMappingTest {

    @Test fun networkComesFromTheGhostPrefix() {
        assertEquals("WhatsApp", BeeperMapping.networkOfUser("@whatsapp_lid-123:beeper.local"))
        assertEquals("Signal", BeeperMapping.networkOfUser("@signal_0a1b:beeper.local"))
        assertEquals("Instagram", BeeperMapping.networkOfUser("@instagramgo_991:beeper.local"))
        assertEquals("Messenger", BeeperMapping.networkOfUser("@facebookgo_4:beeper.local"))
        assertNull(BeeperMapping.networkOfUser("@alex:beeper.com"))
    }

    @Test fun roomNetworkSkipsMeAndFallsBackToBeeper() {
        val me = "@gio:beeper.com"
        assertEquals("Telegram", BeeperMapping.networkOfRoom(listOf(me, "@telegram_77:beeper.local"), me))
        assertEquals("WhatsApp", BeeperMapping.networkOfRoom(listOf(me, "@whatsappbot:beeper.local"), me))
        assertEquals("Beeper", BeeperMapping.networkOfRoom(listOf(me, "@alex:beeper.com"), me))
    }

    @Test fun bridgeBotsAreNotPeople() {
        assertTrue(BeeperMapping.isBridgeBot("@whatsappbot:beeper.local"))
        assertFalse(BeeperMapping.isBridgeBot("@whatsapp_lid-1:beeper.local"))
        assertFalse(BeeperMapping.isBridgeBot("@alex:beeper.com"))
    }

    @Test fun reactionsSurviveVariationSelectorsAndSkinTones() {
        assertEquals(ReactionType.LOVE, BeeperMapping.reactionOf("❤"))
        assertEquals(ReactionType.LOVE, BeeperMapping.reactionOf("❤️"))
        assertEquals(ReactionType.LIKE, BeeperMapping.reactionOf("👍🏽"))
        assertNull(BeeperMapping.reactionOf("🦄")) // a unicorn has no tapback
        ReactionType.entries.forEach { type ->
            assertEquals(type, BeeperMapping.reactionOf(BeeperMapping.emojiFor(type)))
        }
    }

    @Test fun messageJsonParsesBackThroughTheOneParser() {
        val json = BeeperMapping.messageJson(
            BeeperMapping.Event(
                eventId = "\$ev1",
                sender = "@whatsapp_lid-1:beeper.local",
                senderName = "Alex",
                fromMe = false,
                timestamp = 1_700_000_000_000,
                text = "on my way",
                replyTo = "\$ev0",
            ),
            roomId = "!room:beeper.com",
        )
        val m = BlueBubblesApi.parseMessage(JSONObject(json.toString()))
        assertEquals("\$ev1", m.guid)
        assertEquals("on my way", m.text)
        assertEquals("Alex", m.sender)
        assertFalse(m.fromMe)
        assertEquals("\$ev0", m.threadOriginatorGuid)
    }

    @Test fun reactionAndItsRemovalFoldLikeTapbacks() {
        val add = BlueBubblesApi.parseMessage(
            BeeperMapping.messageJson(
                BeeperMapping.Event("\$r1", "@me", null, true, 1L, "", reactionTarget = "\$ev1", reactionKey = "😂"),
                "!r",
            ),
        )
        assertTrue(add.isReaction)
        assertEquals(ReactionType.LAUGH, add.reactionType)
        val removed = BlueBubblesApi.parseMessage(
            BeeperMapping.messageJson(
                BeeperMapping.Event("\$r1", "@me", null, true, 1L, "", reactionTarget = "\$ev1", reactionRemoval = ReactionType.LAUGH),
                "!r",
            ),
        )
        assertTrue(removed.isReactionRemoval)
    }

    @Test fun attachmentGuidsRoundTrip() {
        val guid = BeeperMapping.attachmentGuid("!abc:beeper.com", "\$e#v", 2)
        val ref = BeeperMapping.parseAttachmentGuid(guid)!!
        assertEquals("!abc:beeper.com", ref.roomId)
        assertEquals("\$e#v", ref.eventId)
        assertEquals(2, ref.index)
        assertNull(BeeperMapping.parseAttachmentGuid("iMessage;-;+1555"))
    }

    @Test fun replyFallbackIsCut() {
        assertEquals("sure", BeeperMapping.stripReplyFallback("> <@alex:beeper.com> lunch?\n\nsure"))
        assertEquals("plain", BeeperMapping.stripReplyFallback("plain"))
    }

    @Test fun claimResponseGetsItsFailures() {
        assertEquals("{\"failures\":{},\"one_time_keys\":{}}", ClaimFixEngine.patchClaim("{\"one_time_keys\":{}}"))
        assertEquals("{\"failures\":{}}", ClaimFixEngine.patchClaim("{}"))
        val compliant = "{\"failures\":{},\"one_time_keys\":{}}"
        assertEquals(compliant, ClaimFixEngine.patchClaim(compliant))
    }

    @Test fun routingIsByGuidPrefix() {
        assertEquals(Backend.BEEPER, Backend.of("mx:!room:beeper.com"))
        assertEquals(Backend.BLUEBUBBLES, Backend.of("iMessage;-;+15551234567"))
        assertEquals(Backend.AGENT, Backend.of("agent:7"))
    }

    @Test fun beeperChatsKeepTheirVerbsWithoutThePrivateApi() {
        val whatsapp = Conversation("mx:!r:beeper.com", "Alex", emptyList(), false, "", 0L, false, network = "WhatsApp")
        val imessage = Conversation("iMessage;-;+15551234567", "", emptyList(), false, "", 0L, false)
        assertTrue(Caps.of(whatsapp, privateApi = false).reactions)
        assertFalse(Caps.of(imessage, privateApi = false).reactions)
        assertTrue(Caps.of(imessage, privateApi = true).reactions)
        assertFalse(Caps.of(whatsapp, privateApi = true).deleteChat)
    }

    @Test fun reportsCarryNoIdentifiers() {
        val line = "12:00:01 signed in as @gio:beeper.com; code sent to g.lupo@example.com; " +
            "row !AbCd123:beeper.local failed on \$Xy_9zQwErTyUiOpAs01 mxc://beeper.com/abc123"
        val out = BeeperReports.redact(line)
        assertFalse(out.contains("gio"))
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("AbCd123"))
        assertFalse(out.contains("Xy_9zQ"))
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("<user:"))
        assertTrue(out.contains("<email:"))
        assertTrue(out.contains("<room:"))
        // Stable: the same id reads the same twice.
        assertEquals(BeeperReports.redact("@a:b.c"), BeeperReports.redact("@a:b.c"))
    }
}
