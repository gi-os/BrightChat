package com.gios.lightchat.beeper

import android.content.Context
import com.gios.light.common.report.Failure
import com.gios.light.common.report.Reports
import com.gios.light.common.report.Symptom
import java.security.MessageDigest

/**
 * Sends Beeper failures to the tracker by itself, with the connection log attached.
 *
 * ### Why sent, not offered
 *
 * The beta is debugged from the field: nobody has logcat on the Light Phone, and an offer to
 * report is dismissed by the same tap that dismisses the failure. A failure the engine detected
 * has already written its own account (what it tried, what came back), so there is nothing to
 * ask. Same call Gio made for BrightControl.
 *
 * ### The throttle rules (from BrightControl and BrightRemote)
 *
 * - **Keyed by kind, never by message.** The message names the room or the HTTP code, so keying
 *   on it makes every repeat a first offence.
 * - **Once per kind per app version, ledger on disk.** Sign-in is the flow people retry across
 *   restarts; a per-process throttle files the same report on every relaunch. A new build gets to
 *   report again, because it may have changed the answer.
 * - **One minute between any two**, whatever they say, so a new failure mode can't flood.
 * - **The plumbing does not report.** Per-room failures in a list pass are counted by the pass,
 *   which reports once.
 *
 * ### What leaves the phone
 *
 * The tracker is private, but the log is still [redact]ed: email addresses, Matrix user, room and
 * event ids and media URLs become short stable hashes. The same id hashes the same way within a
 * report, so "room 3fa1 failed twice" still reads, and nothing in it names a person.
 */
object BeeperReports {

    private const val PREFS = "beeper_reports"
    private const val KEY_SENT = "sent"
    private const val KEY_LAST = "last_at"
    private const val FLOOR_MS = 60_000L
    private const val LOG_LINES = 80

    fun shouldSend(context: Context, kind: String, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = version(context) + "|" + kind
        val sent = prefs.getStringSet(KEY_SENT, emptySet()).orEmpty()
        if (key in sent) return false
        if (now - prefs.getLong(KEY_LAST, 0L) < FLOOR_MS) return false
        prefs.edit().putStringSet(KEY_SENT, sent + key).putLong(KEY_LAST, now).apply()
        return true
    }

    /** Files one report. [manual] is the Settings button: it skips the throttle and says so. */
    suspend fun send(context: Context, kind: String, error: Throwable?, log: List<String>, manual: Boolean = false) {
        val detail = buildString {
            if (error != null) appendLine("${error::class.java.simpleName}: ${redact(error.message.orEmpty())}")
            appendLine()
            appendLine("Beeper log (last $LOG_LINES lines, redacted):")
            log.takeLast(LOG_LINES).forEach { appendLine(redact(it)) }
        }
        val report = Reports.compose(
            context = context,
            symptom = Symptom.Other,
            note = if (manual) "Beeper log, sent from Settings" else "Beeper: $kind (sent automatically)",
            screen = "beeper",
            crash = null,
            failure = Failure(what = "beeper: $kind", detail = detail.take(20_000)),
        )
        runCatching { Reports.submit(context, report) }
    }

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val MATRIX_ID = Regex("[@!$#][A-Za-z0-9._=/+\\-]+:[A-Za-z0-9.-]+(:[0-9]+)?")
    private val MXC = Regex("mxc://[A-Za-z0-9./_-]+")
    private val BARE_EVENT = Regex("\\$[A-Za-z0-9_\\-+/]{16,}")

    /**
     * Replaces anything that identifies a person or a chat with `<kind:hash>`. Pure, so the unit
     * test can pin it. Order matters: a Matrix id contains an `@` and would otherwise be eaten by
     * the email pattern with its server attached.
     */
    fun redact(line: String): String {
        var out = MXC.replace(line) { "<mxc:${hash(it.value)}>" }
        out = MATRIX_ID.replace(out) { m ->
            val kind = when (m.value.first()) {
                '@' -> "user"
                '!' -> "room"
                '$' -> "event"
                else -> "alias"
            }
            "<$kind:${hash(m.value)}>"
        }
        out = EMAIL.replace(out) { "<email:${hash(it.value)}>" }
        out = BARE_EVENT.replace(out) { "<event:${hash(it.value)}>" }
        return out
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("brightchat-beeper|" + value).toByteArray())
        return digest.take(3).joinToString("") { "%02x".format(it) }
    }

    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
