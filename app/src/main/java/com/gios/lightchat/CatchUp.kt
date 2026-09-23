package com.gios.lightchat

import android.content.Context
import android.util.Log
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.api.Store
import com.gios.lightchat.db.MessageStore
import com.gios.lightchat.socket.AppForeground
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pulls anything unread that arrived since the last time we alerted, and notifies for it.
 * The backstop under the live socket, and the only thing that runs while the phone is
 * asleep — see [PollAlarm] for why a timer inside the service isn't enough.
 *
 * Notification only: no box, no buzz per message, no screen wake. By the time this finds
 * something it is minutes old, and lighting the panel for old news is the behaviour we
 * spent a while removing. One buzz if it found anything at all.
 *
 * Shared by the service's watchdog, the alarm and the backup worker, so all three can only
 * ever produce one alert per message: the watermark in [Store.lastAlertedAt] is the single
 * arbiter, and it's persisted precisely because the process dying is the case this exists
 * for.
 *
 * **The watermark is the dangerous part.** Advancing it is how a message stops being
 * "missed", so advancing it past something never examined loses that message permanently —
 * no later poll will look before the line again. Two ways that used to happen, both fixed
 * here and both worst exactly when the phone has been off a while:
 *
 * 1. A page of the sweep is a fixed number of *messages*, not chats. Come back to a
 *    backlog and one busy group fills the page on its own, hiding every other chat behind
 *    it — and the watermark then jumped to the newest date in the page, over all of them.
 *    Now the sweep pages back until it actually reaches the watermark ([PAGES] deep).
 * 2. When the app was foregrounded the alert was skipped *and* the watermark advanced, so
 *    a message that arrived while you were in the app and never read was gone. Now the
 *    watermark is held below it and it's queued in [PendingAlerts] for the screen going off.
 */
object CatchUp {
    private const val TAG = "CatchUp"

    /** Messages per page of the sweep. */
    private const val PAGE = 50

    /** How deep to page when there's a backlog. Past that we give up and accept the
     *  watermark jump — a message this far behind isn't a missed notification, it's
     *  history, and paging forever would be its own bug. */
    private const val PAGES = 4

    /** Wall clock the sweep may spend paging. A Doze alarm's network window is about ten
     *  seconds and the broadcast's own budget is the same order, so the sweep has to stop
     *  itself rather than be cut off mid-response — a cut connection is recorded as a
     *  failure and retried identically, which on a slow link never converges. */
    private const val SWEEP_BUDGET_MS = 6_000L

    /** Per-page timeout, well inside [SWEEP_BUDGET_MS] so a stalled page fails rather
     *  than eating the whole budget. */
    private const val SWEEP_PAGE_TIMEOUT_MS = 6_000

    /** Only one catch-up at a time. Six things can trigger one — the alarm, the service
     *  watchdog, a socket reconnect, screen-on, the network coming back, and the worker —
     *  and they overlap in exactly the situations that produce several at once (a phone
     *  waking up with the tunnel reconnecting). Two concurrent runs read the same
     *  watermark and both write, so the later write can move it *backwards* and re-alert a
     *  message that was already delivered. The loser reports success: something is checking
     *  right now, which is all the caller wanted to know. */
    private val running = AtomicBoolean(false)

    /**
     * Runs one catch-up.
     *
     * @return whether we reached the server. Not whether anything was found — the caller
     *   uses it to decide how soon to try again, and "nothing new" is a success.
     */
    fun run(context: Context): Boolean {
        // Beeper in parallel: its sync runs on its own scope while the sweep below does its REST
        // calls, and both have to fit the same Doze network window. Waited on at the end, within
        // a budget of its own. See BeeperEngine.catchUpAsync.
        val beeper = runCatching { com.gios.lightchat.beeper.BeeperEngine.catchUpAsync(context) }.getOrNull()
        try {
            if (!running.compareAndSet(false, true)) return true
            return try {
                runLocked(context)
            } finally {
                running.set(false)
            }
        } finally {
            if (beeper != null) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(BEEPER_WAIT_MS) { beeper.join() }
                    }
                }
            }
        }
    }

    /** How long the poll waits for Beeper's sync before letting the broadcast go. */
    private const val BEEPER_WAIT_MS = 8_000L

    private fun runLocked(context: Context): Boolean {
        val app = context.applicationContext
        // Not set up yet: nothing to check, and recording a failure would back the poll off
        // for a reason that has nothing to do with delivery.
        val client = client(app) ?: return true

        // Phase 1: the cheap question. One small response that fits inside the network
        // window a Doze alarm grants; the expensive sweep is only paid for when this says
        // something arrived, by which point the radio is warm. See
        // BlueBubblesApi.newestMessageDate.
        val newest = runCatching { client.newestMessageDate() }
            .onFailure { Log.d(TAG, "probe failed: $it") }
            .getOrNull() ?: run { Store.recordPoll(app, ok = false); return false }

        val watermark = Store.lastAlertedAt(app)

        // First run seeds the watermark without alerting; otherwise every unread chat on
        // the account would arrive at once the first time a build with this in it starts.
        if (watermark == 0L) {
            Store.setLastAlertedAt(app, newest)
            Store.recordPoll(app, ok = true)
            return true
        }
        if (newest <= watermark) {
            Store.recordPoll(app, ok = true)
            return true
        }

        // Phase 2: something is new, so find out what. Pages back until the sweep covers
        // the watermark, so the list below can't be a window onto only part of the backlog.
        val sweep = runCatching {
            client.sweep(
                pageSize = PAGE,
                coverBackTo = watermark,
                maxPages = PAGES,
                budgetMs = SWEEP_BUDGET_MS,
                pageTimeoutMs = SWEEP_PAGE_TIMEOUT_MS,
            )
        }
            .onFailure { Log.d(TAG, "sweep failed: $it") }
            .getOrNull() ?: run { Store.recordPoll(app, ok = false); return false }
        Store.recordPoll(app, ok = true)

        if (!sweep.complete) {
            // Either a backlog deeper than PAGE * PAGES, or paging ran out of time. Either
            // way everything the sweep did reach still gets alerted, and what's older is
            // written off — on purpose, because the alternative is a poll that can never
            // finish and so never advances at all.
            Log.w(TAG, "sweep incomplete; nothing older than ${sweep.oldestDate} was examined")
        }

        val convos = sweep.conversations
        // **The background poll keeps the on-disk list fresh too.** It has just paid for
        // this data; letting it evaporate would mean the next launch showed a stale list and
        // then re-fetched the same thing. Chats only, deliberately — `syncedAt` is left
        // alone, so the app's own delta still re-reads these messages and stores the bodies
        // for whichever threads are actually open. Advancing it here would skip them.
        runCatching { MessageStore.get(app).putChats(convos) }
            .onFailure { Log.d(TAG, "couldn't cache the list: $it") }
        // `newest` as the floor as well as `seen`: a row the collapse skipped (no embedded
        // chat) would otherwise leave `seen` permanently below the probe's answer, so the
        // cheap short-circuit above could never fire again and every poll would pay for a
        // full sweep. Safe — the sweep was issued after the probe, so it covers it.
        val seen = maxOf(convos.maxOfOrNull { it.lastDate } ?: 0L, newest)
        val contacts = Store.contacts(app)
        // Incoming, unread account-wide (a dateRead stamp means it was read on some device),
        // newer than the line. Anything newer that fails this is either our own message or
        // one already read, and the watermark may safely pass it.
        val fresh = convos.filter { convo ->
            if (convo.lastDate <= watermark) return@filter false
            val reaction = convo.lastReaction
            // A tapback is judged on its own terms. `lastFromMe` describes the newest real
            // *speech*, so a reaction to something I said fails it — and that is precisely
            // the case worth being told about. (The socket sees these live; this is the path
            // that runs when it was asleep or wedged.)
            if (reaction != null) return@filter !reaction.fromMe
            convo.unread && !convo.lastFromMe
        }
        // A one-time code found here is held for the keyboard exactly as the socket's path does,
        // and for the same reason: this is the path that runs while the phone is asleep, so on a
        // phone that dozed between the code arriving and being wanted, this is the *only* thing
        // that saw it. [Store.setLoginCode] stamps it with the message's own time, so one that
        // is already older than the window expires immediately rather than arriving fresh.
        //
        // The preview text is what there is — the poll works from the conversation list, which
        // carries the last message's body and not the message. That is enough: a code is short
        // and services put it in the first line.
        fresh.forEach { convo ->
            LoginCodes.find(convo.lastText)?.let { Store.setLoginCode(app, it, convo.lastDate) }
        }
        val missed = fresh
            // Strangers, unless asked for — or unless it's the code you're waiting on, which is
            // an interruption you caused. The watermark still passes a filtered message —
            // suppressing it is a decision, not a deferral, so it must not be re-examined every
            // poll for the rest of time. See SenderFilter.
            .filter {
                SenderFilter.mayAlert(
                    app,
                    contacts.knows(it),
                    carriesCode = LoginCodes.looksLikeLogin(it.lastText),
                )
            }
        if (missed.isEmpty()) {
            Store.setLastAlertedAt(app, maxOf(watermark, seen))
            return true
        }

        // Title and body together, from the row: a group says who spoke, and a row whose
        // newest activity is a tapback says who reacted and to what. See AlertText — the
        // socket path phrases the same message the same way.
        val alerts = missed.map { convo -> convo to AlertText.forConversation(convo, contacts) }

        if (AppForeground.active) {
            // The user is in the app; the list on screen is being refreshed anyway and an
            // alert for something they're looking at is noise. But it isn't dropped:
            // queued for the screen going off, and — because the process can die before
            // that — the watermark is held just below the oldest of them, so the next poll
            // finds them again. Once read, they stop matching `unread` and the watermark
            // moves on by itself.
            alerts.forEach { (convo, alert) ->
                // Same gate as the socket path: the row on screen right now has been seen.
                PendingAlerts.add(
                    convo.guid, alert.title, alert.body, convo.lastDate,
                    seen = convo.guid in AppForeground.visibleChatGuids,
                )
            }
            val hold = missed.minOf { it.lastDate } - 1
            Store.setLastAlertedAt(app, maxOf(watermark, minOf(seen, hold)))
            Log.d(TAG, "found ${missed.size} missed while foreground; deferred")
            return true
        }

        Log.d(TAG, "found ${missed.size} missed")
        alerts.forEach { (convo, alert) -> Notifications.post(app, alert.title, alert.body, convo.guid) }
        // Advanced *after* posting, not before: if this dies partway the next run retries,
        // and a retry is harmless — notification ids are per chat, so a repost replaces the
        // same row. Losing an alert is the failure that matters.
        Store.setLastAlertedAt(app, maxOf(watermark, seen))
        HeadsUp.buzz(app)
        return true
    }

    private fun client(context: Context): BlueBubblesApi? {
        val url = Store.baseUrl(context) ?: return null
        val password = Store.password(context)?.takeIf { it.isNotBlank() } ?: return null
        return BlueBubblesApi(url, password)
    }
}
