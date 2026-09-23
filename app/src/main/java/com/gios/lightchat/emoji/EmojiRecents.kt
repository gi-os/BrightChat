package com.gios.lightchat.emoji

import android.content.Context
import android.graphics.Paint
import com.gios.lightchat.R

/**
 * The emoji you react with most recently, on disk, for the front of the picker.
 */
object EmojiRecents {
    private const val PREFS = "emoji"
    private const val KEY = "recent"

    fun load(context: Context): RecentEmoji =
        RecentEmoji.deserialize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun used(context: Context, glyph: String) {
        val next = load(context).used(glyph)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.serialize()).apply()
    }
}

/**
 * The emoji table, loaded once and cut down to what this phone's font can draw.
 *
 * A bundled table is what Unicode defines, not what LightOS draws: anything newer than its font
 * would show as an empty box, so each glyph is asked [Paint.hasGlyph] once, off the main thread.
 * Recomputed per process rather than stored, so a font update is picked up.
 */
object EmojiTable {
    @Volatile private var cached: Pair<Emoji, IntArray>? = null

    /** The table and the indices this phone can draw. Blocking; call off the main thread. */
    fun get(context: Context): Pair<Emoji, IntArray> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val table = runCatching {
                context.resources.openRawResource(R.raw.emoji).use { Emoji.load(it) }
            }.getOrDefault(Emoji.EMPTY)
            val paint = Paint()
            val usable = table.indices().filter { i -> runCatching { paint.hasGlyph(table.glyph(i)) }.getOrDefault(false) }
            return (table to usable.toIntArray()).also { cached = it }
        }
    }
}
