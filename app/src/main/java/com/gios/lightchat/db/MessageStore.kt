package com.gios.lightchat.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gios.lightchat.ChatMessage
import com.gios.lightchat.Conversation
import com.gios.lightchat.ReactionPreview
import com.gios.lightchat.ReactionType
import com.gios.lightchat.api.BlueBubblesApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's own copy of the conversation list and of whatever thread history has
 * actually been looked at.
 *
 * **Why this exists.** Every launch used to re-download the newest 1000 messages on the
 * account — with each one's chat, that chat's participants and its attachment metadata —
 * purely to work out what the conversation list should say. Opening a thread then fetched
 * its last 100 messages again, every time, and the cache holding them was a `HashMap` in
 * the ViewModel that died with the process. Nothing the app knew survived being closed, so
 * it re-learned all of it, over a Tailscale tunnel, on a phone. Here the list is on disk:
 * it renders instantly, it renders with no network at all, and a sync fetches only what
 * changed.
 *
 * **Raw SQLite, no Room.** Same reasoning as the rest of this app being
 * `HttpURLConnection` and `org.json` with no networking or image library: two tables and
 * six queries do not justify an annotation processor.
 *
 * **Messages are stored as their original JSON**, with only the columns needed to find
 * and order them pulled out. Re-parsing with [BlueBubblesApi.parseMessage] on the way out
 * means there is exactly one message parser in the app rather than a second, subtly
 * different one written against a column layout — and a field added to [ChatMessage]
 * later is picked up by rows already on disk instead of needing a migration.
 */
class MessageStore private constructor(context: Context) {

    private val helper = Helper(context.applicationContext)

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE messages (
                    chat_guid TEXT NOT NULL,
                    guid TEXT NOT NULL,
                    date INTEGER NOT NULL,
                    json TEXT NOT NULL,
                    PRIMARY KEY (chat_guid, guid)
                )
                """.trimIndent(),
            )
            // The only access pattern there is: one chat's messages, newest first. Without
            // it every thread open is a full table scan, which on a phone that has been
            // running for months is the difference between instant and visibly not.
            db.execSQL("CREATE INDEX idx_messages_chat_date ON messages (chat_guid, date DESC)")
            db.execSQL(
                """
                CREATE TABLE chats (
                    guid TEXT PRIMARY KEY,
                    last_date INTEGER NOT NULL,
                    json TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_chats_last_date ON chats (last_date DESC)")
            // Room guid -> the conversation that owns it.
            //
            // A group iMessage has forked spans several rooms but is *one* row in the list,
            // stored under the live room's guid. Without this mapping the delta, which sees
            // messages tagged with whichever room they arrived in, looks up a sibling guid,
            // finds nothing, and inserts the group a second time — so a forked group
            // silently doubles in the list and never recovers, because only the first-run
            // seed re-runs the fork collapse.
            db.execSQL(
                """
                CREATE TABLE rooms (
                    room_guid TEXT PRIMARY KEY,
                    primary_guid TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        /**
         * A rollback to an older APK — routine when installs come from Obtainium — otherwise
         * throws `SQLiteDowngradeFailedException` on the first database access, and that
         * access is in the ViewModel's constructor, so the app cannot start at all. Same
         * treatment as an upgrade: it's a cache, so throw it away and re-sync.
         */
        override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) = onUpgrade(db, old, new)

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // The store is a cache of the Mac's chat.db, never the only copy of anything,
            // so a schema change throws it away and re-syncs rather than migrating. The
            // cost is one seed; the alternative is migration code for data we can refetch.
            db.execSQL("DROP TABLE IF EXISTS messages")
            db.execSQL("DROP TABLE IF EXISTS chats")
            db.execSQL("DROP TABLE IF EXISTS rooms")
            db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }
    }

    /* ---------------- messages ---------------- */

    /**
     * Writes messages, replacing any already held under the same (chat, guid).
     *
     * Replace rather than ignore because a message's row changes after it arrives — a
     * `dateRead` stamp, a delivery receipt, an edit — and the newest copy is the true one.
     *
     * One transaction for the batch: a sync page is up to a thousand rows, and SQLite
     * commits each statement separately otherwise, which is a disk sync per message.
     */
    fun putMessages(rows: List<Row>) {
        if (rows.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val values = ContentValues(4).apply {
                    put("chat_guid", row.chatGuid)
                    put("guid", row.message.guid)
                    put("date", row.message.date)
                    put("json", row.json)
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * A chat's newest [limit] messages, oldest-first (the order the thread wants).
     *
     * [before] pages backwards: pass the oldest date currently held to get the page under
     * it. Exclusive, unlike the server's `before`, because here we know exactly what we
     * already have.
     */
    fun messages(chatGuid: String, limit: Int = PAGE, before: Long? = null): List<ChatMessage> {
        val db = helper.readableDatabase
        val where = if (before == null) "chat_guid = ?" else "chat_guid = ? AND date < ?"
        val args = if (before == null) arrayOf(chatGuid) else arrayOf(chatGuid, before.toString())
        val out = ArrayList<ChatMessage>(limit)
        db.query("messages", arrayOf("json"), where, args, null, null, "date DESC", limit.toString())
            .use { c ->
                while (c.moveToNext()) {
                    val parsed = runCatching { BlueBubblesApi.parseMessage(JSONObject(c.getString(0))) }
                    parsed.getOrNull()?.let { out.add(it.copy(room = chatGuid)) }
                }
            }
        return out.asReversed()
    }

    /** Messages across several rooms of a forked group, de-duplicated, oldest first. */
    fun messages(chatGuids: List<String>, limit: Int = PAGE, before: Long? = null): List<ChatMessage> =
        chatGuids.flatMap { messages(it, limit, before) }
            .distinctBy { it.guid }
            .sortedBy { it.date }
            // `limit` is per room above, so a two-room group would otherwise hand back twice
            // the window, and not the newest of it either — each room's own newest `limit`,
            // which for a dormant sibling reaches years back.
            .takeLast(limit)

    /**
     * One message by guid, wherever it lives, or null.
     *
     * For naming what a tapback points at. Not scoped to a chat on purpose: a group
     * iMessage has forked spans sibling rooms and a reaction can land in a different one
     * than its target. `guid` is the back half of the primary key, so this is a scan —
     * fine at one row per incoming reaction, and the table is trimmed per chat anyway.
     */
    fun messageByGuid(guid: String): ChatMessage? {
        val db = helper.readableDatabase
        db.query("messages", arrayOf("json"), "guid = ?", arrayOf(guid), null, null, null, "1")
            .use { c ->
                if (!c.moveToNext()) return null
                return runCatching { BlueBubblesApi.parseMessage(JSONObject(c.getString(0))) }.getOrNull()
            }
    }

    /** The newest message date held for a chat, or 0. The per-thread sync's `after`. */
    fun newestDate(chatGuid: String): Long = dateEdge(chatGuid, newest = true)

    /** The oldest message date held for a chat, or 0. The backfill's `before`. */
    fun oldestDate(chatGuid: String): Long = dateEdge(chatGuid, newest = false)

    private fun dateEdge(chatGuid: String, newest: Boolean): Long {
        val fn = if (newest) "MAX(date)" else "MIN(date)"
        helper.readableDatabase
            .rawQuery("SELECT $fn FROM messages WHERE chat_guid = ?", arrayOf(chatGuid))
            .use { c -> return if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    fun countFor(chatGuid: String): Int {
        helper.readableDatabase
            .rawQuery("SELECT COUNT(*) FROM messages WHERE chat_guid = ?", arrayOf(chatGuid))
            .use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * Drops all but the newest [keep] messages in a chat.
     *
     * Run after a sync touches a chat, not on a timer: the only way the table grows is a
     * write, so that is the only moment it can need trimming. Older history is not lost in
     * any meaningful sense — scrolling back re-fetches it from the Mac, which is the copy
     * that matters.
     */
    fun trim(chatGuid: String, keep: Int = KEEP_PER_CHAT) {
        helper.writableDatabase.execSQL(
            """
            DELETE FROM messages
            WHERE chat_guid = ?
              AND date < (
                SELECT MIN(date) FROM (
                    SELECT date FROM messages WHERE chat_guid = ? ORDER BY date DESC LIMIT ?
                )
              )
            """.trimIndent(),
            arrayOf(chatGuid, chatGuid, keep.toString()),
        )
    }

    /* ---------------- chats ---------------- */

    fun putChats(conversations: List<Conversation>) {
        if (conversations.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (c in conversations) {
                val values = ContentValues(3).apply {
                    put("guid", c.guid)
                    put("last_date", c.lastDate)
                    put("json", encode(c).toString())
                }
                db.insertWithOnConflict("chats", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                for (room in c.guids) {
                    db.insertWithOnConflict(
                        "rooms",
                        null,
                        ContentValues(2).apply { put("room_guid", room); put("primary_guid", c.guid) },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** One conversation you exchanged messages in, within a window. */
    data class Talked(
        val chatGuid: String,
        val firstMs: Long,
        val lastMs: Long,
        val messages: Int,
        val fromThem: Int,
    ) {
        /** Whether they said anything, as opposed to you having talked at them. */
        val theyReplied: Boolean get() = fromThem > 0
    }

    /**
     * Who you exchanged messages with in a window, and when.
     *
     * **A query, not a log.** Every message this phone has ever synced is already in this table
     * with its date, so who you talked to last Tuesday is a `GROUP BY` — there is nothing to record
     * as it happens and nothing to miss if the app was closed. That makes it retroactive over the
     * whole synced history, which a recorder could never be.
     *
     * The rooms join is load-bearing. A group iMessage that has forked spans several room guids and
     * is one conversation; messages are tagged with whichever room they arrived in. Without
     * collapsing them a forked group comes back as two or three separate people you talked to.
     *
     * Counting `from_me` from the JSON rather than a column because there isn't one — a `LIKE` is
     * crude but the alternative is decoding a thousand rows to answer "did they reply". The
     * canonical form is `"fromMe":true`, which is what the encoder writes.
     */
    fun talkedTo(fromMs: Long, toMs: Long): List<Talked> {
        val out = ArrayList<Talked>()
        val sql = """
            SELECT COALESCE(r.primary_guid, m.chat_guid) AS conv,
                   MIN(m.date), MAX(m.date), COUNT(*),
                   SUM(CASE WHEN m.json LIKE '%"fromMe":true%' THEN 0 ELSE 1 END)
            FROM messages m
            LEFT JOIN rooms r ON r.room_guid = m.chat_guid
            WHERE m.date >= ? AND m.date < ?
            GROUP BY conv
            ORDER BY MIN(m.date) ASC
        """.trimIndent()
        runCatching {
            helper.readableDatabase.rawQuery(sql, arrayOf(fromMs.toString(), toMs.toString()))
                .use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            Talked(
                                chatGuid = c.getString(0),
                                firstMs = c.getLong(1),
                                lastMs = c.getLong(2),
                                messages = c.getInt(3),
                                fromThem = c.getInt(4),
                            ),
                        )
                    }
                }
        }
        return out
    }

    /** The conversation list, newest activity first — what the list screen renders. */
    fun chats(): List<Conversation> {
        val out = ArrayList<Conversation>()
        helper.readableDatabase
            .query("chats", arrayOf("json"), null, null, null, null, "last_date DESC")
            .use { c ->
                while (c.moveToNext()) {
                    runCatching { decode(JSONObject(c.getString(0))) }.getOrNull()?.let(out::add)
                }
            }
        return out
    }

    fun chat(guid: String): Conversation? {
        helper.readableDatabase
            .query("chats", arrayOf("json"), "guid = ?", arrayOf(guid), null, null, null, "1")
            .use { c ->
                if (!c.moveToFirst()) return null
                return runCatching { decode(JSONObject(c.getString(0))) }.getOrNull()
            }
    }

    /**
     * The conversation that owns [roomGuid] — itself for an ordinary chat, the collapsed
     * parent for one room of a forked group. The delta's only correct way in, since the
     * messages it receives are tagged by room.
     */
    fun conversationForRoom(roomGuid: String): Conversation? {
        val primary = helper.readableDatabase
            .query("rooms", arrayOf("primary_guid"), "room_guid = ?", arrayOf(roomGuid), null, null, null, "1")
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: roomGuid
        return chat(primary)
    }

    fun deleteChat(guids: List<String>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (g in guids) {
                db.delete("chats", "guid = ?", arrayOf(g))
                db.delete("messages", "chat_guid = ?", arrayOf(g))
                db.delete("rooms", "room_guid = ?", arrayOf(g))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* ---------------- sync bookkeeping ---------------- */

    /**
     * The newest message date any sync has seen, and the `after` the next one asks for.
     *
     * A message date, deliberately, not a local clock reading: the filter runs against the
     * Mac's `message.date`, and the two clocks are not the same clock. Taking `now` here
     * would skip anything created in the drift between them.
     */
    fun syncedAt(): Long = meta(KEY_SYNCED_AT)?.toLongOrNull() ?: 0L

    fun setSyncedAt(value: Long) {
        if (value <= 0L) return
        putMeta(KEY_SYNCED_AT, value.toString())
    }

    /** Whether a full thread fetch has ever run for this chat. Distinguishes "no messages
     *  held because none have been fetched" from "held, and the chat really is empty". */
    fun isThreadLoaded(chatGuid: String): Boolean = meta(KEY_THREAD_PREFIX + chatGuid) == "1"

    fun setThreadLoaded(chatGuid: String) = putMeta(KEY_THREAD_PREFIX + chatGuid, "1")

    private fun meta(key: String): String? {
        helper.readableDatabase
            .query("meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null, "1")
            .use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun putMeta(key: String, value: String) {
        helper.writableDatabase.insertWithOnConflict(
            "meta",
            null,
            ContentValues(2).apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Sign-out: the store holds the account's messages, so it goes with the password. */
    fun clear() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("chats", null, null)
            db.delete("rooms", null, null)
            db.delete("meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A message and the room it belongs to, with the JSON it was parsed from. */
    data class Row(val chatGuid: String, val message: ChatMessage, val json: String)

    companion object {
        @Volatile
        private var instance: MessageStore? = null

        /**
         * One store per process.
         *
         * Two `SQLiteOpenHelper`s over the same file in one process are two independent
         * connection pools that do not share the Java-level lock, so concurrent writes
         * surface as `SQLiteDatabaseLockedException` instead of being serialised — and the
         * background catch-up writes at the same time as the app does. Constructing one per
         * poll also leaked a pool per run.
         */
        fun get(context: Context): MessageStore =
            instance ?: synchronized(this) {
                instance ?: MessageStore(context.applicationContext).also { instance = it }
            }

        private const val NAME = "lightchat.db"
        private const val VERSION = 1

        /** Messages per thread page, on disk and over the wire. */
        const val PAGE = 50

        /**
         * Messages kept per chat.
         *
         * Enough that scrolling back through a normal conversation never touches the
         * network, small enough that a few thousand chats stay a database you would not
         * notice. What falls off is still on the Mac, one page request away.
         */
        const val KEEP_PER_CHAT = 500

        private const val KEY_SYNCED_AT = "synced_at"
        private const val KEY_THREAD_PREFIX = "thread:"

        /**
         * [Conversation] to JSON and back.
         *
         * Written by hand and kept here rather than on the model: persistence is this
         * file's business, and a `Conversation` is a derived thing (a forked group's rooms
         * are already collapsed into one by the time it exists) rather than a wire type.
         */
        fun encode(c: Conversation): JSONObject = JSONObject().apply {
            put("guid", c.guid)
            put("displayName", c.displayName)
            put("participants", JSONArray(c.participants))
            put("isGroup", c.isGroup)
            put("lastText", c.lastText)
            put("lastDate", c.lastDate)
            put("lastFromMe", c.lastFromMe)
            put("lastSender", c.lastSender ?: JSONObject.NULL)
            put("guids", JSONArray(c.guids))
            put("unread", c.unread)
            c.network?.let { put("network", it) }
            c.lastReaction?.let { r ->
                put(
                    "lastReaction",
                    JSONObject().apply {
                        put("type", r.type.name)
                        put("fromMe", r.fromMe)
                        put("reactor", r.reactor ?: JSONObject.NULL)
                        put("target", r.target)
                    },
                )
            }
        }

        fun decode(o: JSONObject): Conversation {
            val participants = o.optJSONArray("participants").toStringList()
            val guids = o.optJSONArray("guids").toStringList()
            val guid = o.optString("guid")
            return Conversation(
                guid = guid,
                displayName = o.optString("displayName"),
                participants = participants,
                isGroup = o.optBoolean("isGroup"),
                lastText = o.optString("lastText"),
                lastDate = o.optLong("lastDate"),
                lastFromMe = o.optBoolean("lastFromMe"),
                // Absent on a row written before this field existed; null then, which
                // costs a group notification its sender name until the next sweep
                // rewrites the row. Not worth a schema bump (which drops the cache).
                lastSender = if (o.isNull("lastSender")) null else o.optString("lastSender").ifBlank { null },
                lastReaction = o.optJSONObject("lastReaction")?.let { r ->
                    val type = ReactionType.entries.firstOrNull { it.name == r.optString("type") }
                    if (type == null) {
                        null
                    } else {
                        ReactionPreview(
                            type = type,
                            fromMe = r.optBoolean("fromMe"),
                            reactor = if (r.isNull("reactor")) null else r.optString("reactor"),
                            target = r.optString("target"),
                        )
                    }
                },
                guids = guids.ifEmpty { listOf(guid) },
                unread = o.optBoolean("unread"),
                network = o.optString("network").takeIf { it.isNotBlank() && it != "null" },
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
        }
    }
}
