package com.gios.lightchat.beeper

import android.content.Context
import com.gios.lightchat.AlertText
import com.gios.lightchat.ChatMessage
import com.gios.lightchat.HeadsUp
import com.gios.lightchat.LoginCodes
import com.gios.lightchat.Notifications
import com.gios.lightchat.PendingAlerts
import com.gios.lightchat.api.Store
import com.gios.lightchat.db.MessageStore
import com.gios.lightchat.socket.AppForeground
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.notification
import de.connect2x.trixnity.client.notification.NotificationUpdate
import de.connect2x.trixnity.client.store.originTimestamp
import de.connect2x.trixnity.core.model.push.PushAction
import java.util.concurrent.ConcurrentHashMap

/**
 * Alerts for Beeper chats, through the same notification, buzz and box as iMessage.
 *
 * ### Who decides what alerts
 *
 * The account's own push rules, evaluated on the phone by Trixnity. A chat muted in Beeper on
 * any device stays quiet here, a group set to mentions only alerts on a mention, and so on,
 * without BrightChat keeping a second copy of those settings. Trixnity hands over a stream of
 * [NotificationUpdate]s (`enableExternalNotifications`): `New` for an alert to raise, `Update`
 * when its event changed, and `Remove` when it was read, on this phone or anywhere else.
 *
 * ### What this adds on top
 *
 * The rules the iMessage path already follows, so the two read the same:
 *
 * - nothing for my own messages, a removed reaction, or a message older than [since];
 * - the thread on screen gets no alert, and the app being open holds the alert for the screen
 *   going off ([PendingAlerts]);
 * - a message that is more than [QUIET_AFTER_MS] old when it gets here (the phone slept through
 *   it) is posted without the buzz and the box, like the iMessage catch-up does;
 * - a one-time code is kept for the keyboard.
 *
 * Senders are not filtered as strangers: a Beeper chat is one you are in, and message requests
 * reach the account as invites, which do not alert.
 */
object BeeperAlerts {

    private const val PREFS = "beeper_alerts"
    private const val KEY_SINCE = "since"

    /** Past this age an alert is news, not an interruption. */
    private const val QUIET_AFTER_MS = 10 * 60_000L

    /** Trixnity's notification id → the chat it was posted for, so `Remove` knows what to clear. */
    private val posted = ConcurrentHashMap<String, String>()

    /**
     * The first moment this build could alert. Set once, the first time it runs, so turning alerts
     * on does not post for every unread chat on the account.
     */
    fun since(ctx: Context): Long {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_SINCE, 0L)
        if (at != 0L) return at
        val now = System.currentTimeMillis()
        p.edit().putLong(KEY_SINCE, now).apply()
        return now
    }

    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        posted.clear()
    }

    /** Runs for the life of the client. Collected unbuffered, as Trixnity asks. */
    suspend fun watch(ctx: Context, c: MatrixClient) {
        val app = ctx.applicationContext
        val floor = since(app)
        c.notification.getAllUpdates().collect { update ->
            runCatching { handle(app, c, update, floor) }
                .onFailure { BeeperEngine.note("alert failed: ${it.message}") }
        }
    }

    private suspend fun handle(ctx: Context, c: MatrixClient, update: NotificationUpdate, floor: Long) {
        when (update) {
            is NotificationUpdate.Remove -> {
                val guid = posted.remove(update.id) ?: return
                // One notification per chat: clear it only when nothing else is still open for it.
                if (posted.values.none { it == guid }) {
                    Notifications.clearChat(ctx, listOf(guid))
                    HeadsUp.cancel(guid)
                }
            }
            is NotificationUpdate.New -> post(ctx, c, update.id, update.actions, update.content, floor, fresh = true)
            is NotificationUpdate.Update -> post(ctx, c, update.id, update.actions, update.content, floor, fresh = false)
        }
    }

    private suspend fun post(
        ctx: Context,
        c: MatrixClient,
        id: String,
        actions: Set<PushAction>,
        content: NotificationUpdate.Content,
        floor: Long,
        fresh: Boolean,
    ) {
        if (PushAction.Notify !in actions) return
        // Invites and other state changes: the list shows them, nothing to say out loud.
        val te = (content as? NotificationUpdate.Content.Message)?.timelineEvent ?: return
        if (te.event.sender == c.userId) return
        val date = te.originTimestamp
        if (date < floor) return
        val row = BeeperEngine.alertRow(c, te) ?: return
        val message = row.message
        if (!shouldAlert(message)) return
        val guid = row.chatGuid
        val convo = BeeperEngine.conversationOf(c, te.event.roomId)

        val code = if (fresh) LoginCodes.find(message.text) else null
        if (code != null) Store.setLoginCode(ctx, code, date)

        val alert = AlertText.forMessage(
            message = message,
            isGroup = convo?.isGroup == true,
            chatDisplayName = convo?.let { Store.nickname(ctx, it.guid) ?: it.displayName }.orEmpty(),
            participants = convo?.participants.orEmpty(),
            contacts = Store.contacts(ctx),
            findTarget = { target -> runCatching { MessageStore.get(ctx).messageByGuid(target) }.getOrNull() },
        )
        posted[id] = guid

        if (AppForeground.active) {
            PendingAlerts.add(guid, alert.title, alert.body, date, seen = guid in AppForeground.visibleChatGuids)
            return
        }
        Notifications.post(ctx, alert.title, alert.body, guid)
        val quiet = !fresh || System.currentTimeMillis() - date > QUIET_AFTER_MS
        if (!quiet) HeadsUp.show(ctx, alert.title, alert.body, guid, alreadyRead = false)
    }

    /** The iMessage path's rules for what is worth an alert at all. */
    fun shouldAlert(m: ChatMessage): Boolean =
        !m.fromMe && !m.isGroupEvent && !m.isReactionRemoval
}
