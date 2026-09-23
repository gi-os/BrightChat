package com.gios.lightchat.people

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import com.gios.lightchat.ChatMessage
import com.gios.lightchat.Contacts
import org.json.JSONArray

/** Hand joins and splits, on disk. Small: a few dozen pairs at most. */
object PeopleStore {
    private const val PREFS = "people"
    private const val KEY_JOINED = "joined"
    private const val KEY_SPLIT = "split"
    private const val KEY_DEFAULT = "default_network"

    /**
     * The network you mostly use. Its chats carry no mark in the list, and a person's messages go
     * out on it when they have a chat there. iMessage until changed.
     */
    fun defaultNetwork(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEFAULT, null) ?: "iMessage"

    fun setDefaultNetwork(context: Context, network: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT, network).apply()
    }

    fun load(context: Context): PeopleLinks {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PeopleLinks(joined = pairs(p.getString(KEY_JOINED, null)), split = pairs(p.getString(KEY_SPLIT, null)))
    }

    fun save(context: Context, links: PeopleLinks) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JOINED, encode(links.joined))
            .putString(KEY_SPLIT, encode(links.split))
            .apply()
    }

    private fun encode(pairs: Set<Pair<String, String>>): String =
        JSONArray().apply { pairs.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } }.toString()

    private fun pairs(raw: String?): Set<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                val a = pair.optString(0)
                val b = pair.optString(1)
                if (a.isBlank() || b.isBlank()) null else a to b
            }.toSet()
        }.getOrDefault(emptySet())
    }
}

/** One call, from the phone's own call history or from a Beeper network. */
data class CallEntry(
    val date: Long,
    /** "Missed", "Incoming", "Outgoing", or the network's own words for a Beeper call. */
    val kind: String,
    /** "Phone", "WhatsApp", "Signal"… */
    val via: String,
    val durationSec: Long = 0,
)

/** A row of the Dial tab's recent calls: who, what happened, and how to get back to them. */
data class RecentCall(
    val name: String,
    val call: CallEntry,
    /** The number to ring back, for a phone call. */
    val number: String? = null,
    /** The chat to open, for a Beeper call. */
    val chatGuid: String? = null,
)

/**
 * A person's calls: the phone's call history for their numbers, and the call notices Beeper's
 * bridges post into their chats ("Missed voice call"). Newest first.
 */
object CallHistory {

    fun canReadPhone(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun phoneCalls(context: Context, numbers: Collection<String>, limit: Int = 400): List<CallEntry> {
        if (numbers.isEmpty() || !canReadPhone(context)) return emptyList()
        val keys = numbers.map { Contacts.key(it) }.filter { it.length >= 7 }.toSet()
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<CallEntry>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null,
                null,
                CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                var seen = 0
                while (c.moveToNext() && seen < limit) {
                    seen++
                    val number = c.getString(0) ?: continue
                    if (Contacts.key(number) !in keys) continue
                    val kind = when (c.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Declined"
                        else -> "Call"
                    }
                    out += CallEntry(date = c.getLong(2), kind = kind, via = "Phone", durationSec = c.getLong(3))
                }
            }
        }
        return out
    }

    /**
     * Every recent call, for the Dial tab: the phone's history (named from the address book) and
     * the call notices in Beeper chats (named after the chat). Newest first.
     */
    fun recent(
        context: Context,
        contacts: Contacts,
        notices: List<ChatMessage>,
        chatOf: (String) -> com.gios.lightchat.Conversation?,
        limit: Int = 60,
    ): List<RecentCall> {
        val phone = ArrayList<RecentCall>()
        if (canReadPhone(context)) {
            runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                    null,
                    null,
                    CallLog.Calls.DATE + " DESC",
                )?.use { c ->
                    while (c.moveToNext() && phone.size < limit) {
                        val number = c.getString(0).orEmpty()
                        val kind = when (c.getInt(1)) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.REJECTED_TYPE -> "Declined"
                            else -> "Call"
                        }
                        phone += RecentCall(
                            name = contacts.name(number) ?: number.ifBlank { "Unknown" },
                            call = CallEntry(c.getLong(2), kind, "Phone", c.getLong(3)),
                            number = number.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
        }
        val beeper = notices.mapNotNull { m ->
            val room = m.room ?: return@mapNotNull null
            val chat = chatOf(room)
            RecentCall(
                name = chat?.displayName?.takeIf { it.isNotBlank() } ?: "Unknown",
                call = CallEntry(m.date, m.text, chat?.network ?: "Beeper"),
                chatGuid = room,
            )
        }
        return (phone + beeper).sortedByDescending { it.call.date }.take(limit)
    }

    /** The call notices among a person's messages, labelled with each one's network. */
    fun networkCalls(messages: List<ChatMessage>, networkOf: (ChatMessage) -> String): List<CallEntry> =
        messages.filter { it.isCall }.map { m -> CallEntry(date = m.date, kind = m.text, via = networkOf(m)) }

    fun merged(phone: List<CallEntry>, network: List<CallEntry>): List<CallEntry> =
        (phone + network).sortedByDescending { it.date }
}
