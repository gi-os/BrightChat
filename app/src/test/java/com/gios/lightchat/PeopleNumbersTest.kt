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
        else setOf(Contacts.key(c.participants.single()))

    @Test fun whatsappGhostIdCarriesTheNumber() {
        assertEquals(setOf("5551234567"), BeeperIdentities.fromUserId("@whatsapp_15551234567:beeper.local"))
        assertTrue(BeeperIdentities.fromUserId("@whatsapp_lid-8812:beeper.local").isEmpty())
        assertTrue(BeeperIdentities.fromUserId("@signal_4b1f:beeper.local").isEmpty())
    }

    @Test fun bridgeIdentifiersAreReadAsKeys() {
        val content = JSONObject()
            .put("membership", "join")
            .put("com.beeper.bridge.identifiers", JSONArray(listOf("tel:+1 (555) 123-4567", "mailto:Alex@Example.com", "signal:abc")))
        assertEquals(setOf("5551234567", "alex@example.com"), BeeperIdentities.fromMemberContent(content))
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
}
