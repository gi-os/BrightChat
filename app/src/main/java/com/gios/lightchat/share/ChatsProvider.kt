package com.gios.lightchat.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gios.lightchat.api.Store
import com.gios.lightchat.db.MessageStore

/**
 * The group conversations, offered to the rest of the collection.
 *
 * **Why this has to exist at all.** Roll's send picker asks *who*, and it answers the question
 * out of the phone's address book — which is the right source for a person and has nothing at
 * all to say about a group. A group iMessage is not a contact: it is a chat room living on the
 * Mac, identified by a guid of the form `iMessage;+;chat684…`, and the only copy of that fact
 * on the phone is this app's local list. Without a bridge, "send this to the group" is
 * unreachable from any other app — the best Roll could do was hand the photograph over with no
 * recipient and let the user find the thread by hand.
 *
 * **Groups only, and nothing else.** The table behind this also holds every one-to-one thread
 * on the account, and none of it is served here. Not because a caller would misuse it, but
 * because a 1:1 chat's guid *is* its handle — Roll already has the handle from the address
 * book and can address that thread without being told anything. Serving the rest would be
 * exposing the shape of every conversation on the phone in exchange for nothing, and the whole
 * argument for having no permission on this provider (below) rests on there being nothing here
 * worth guarding.
 *
 * **No permission, same reasoning as Roll's own StarsProvider.** It reveals the names of groups
 * you are in, to an app that asks by authority name, on a phone with one user and a hand-picked
 * set of applications. The alternative is a signature check maintained against a keystore per
 * app, which is more moving parts protecting less.
 *
 * **Titles are resolved here, not by the caller.** The stored chat carries raw handles; turning
 * `+13152122695` into "Liz" needs the BlueBubbles address book, which this app persists and
 * Roll has no copy of. Resolving on this side means the picker shows the same words the
 * conversation list does, which is the point — a row that reads differently in two apps is two
 * groups as far as the user is concerned.
 */
class ChatsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = arrayOf(COLUMN_GUID, COLUMN_TITLE, COLUMN_PARTICIPANTS, COLUMN_LAST_DATE)
        val cursor = MatrixCursor(columns)
        val context = context ?: return cursor
        // A provider can be queried with no part of this app running, so everything here has to
        // come off disk on its own: the store opens its own database handle and the contact
        // index is a persisted map, not a live object owned by the ViewModel.
        runCatching {
            val contacts = Store.contacts(context)
            MessageStore.get(context).chats()
                .filter { it.isGroup }
                .forEach { chat ->
                    cursor.addRow(
                        arrayOf<Any?>(
                            chat.guid,
                            contacts.title(chat),
                            chat.participants.size,
                            chat.lastDate,
                        ),
                    )
                }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY_SUFFIX.chat"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = "com.gios.lightchat.chats"

        /**
         * The chat-room guid. **This is the whole payload** — it is what a sender puts back in
         * its intent to address this group, and it is opaque: `iMessage;+;chat684…` is the
         * server's identifier and nothing about its shape should be parsed by a caller.
         */
        const val COLUMN_GUID = "guid"

        /** What the conversation list calls this group, with handles already resolved to names. */
        const val COLUMN_TITLE = "title"

        /** How many people are in it. A picker wants to say "4 people" under the name. */
        const val COLUMN_PARTICIPANTS = "participants"

        /** Epoch millis of the last activity, so a caller can order by it without a second query. */
        const val COLUMN_LAST_DATE = "last_date"

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY_SUFFIX/chats")
    }
}
