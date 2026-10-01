package com.gios.lightchat

import com.gios.lightchat.beeper.BeeperIdentities
import com.gios.lightchat.people.People
import com.gios.lightchat.people.PeopleLinks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleNumbersTest {

    private fun imessage(handle: String, date: Long) =
        Conversation("iMessage;-;$handle", "", listOf(handle), false, "hi", date, false)

    private fun beeper(room: String, user: String, name: String, network: String, date: Long) =
        Conversation("mx:!$room:beeper.com", name, listOf(user), false, "yo", date, false, network = network)

    private fun keysOf(c: Conversation): Set<String> =
        if (c.isBeeper) BeeperIdentities.fromUserId(c.participants.single())
        else setOfNotNull(People.matchKey(c.participants.single()))

    @Test fun whatsappGhostIdCarriesTheNumber() {
        assertEquals(setOf("15551234567"), BeeperIdentities.fromUserId("@whatsapp_15551234567:beeper.local"))
        assertTrue(BeeperIdentities.fromUserId("@whatsapp_lid-8812:beeper.local").isEmpty())
        assertTrue(BeeperIdentities.fromUserId("@signal_4b1f:beeper.local").isEmpty())
    }

    @Test fun bridgeIdentifiersAreReadAsKeys() {
        val content = JSONObject()
            .put("membership", "join")
            .put("com.beeper.bridge.identifiers", JSONArray(listOf("tel:+1 (555) 123-4567", "mailto:Alex@Example.com", "signal:abc")))
        assertEquals(setOf("15551234567", "alex@example.com"), BeeperIdentities.fromMemberContent(content))
        assertTrue(BeeperIdentities.fromMemberContent(JSONObject().put("membership", "join")).isEmpty())
    }

    @Test fun sameNumberJoinsWhateverTheNames() {
        val list = listOf(
            imessage("+15551234567", 10),
            beeper("a", "@whatsapp_15551234567:beeper.local", "Al", "WhatsApp", 20),
        )
        val merged = People.merge(list, { null }, PeopleLinks(), emptySet(), ::keysOf)
        assertEquals(1, merged.size)
        assertTrue(merged.single().isPerson)
    }

    @Test fun aHandSplitBeatsANumber() {
        val a = imessage("+15551234567", 10)
        val b = beeper("a", "@whatsapp_15551234567:beeper.local", "Al", "WhatsApp", 20)
        val links = PeopleLinks().unjoin(a.guid, b.guid)
        assertTrue(People.merge(listOf(a, b), { null }, links, emptySet(), ::keysOf).none { it.isPerson })
    }

    @Test fun countriesDoNotCollide() {
        // +39 347 123 4567 and +1 347 123 4567 share their last ten digits.
        assertTrue(People.matchKey("+393471234567") != People.matchKey("+13471234567"))
        assertEquals("13471234567", People.matchKey("(347) 123-4567"))
        assertEquals(null, People.matchKey("22395"))
    }

    @Test fun chainedJoinsNeverPutTwoIMessageChatsInOneRow() {
        // "Alex Kim" on iMessage, a WhatsApp chat named Alex Kim whose number belongs to a second,
        // unsaved iMessage chat: name joins the first pair, number the second.
        val i1 = imessage("+15550000001", 10)
        val w = beeper("w", "@whatsapp_15550000002:beeper.local", "Alex Kim", "WhatsApp", 20)
        val i2 = imessage("+15550000002", 30)
        val names = { c: Conversation -> if (c.guid == i1.guid || c.isBeeper) "Alex Kim" else null }
        val merged = People.merge(listOf(i1, w, i2), names, PeopleLinks(), emptySet(), ::keysOf)
        assertTrue(merged.none { p -> p.members.count { !it.isBeeper } > 1 })
    }

    @Test fun rowGuidIsStableWithoutAnIMessageMember() {
        val a = beeper("a", "@whatsapp_15551112222:beeper.local", "Al", "WhatsApp", 10)
        val b = beeper("b", "@signal_x:beeper.local", "Al", "Signal", 20)
        val links = PeopleLinks().join(a.guid, b.guid)
        val first = People.merge(listOf(a, b), { null }, links, emptySet()).single()
        val later = People.merge(listOf(a.copy(lastDate = 30), b), { null }, links, emptySet()).single()
        assertEquals(first.guid, later.guid)
    }
}
