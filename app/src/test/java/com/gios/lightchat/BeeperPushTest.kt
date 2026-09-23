package com.gios.lightchat

import com.gios.lightchat.beeper.BeeperPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class BeeperPushTest {

    @Test fun topicIsUnifiedPushShaped() {
        val t = BeeperPush.newTopic(SecureRandom())
        assertEquals(14, t.length)
        assertTrue(t.startsWith("up"))
        assertTrue(t.all { it.isLetterOrDigit() })
    }

    @Test fun serverIsNormalized() {
        assertEquals("https://ntfy.sh", BeeperPush.normalizeServer(""))
        assertEquals("https://push.example.com", BeeperPush.normalizeServer(" push.example.com/ "))
        assertEquals("http://10.0.0.2:8080", BeeperPush.normalizeServer("http://10.0.0.2:8080"))
    }

    @Test fun urls() {
        assertEquals("https://ntfy.sh/upABC?up=1", BeeperPush.pushkey("https://ntfy.sh", "upABC"))
        assertEquals("https://ntfy.sh/upABC/json?up=1&since=all", BeeperPush.streamUrl("https://ntfy.sh", "upABC", null))
        assertEquals("https://ntfy.sh/upABC/json?up=1&since=x1", BeeperPush.streamUrl("https://ntfy.sh", "upABC", "x1"))
    }

    @Test fun parsesGatewayMessage() {
        val body = """{"notification":{"room_id":"!r:beeper.com","event_id":"${'$'}e","counts":{"unread":1}}}"""
        val line = org.json.JSONObject()
            .put("id", "m1").put("event", "message").put("topic", "upABC").put("message", body)
            .toString()
        val push = BeeperPush.parse(line)!!
        assertEquals("m1", push.id)
        assertEquals("!r:beeper.com", push.roomId)
        assertEquals("\$e", push.eventId)
    }

    @Test fun countsOnlyStillWakes() {
        val line = """{"id":"m2","event":"message","message":"{\"notification\":{\"counts\":{\"unread\":0}}}"}"""
        val push = BeeperPush.parse(line)!!
        assertNull(push.roomId)
        assertNull(push.eventId)
    }

    @Test fun keepalivesAreNotPushes() {
        assertNull(BeeperPush.parse("""{"id":"k","event":"keepalive","topic":"upABC"}"""))
        assertNull(BeeperPush.parse("""{"id":"o","event":"open"}"""))
        assertNull(BeeperPush.parse("not json"))
    }
}
