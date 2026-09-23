package com.gios.lightchat

import com.gios.lightchat.people.People
import com.gios.lightchat.people.PeopleLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {

    private fun imessage(handle: String, date: Long, fromMe: Boolean = false) =
        Conversation("iMessage;-;$handle", "", listOf(handle), false, "hi $handle", date, fromMe)

    private fun beeper(room: String, name: String, network: String, date: Long, fromMe: Boolean = false) =
        Conversation("mx:!$room:beeper.com", name, listOf("@x_$room:beeper.local"), false, "yo", date, fromMe, network = network)

    private val names = mapOf("+15550001" to "Alex Kim", "+15550002" to "Sam Lee")

    private fun nameOf(c: Conversation): String? =
        if (c.isBeeper) c.displayName else names[c.participants.single()]

    @Test fun sameFullNameOnTwoNetworksIsOnePerson() {
        val list = listOf(imessage("+15550001", 10), beeper("a", "Alex Kim", "WhatsApp", 20), imessage("+15550002", 5))
        val merged = People.merge(list, ::nameOf, PeopleLinks(), emptySet())
        assertEquals(2, merged.size)
        val alex = merged.first { it.isPerson }
        assertEquals("iMessage;-;+15550001", alex.guid) // the iMessage chat keeps its guid
        assertEquals(20L, alex.lastDate) // the preview is the newest member's
        assertEquals("WhatsApp", alex.network)
        assertTrue("mx:!a:beeper.com" in alex.guids)
    }

    @Test fun ambiguousNamesJoinNothing() {
        val list = listOf(
            imessage("+15550001", 10),
            beeper("a", "Alex Kim", "WhatsApp", 20),
            beeper("b", "Alex Kim", "WhatsApp", 30),
        )
        assertTrue(People.merge(list, ::nameOf, PeopleLinks(), emptySet()).none { it.isPerson })
    }

    @Test fun firstNamesAloneNeverJoin() {
        val list = listOf(beeper("a", "Alex", "WhatsApp", 1), beeper("b", "Alex", "Signal", 2))
        assertTrue(People.merge(list, ::nameOf, PeopleLinks(), emptySet()).none { it.isPerson })
    }

    @Test fun groupsNeverJoin() {
        val g = beeper("g", "Alex Kim", "WhatsApp", 1).copy(isGroup = true)
        val list = listOf(imessage("+15550001", 2), g)
        assertTrue(People.merge(list, ::nameOf, PeopleLinks(), emptySet()).none { it.isPerson })
    }

    @Test fun aHandSplitSticksAndAHandJoinWins() {
        val a = imessage("+15550001", 10)
        val b = beeper("a", "Alex Kim", "WhatsApp", 20)
        val split = PeopleLinks().unjoin(a.guid, b.guid)
        assertTrue(People.merge(listOf(a, b), ::nameOf, split, emptySet()).none { it.isPerson })
        val c = beeper("c", "Mom", "Signal", 5)
        val joined = PeopleLinks().join(a.guid, c.guid)
        val merged = People.merge(listOf(a, c), ::nameOf, joined, emptySet())
        assertEquals(1, merged.size)
        assertTrue(merged.single().isPerson)
    }

    @Test fun aStarredMemberIsThePrimary() {
        val a = imessage("+15550001", 10)
        val b = beeper("a", "Alex Kim", "WhatsApp", 20)
        val merged = People.merge(listOf(a, b), ::nameOf, PeopleLinks(), setOf(b.guid))
        assertEquals(b.guid, merged.single().guid)
        assertFalse(merged.single().guids.isEmpty())
    }
}
