package com.gios.lightchat.beeper

import android.content.Context
import com.gios.lightchat.Contacts
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone numbers and email addresses behind bridged people, so a person's WhatsApp chat can
 * join their iMessage one by number rather than by a name that has to match to the letter.
 *
 * Two sources, cheapest first:
 *
 * 1. **The ghost's own id.** WhatsApp's bridge names a number-based contact `@whatsapp_<digits>`,
 *    so the number is right there. Contacts WhatsApp only knows by its newer opaque ids
 *    (`whatsapp_lid-…`) carry nothing.
 * 2. **What the bridge says about them.** Beeper's bridges put `com.beeper.bridge.identifiers`
 *    (`tel:+15551234567`, `mailto:…`) on the ghost's room membership. Trixnity drops fields it has
 *    no type for, so [BeeperEngine] reads that one event raw, once per person, and this keeps the
 *    answer.
 *
 * Keys are [Contacts.key] form — the last ten digits, or a lowercased email — the same form the
 * address book and iMessage handles already use, so a match is a plain set intersection.
 */
object BeeperIdentities {
    private const val PREFS = "beeper_ids"

    /** Look again after this long when the bridge said nothing; people add numbers. */
    private const val RETRY_EMPTY_MS = 7L * 24 * 60 * 60 * 1000

    private val WHATSAPP_NUMBER = Regex("^@whatsapp_(\\d{7,15}):")

    private data class Entry(val keys: Set<String>, val checkedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile private var loaded = false

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val all = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            for ((user, raw) in all) {
                val o = runCatching { JSONObject(raw as String) }.getOrNull() ?: continue
                val keys = o.optJSONArray("k")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
                cache[user] = Entry(keys, o.optLong("t"))
            }
            loaded = true
        }
    }

    /** Every key known for [userId]: from its id, and from what the bridge said. */
    fun keysFor(ctx: Context, userId: String): Set<String> {
        load(ctx)
        return fromUserId(userId) + cache[userId]?.keys.orEmpty()
    }

    /** Whether the bridge should be asked about [userId]: never asked, or asked long ago and empty. */
    fun needsLookup(ctx: Context, userId: String, now: Long = System.currentTimeMillis()): Boolean {
        load(ctx)
        val e = cache[userId] ?: return true
        return e.keys.isEmpty() && now - e.checkedAt > RETRY_EMPTY_MS
    }

    fun remember(ctx: Context, userId: String, keys: Set<String>, now: Long = System.currentTimeMillis()) {
        load(ctx)
        cache[userId] = Entry(keys, now)
        val o = JSONObject().put("k", JSONArray(keys.toList())).put("t", now)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(userId, o.toString()).apply()
    }

    fun forget(ctx: Context) {
        cache.clear()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The number in a WhatsApp ghost's id, as a key. Empty for everyone else. */
    fun fromUserId(userId: String): Set<String> =
        WHATSAPP_NUMBER.find(userId)?.groupValues?.get(1)?.let { setOf(Contacts.key(it)) }.orEmpty()

    /**
     * The keys in a raw `m.room.member` content. `tel:` and `mailto:` only; anything else a bridge
     * lists (a username, an internal id) says nothing an address book would.
     */
    fun fromMemberContent(content: JSONObject): Set<String> {
        val ids = content.optJSONArray("com.beeper.bridge.identifiers") ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until ids.length()) {
            val id = ids.optString(i).trim()
            when {
                id.startsWith("tel:", ignoreCase = true) ->
                    Contacts.key(id.substring(4)).takeIf { it.length >= 7 }?.let(out::add)
                id.startsWith("mailto:", ignoreCase = true) ->
                    id.substring(7).lowercase().takeIf { it.contains("@") }?.let(out::add)
            }
        }
        return out
    }
}
