package com.gios.lightchat.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gios.lightchat.LoginCodes
import com.gios.lightchat.api.Store

/**
 * The login code that just arrived, for as long as it is worth anything.
 *
 * **The problem this solves.** A verification code arrives in iMessage and has to be retyped
 * into a field in another app, from memory, six digits at a time, having switched away from the
 * thing that is waiting for it. iOS solves this by putting the code above the keyboard.
 * Android solves it with autofill, which needs Play Services and a real SMS app — neither of
 * which exists here, because these codes arrive over BlueBubbles and not over the carrier.
 * So the keyboard reads it from here instead. See LightKeyboard's suggestion strip.
 *
 * **A code and nothing else.** Not the message, not the sender, not the thread it came from.
 * The keyboard needs the digits; everything else would be a second copy of the conversation
 * list living in an app that has no business holding one.
 *
 * **It expires, and the expiry is enforced on read.** [Store.CODE_TTL_MS] after the message
 * landed this returns nothing at all, whatever is still on disk. That matters more than it
 * looks: a stale code pinned to the suggestion strip is a wrong answer offered in the position
 * the user has learned to accept without reading, and codes themselves expire in minutes
 * anyway. Enforcing it here rather than by clearing on a timer means a phone that was asleep,
 * or a process that died between the message and the keyboard opening, still gets the right
 * answer instead of depending on something having run.
 *
 * No permission on it, for the reasons in [ChatsProvider] — and a smaller surface than that
 * one, since a three-minute window carrying at most one number is not a history of anything.
 */
class CodeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Zero rows or one. A cursor rather than a `call()` because a cursor is what a keyboard can
     * read on the main thread through the ordinary ContentResolver path without any of it
     * knowing what this app is.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(COLUMN_CODE, COLUMN_ARRIVED))
        val context = context ?: return cursor
        runCatching {
            val held = Store.loginCode(context) ?: return@runCatching
            // Re-checked rather than trusted: the value is written by whichever of the socket or
            // the catch-up poll saw the message first, and a build of either that ever writes
            // something malformed should not be able to put it in front of the user.
            if (!LoginCodes.wellFormedCode(held.code)) return@runCatching
            cursor.addRow(arrayOf<Any?>(held.code, held.arrivedAt))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY_SUFFIX.code"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = "com.gios.lightchat.code"

        /** The code itself, exactly as it was written. */
        const val COLUMN_CODE = "code"

        /** Epoch millis the message carrying it arrived, so a reader can show its own age if it wants. */
        const val COLUMN_ARRIVED = "arrived_at"

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY_SUFFIX/code")
    }
}
