package com.gios.lightchat.api

import android.content.Context
import com.gios.lightchat.Contacts
import com.gios.lightchat.Gif
import com.gios.lightchat.GifJson
import com.gios.lightchat.NewsletterBatch
import com.gios.lightchat.NewsletterJson
import org.json.JSONObject

/**
 * On-device state for the BlueBubbles client: the server URL (entered at setup,
 * editable in Settings) and the server password, encrypted at rest by
 * [SecureStore]. The app talks to one self-hosted BlueBubbles Server — typically
 * reached over Tailscale Serve or another HTTPS reverse proxy (which provides TLS
 * + private routing; the server itself stays LAN-bound). Nothing here touches
 * Google Play Services.
 */
object Store {
    private const val PREFS = "chat"
    private const val KEY_PASSWORD = "bb_password" // encrypted
    private const val KEY_CONTACTS = "contacts"    // normalized key → name, JSON
    private const val KEY_NICKNAMES = "nicknames"  // chat guid → nickname, JSON (local override)
    private const val KEY_BASE_URL = "base_url"    // the server URL, set at setup
    private const val KEY_PRIVATE_API = "private_api" // server's Private API live?
    private const val KEY_FAVORITES = "favorites"     // starred chat guids, newline-joined
    private const val KEY_ALERTED_AT = "alerted_at"   // newest message we've alerted for
    private const val KEY_POLL_AT = "poll_at"         // last catch-up attempt, wall clock
    private const val KEY_POLL_OK_AT = "poll_ok_at"   // last catch-up that reached the server
    private const val KEY_POLL_FAILS = "poll_fails"   // consecutive failures
    private const val KEY_NOTIFY_UNKNOWN = "notify_unknown" // alert for senders not in the address book
    private const val KEY_HEADS_UP = "heads_up_box" // show the on-screen box for new messages
    private const val KEY_HAPTICS = "haptics"       // buzz on every tap
    private const val KEY_ALERTS_OWNED = "alerts_owned" // BrightControl draws the box for every app
    private const val KEY_CALL_ANNOUNCE = "call_announce" // text people the dumb-phone number when calling them
    private const val KEY_MY_NUMBER = "my_number" // manual override for the SIM's own number
    private const val KEY_NOTED = "noted_keys"        // conversations whose note has been opened
    private const val KEY_PINS = "pinned_guids"      // starred chats held at the top, newest pin first
    private const val KEY_SPEED_DIAL = "speed_dial"   // digit -> number\u0000name
    private const val KEY_CODE = "login_code"         // the newest one-time code seen
    private const val KEY_CODE_AT = "login_code_at"   // when the message carrying it arrived
    private const val KEY_BG_PREFIX = "chat_bg:"      // per-chat background filter stack, JSON
    private const val KEY_NEWSLETTERS = "newsletters" // named broadcast batches, JSON
    private const val KEY_WHISPER_URL = "whisper_url"     // transcription endpoint, or unset
    private const val KEY_WHISPER_KEY = "whisper_key"     // encrypted bearer key
    private const val KEY_WHISPER_MODEL = "whisper_model" // e.g. whisper-1, or a local model name
    private const val KEY_TRANSCRIPT_PREFIX = "transcript:" // attachment guid → words
    private const val KEY_KLIPY_KEY = "klipy_key"       // encrypted GIF-service key
    private const val KEY_GIFS_SAVED = "gifs_saved"     // saved GIFs, JSON (see GifJson)
    private const val KEY_GIFS_RECENT = "gifs_recent"   // recently sent GIFs, JSON
    private const val KEY_GIF_CUSTOMER = "gif_customer" // random per-install id the GIF API asks for

    /** The configured BlueBubbles Server URL, or null if setup hasn't run yet. */
    fun baseUrl(context: Context): String? =
        prefs(context).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }

    /** Stores the server URL, normalizing it: default to https:// if no scheme is
     *  given, and drop a trailing slash so it concatenates cleanly with API paths. */
    fun setBaseUrl(context: Context, value: String) {
        var url = value.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        prefs(context).edit().putString(KEY_BASE_URL, url).apply()
    }

    fun password(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.let { runCatching { SecureStore.decrypt(it) }.getOrNull() }

    fun setPassword(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PASSWORD, SecureStore.encrypt(value.trim())).apply()
    }

    fun hasPassword(context: Context): Boolean = !password(context).isNullOrBlank()

    // ---------------------------------------------------------------- transcription

    /**
     * Where to send a recording to have it turned into words. See [WhisperApi].
     *
     * A URL rather than a switch, because there is nothing to switch on: no model ships in this app,
     * so transcription exists exactly to the extent that you have pointed it at a server. Empty is
     * the honest off.
     */
    fun whisperUrl(context: Context): String? =
        prefs(context).getString(KEY_WHISPER_URL, null)?.takeIf { it.isNotBlank() }

    fun setWhisperUrl(context: Context, value: String) {
        var url = value.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        prefs(context).edit().putString(KEY_WHISPER_URL, url).apply()
    }

    /** Encrypted, exactly like the server password: it is a bearer key and may be a paid one. */
    fun whisperKey(context: Context): String =
        prefs(context).getString(KEY_WHISPER_KEY, null)
            ?.let { runCatching { SecureStore.decrypt(it) }.getOrNull() }
            .orEmpty()

    fun setWhisperKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_WHISPER_KEY, SecureStore.encrypt(value.trim())).apply()
    }

    fun whisperModel(context: Context): String =
        prefs(context).getString(KEY_WHISPER_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: WhisperApi.DEFAULT_MODEL

    fun setWhisperModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_WHISPER_MODEL, value.trim()).apply()
    }

    fun canTranscribe(context: Context): Boolean = !whisperUrl(context).isNullOrBlank()

    /**
     * A transcript already fetched, by attachment guid.
     *
     * Cached because transcription is slow and may be billed: opening the same voice memo twice
     * should not pay for it twice. Keyed on the guid rather than on the file, since the file is a
     * cache entry that can be evicted while the words are still worth keeping.
     */
    fun transcript(context: Context, guid: String): String? =
        prefs(context).getString(KEY_TRANSCRIPT_PREFIX + guid, null)?.takeIf { it.isNotBlank() }

    fun setTranscript(context: Context, guid: String, text: String) {
        prefs(context).edit().putString(KEY_TRANSCRIPT_PREFIX + guid, text).apply()
    }

    // ------------------------------------------------------------------------- gifs

    /**
     * The GIF service key to search with: this phone's own if one has been entered, else the one
     * the app ships with ([KlipyKey]). See [KlipyApi] for which service and why.
     *
     * The two exist for different reasons and neither replaces the other. The **built-in** key is
     * what makes the GIF button work on a fresh install with nothing to set up, and its allowance
     * is per *key* — every install draws on the same one — so a busy hour is a 429 for everybody.
     * A **personal** key is the answer to that, and takes precedence whenever it is set.
     *
     * Blank is still a working state, not a broken one: the picker offers what this phone has
     * saved and recently sent, which needs no service at all.
     */
    fun klipyKey(context: Context): String = ownKlipyKey(context).ifBlank { KlipyKey.builtIn }

    /**
     * Only this phone's own key, blank when none has been entered.
     *
     * Separate from [klipyKey] because the settings screen must show what the *user* set — a field
     * pre-filled with the app's built-in key would be handing the shipped key to anybody who opened
     * Settings, and worse, it would then be saved back as though they had chosen it.
     *
     * Encrypted at rest, like the server password and the Whisper key.
     */
    fun ownKlipyKey(context: Context): String =
        prefs(context).getString(KEY_KLIPY_KEY, null)
            ?.let { runCatching { SecureStore.decrypt(it) }.getOrNull() }
            .orEmpty()

    fun setKlipyKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_KLIPY_KEY, SecureStore.encrypt(value.trim())).apply()
    }

    /** Whether GIFs can be *searched* at all. Saved ones work regardless. */
    fun canSearchGifs(context: Context): Boolean = klipyKey(context).isNotBlank()

    /**
     * The saved GIFs, newest first, and the ones recently sent.
     *
     * Local by construction, like [favorites] and [newsletters]: BlueBubbles has no concept of a
     * GIF, and the GIF service has no concept of this person — there is no account here to sync
     * with. JSON rather than the newline-joined form the guid lists use, because a GIF carries a
     * provider-written title. See [com.gios.lightchat.Gifs], which owns the files behind these.
     */
    fun savedGifs(context: Context): List<Gif> =
        GifJson.decode(prefs(context).getString(KEY_GIFS_SAVED, null))

    fun setSavedGifs(context: Context, value: List<Gif>) {
        prefs(context).edit().putString(KEY_GIFS_SAVED, GifJson.encode(value)).apply()
    }

    fun recentGifs(context: Context): List<Gif> =
        GifJson.decode(prefs(context).getString(KEY_GIFS_RECENT, null))

    fun setRecentGifs(context: Context, value: List<Gif>) {
        prefs(context).edit().putString(KEY_GIFS_RECENT, GifJson.encode(value)).apply()
    }

    /**
     * A random id the GIF service wants on each request, made once on first use.
     *
     * A UUID and nothing else: not the install id, not a hash of the phone, not anything derived
     * from the person holding it. The service uses it to keep its own recents and to fill its ad
     * slots; this app keeps recents itself, so the value of the id to us is exactly that the API
     * stops asking. Cleared with everything else on sign out, which is the right lifetime for it.
     */
    fun gifCustomerId(context: Context): String {
        prefs(context).getString(KEY_GIF_CUSTOMER, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_GIF_CUSTOMER, id).apply()
        return id
    }

    /**
     * Persists the contact index so the [com.gios.lightchat.socket.SocketService] —
     * which can run with no activity/ViewModel alive (e.g. started at boot) — can
     * resolve sender addresses to names for notifications. Stored as the already-
     * normalized key → name map ([Contacts.asMap]), no re-normalization on read.
     */
    fun setContacts(context: Context, byKey: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in byKey) obj.put(k, v)
        prefs(context).edit().putString(KEY_CONTACTS, obj.toString()).apply()
    }

    /** The persisted contact index, or an empty one if none stored yet. */
    fun contacts(context: Context): Contacts {
        val json = prefs(context).getString(KEY_CONTACTS, null) ?: return Contacts()
        return runCatching {
            val obj = JSONObject(json)
            val map = HashMap<String, String>(obj.length())
            obj.keys().forEach { map[it] = obj.getString(it) }
            Contacts.fromMap(map)
        }.getOrDefault(Contacts())
    }

    /**
     * Local per-phone nicknames that override a conversation's display name, keyed by
     * primary chat guid. Persisted separately from the server's name (which re-syncs and
     * would clobber an override), and separately from [favorites]/[pins] because this maps
     * a guid to a string. JSON like [contacts]; a guid never needs normalizing.
     */
    fun nicknames(context: Context): Map<String, String> {
        val json = prefs(context).getString(KEY_NICKNAMES, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val map = HashMap<String, String>(obj.length())
            obj.keys().forEach { map[it] = obj.getString(it) }
            map
        }.getOrDefault(emptyMap())
    }

    /** The nickname for [guid], or null when none is set (blank counts as none). */
    fun nickname(context: Context, guid: String): String? =
        nicknames(context)[guid]?.takeIf { it.isNotBlank() }

    /** Sets the nickname for [guid]; a blank [value] clears it. */
    fun setNickname(context: Context, guid: String, value: String) {
        val name = value.trim()
        val next = nicknames(context).toMutableMap()
        if (name.isBlank()) next.remove(guid) else next[guid] = name
        val obj = JSONObject()
        for ((k, v) in next) obj.put(k, v)
        prefs(context).edit().putString(KEY_NICKNAMES, obj.toString()).apply()
    }

    /** Whether the server's Private API is live (tapbacks available). Cached from
     *  `server/info` so the UI knows on launch before the first refresh lands. */
    fun privateApi(context: Context): Boolean = prefs(context).getBoolean(KEY_PRIVATE_API, false)

    fun setPrivateApi(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRIVATE_API, value).apply()
    }

    /**
     * The starred conversations, by primary chat guid. Persisted (not derived from
     * the server) because BlueBubbles exposes no favorites concept — this is a
     * local, per-phone pin. Stored newline-joined rather than as a JSON array
     * because guids never contain a newline and a StringSet would reorder.
     */
    fun favorites(context: Context): Set<String> =
        prefs(context).getString(KEY_FAVORITES, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun setFavorites(context: Context, guids: Set<String>) {
        prefs(context).edit().putString(KEY_FAVORITES, guids.joinToString("\n")).apply()
    }

    /**
     * The per-chat background's filter stack, as JSON — see
     * [com.gios.lightchat.ChatBackground], which owns the shape (and the image
     * file itself, under filesDir). Local like favorites: BlueBubbles has no
     * concept of a chat wallpaper, and a background is a per-phone choice anyway.
     */
    fun background(context: Context, chatGuid: String): String? =
        prefs(context).getString(KEY_BG_PREFIX + chatGuid, null)

    /** Stores the stack, or clears it when [json] is null (the background was removed). */
    fun setBackground(context: Context, chatGuid: String, json: String?) {
        prefs(context).edit().apply {
            if (json == null) remove(KEY_BG_PREFIX + chatGuid) else putString(KEY_BG_PREFIX + chatGuid, json)
        }.apply()
    }

    /**
     * The date of the newest message we've raised an alert for. The catch-up poll (see
     * `SocketService`) uses it as a watermark so a missed message notifies exactly once,
     * no matter how many times the poll runs or the process restarts. Persisted rather
     * than in-memory precisely because the process restarting is the case it exists for.
     */
    fun lastAlertedAt(context: Context): Long = prefs(context).getLong(KEY_ALERTED_AT, 0L)

    fun setLastAlertedAt(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_ALERTED_AT, value).apply()
    }

    /**
     * Delivery bookkeeping, written by every catch-up attempt.
     *
     * [lastPollAt] is what makes a *broken* alarm chain detectable. Each
     * `setAndAllowWhileIdle` firing arms the next one, so the chain is a single thread
     * that a force-stop, a lost firing, or an app update cuts for good — and nothing
     * about the app's state says so. Comparing this against the expected interval does
     * (`PollAlarm.looksStalled`), which is what lets a screen-on, a network coming back,
     * or the backup worker repair it.
     *
     * [lastPollOkAt] is separate because "the alarm fired" and "we heard from the server"
     * are different failures with the same symptom. [pollFailures] backs off the interval
     * so an unreachable server doesn't poll the battery flat, and is reset by any success.
     */
    fun lastPollAt(context: Context): Long = prefs(context).getLong(KEY_POLL_AT, 0L)

    fun lastPollOkAt(context: Context): Long = prefs(context).getLong(KEY_POLL_OK_AT, 0L)

    fun pollFailures(context: Context): Int = prefs(context).getInt(KEY_POLL_FAILS, 0)

    /** Records an attempt and its outcome in one write. */
    fun recordPoll(context: Context, ok: Boolean) {
        val now = System.currentTimeMillis()
        val edit = prefs(context).edit().putLong(KEY_POLL_AT, now)
        if (ok) {
            edit.putLong(KEY_POLL_OK_AT, now).putInt(KEY_POLL_FAILS, 0)
        } else {
            edit.putInt(KEY_POLL_FAILS, pollFailures(context) + 1)
        }
        edit.apply()
    }

    /**
     * Whether a message from somebody not in the address book should raise an alert.
     *
     * **Off by default**, which is the whole point of it. An iMessage account that has existed for
     * years receives a steady trickle from short codes, delivery services, two-factor senders and
     * whoever last had your number — and on this phone every one of those buzzes, lights the panel
     * and puts a box in front of whatever you were doing. The messages still arrive and still show
     * an unread mark in the list; they just don't interrupt.
     *
     * "Known" is [Contacts.knows]: a named group, or any participant in the address book. Same
     * definition as the list's Known tab, so what the setting does is exactly "alert me about the
     * Known and Favourites tabs" — no second notion of who counts as a stranger.
     */
    fun notifyUnknown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_UNKNOWN, false)

    fun setNotifyUnknown(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_UNKNOWN, value).apply()
    }

    /**
     * Whether an incoming message puts the box up over whatever the phone is showing
     * (see `HeadsUp`). **On by default** — it's the app's signature move — but it can be
     * turned off for people who find a lit-up panel worse than a missed text. The shade
     * notification is always posted either way (it's the record, and it drives
     * LightGlance's dot), and the buzz still happens: this only controls the display.
     */
    fun headsUpBox(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEADS_UP, true)

    fun setHeadsUpBox(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEADS_UP, value).apply()
    }

    /**
     * Whether a tap buzzes the motor.
     *
     * On by default — the tick is how a screen with no button borders tells you the tap landed.
     * Off silences every tap, long press and menu pick in the app at once; see
     * [com.gios.lightchat.ui.Haptics] for where the switch is applied. The buzz a new message
     * makes is a notification, not feedback, and is not covered by this.
     */
    fun haptics(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHaptics(context: Context, value: Boolean) {
        // commit(), not apply(): a report (light-reports#476) had this switch back on after the
        // phone locked up and the process was lost. An apply() still queued when a process is
        // killed is a write that never happened, and this is one boolean written from a tap on
        // Settings — the synchronous write costs nothing anyone will feel.
        prefs(context).edit().putBoolean(KEY_HAPTICS, value).commit()
    }

    /**
     * Whether BrightControl has claimed the on-screen box for every app on the phone.
     *
     * Written only by [com.gios.lightchat.AlertOwnerReceiver], never by a settings screen: this is
     * not a preference, it is a fact about another app, and the user's own preference above stays
     * exactly where they left it so turning BrightControl's banners off gives the box straight
     * back. Read through [com.gios.lightchat.AlertOwner.ownedElsewhere], which also checks the
     * claimant is still installed.
     */
    fun alertsOwnedElsewhere(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERTS_OWNED, false)

    fun setAlertsOwnedElsewhere(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALERTS_OWNED, value).apply()
    }

    /**
     * Whether placing a call also texts the callee which number the call is coming from —
     * see `CallAnnounce`. Off by default: texting people automatically is the kind of thing
     * that should only ever happen because Gio asked it to.
     */
    fun callAnnounce(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CALL_ANNOUNCE, false)

    fun setCallAnnounce(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CALL_ANNOUNCE, value).apply()
    }

    /**
     * The phone's own number, typed in by hand — the fallback for a SIM whose line-1
     * number is blank, which plenty are. Takes precedence over the SIM when set, because
     * a number somebody typed on purpose beats one a carrier half-filled.
     */
    fun myNumber(context: Context): String? =
        prefs(context).getString(KEY_MY_NUMBER, null)?.takeIf { it.isNotBlank() }

    fun setMyNumber(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MY_NUMBER, value.trim()).apply()
    }

    /**
     * Whether this phone has ever opened the LightNotebook note for a conversation.
     *
     * The contact page's note row is a deep link and nothing more — LightNotebook is not
     * queried, so there is no way to ask whether a note exists. This is the honest half of
     * the answer: not "there is a note", but "you have been here before", which is what
     * decides between "Open note" and "Add a note".
     *
     * Keyed by the conversation's normalised handles (see `ChatViewModel.noteKey`), which
     * never contain a newline, so the same newline-joined storage as [favorites] holds.
     */
    fun noteOpened(context: Context, key: String): Boolean = key in notedKeys(context)

    fun setNoteOpened(context: Context, key: String) {
        if (key.isBlank()) return
        val next = notedKeys(context) + key
        prefs(context).edit().putString(KEY_NOTED, next.joinToString("\n")).apply()
    }

    private fun notedKeys(context: Context): Set<String> =
        prefs(context).getString(KEY_NOTED, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    /**
     * How long a one-time code is worth offering.
     *
     * Three minutes, which is shorter than any service's own expiry and longer than the walk
     * from "a code arrived" to "the field is focused". The number is a guess at human latency
     * rather than at cryptography: the cost of being too short is retyping six digits, and the
     * cost of being too long is the keyboard confidently offering a code that no longer works,
     * in the one slot the user taps without reading.
     */
    const val CODE_TTL_MS = 3 * 60 * 1000L

    /** A code and when it landed. */
    data class LoginCode(val code: String, val arrivedAt: Long)

    /**
     * Records the newest one-time code.
     *
     * Written by whichever of the socket or the catch-up poll saw the message first, and the
     * newer one wins — a phone that wakes to two codes should offer the one it can still use.
     * [arrivedAt] is the *message's* timestamp rather than now, so a code fished out of a
     * catch-up poll five minutes late is already expired by [loginCode] instead of arriving
     * fresh.
     */
    fun setLoginCode(context: Context, code: String, arrivedAt: Long) {
        if (code.isBlank()) return
        val existing = prefs(context).getLong(KEY_CODE_AT, 0L)
        if (arrivedAt < existing) return
        prefs(context).edit()
            .putString(KEY_CODE, code)
            .putLong(KEY_CODE_AT, arrivedAt)
            .apply()
    }

    /**
     * The code, if there is one and it is still fresh.
     *
     * The expiry is applied here rather than by clearing the value on a timer, because nothing
     * is guaranteed to be running to do the clearing — the process dies, the phone dozes, and
     * a value that outlived its window has to answer for itself when it is next read.
     */
    fun loginCode(context: Context, now: Long = System.currentTimeMillis()): LoginCode? {
        val code = prefs(context).getString(KEY_CODE, null)?.takeIf { it.isNotBlank() } ?: return null
        val at = prefs(context).getLong(KEY_CODE_AT, 0L)
        if (at <= 0L) return null
        // Also rejects a timestamp in the future, which is what a clock correction between the
        // Mac and the phone looks like, and which would otherwise pin a code indefinitely.
        val age = now - at
        if (age < 0L || age > CODE_TTL_MS) return null
        return LoginCode(code, at)
    }

    /**
     * The pinned conversation guids, newest pin first.
     *
     * Newline-joined like [favorites] beside it, and for the same reason: a guid never contains a
     * newline, and one preference read beats a JSON parse on every list recomposition.
     */
    fun pins(context: Context): List<String> =
        prefs(context).getString(KEY_PINS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setPins(context: Context, value: List<String>) {
        prefs(context).edit().putString(KEY_PINS, value.joinToString("\n")).apply()
    }

    /** One speed-dial slot: the number a held key rings, and whose it is. */
    data class Speed(val number: String, val name: String)

    /**
     * The slot on [digit], or null if nothing has been put there.
     *
     * Stored as one line per slot, `digit\u0000number\u0000name`, rather than as JSON: three
     * fields and nine possible rows do not justify a parser, and the separator is a character
     * that cannot occur in any of them.
     */
    fun speedDial(context: Context, digit: Int): Speed? = speedDialAll(context)[digit]

    fun speedDialAll(context: Context): Map<Int, Speed> {
        val raw = prefs(context).getString(KEY_SPEED_DIAL, null) ?: return emptyMap()
        val out = HashMap<Int, Speed>()
        for (line in raw.split('\n')) {
            val parts = line.split('\u0000')
            if (parts.size != 3) continue
            val digit = parts[0].toIntOrNull() ?: continue
            if (parts[1].isBlank()) continue
            out[digit] = Speed(parts[1], parts[2])
        }
        return out
    }

    fun setSpeedDial(context: Context, digit: Int, number: String, name: String) {
        if (digit !in 1..9 || number.isBlank()) return
        val next = speedDialAll(context).toMutableMap()
        next[digit] = Speed(number, name)
        prefs(context).edit()
            .putString(
                KEY_SPEED_DIAL,
                next.entries.joinToString("\n") { "${it.key}\u0000${it.value.number}\u0000${it.value.name}" },
            )
            .apply()
    }

    /**
     * Forgets the slot on [digit].
     *
     * Written as the whole map rather than by removing one line, because the storage is one
     * string: there is no key to remove, only a value to rewrite without it.
     */
    fun clearSpeedDial(context: Context, digit: Int) {
        val next = speedDialAll(context).toMutableMap()
        if (next.remove(digit) == null) return
        prefs(context).edit()
            .putString(
                KEY_SPEED_DIAL,
                next.entries.joinToString("\n") { "${it.key}\u0000${it.value.number}\u0000${it.value.name}" },
            )
            .apply()
    }

    /**
     * The newsletter batches — named recipient sets one message broadcasts to.
     *
     * Local by construction: BlueBubbles has no concept of a mailing list, so there is nothing
     * on the server to sync with. Same reasoning as [favorites], and the same consequence —
     * they live and die with this install (LightSync carries them, since they are in this
     * preference file and are not the encrypted password).
     */
    fun newsletters(context: Context): List<NewsletterBatch> =
        NewsletterJson.decode(prefs(context).getString(KEY_NEWSLETTERS, null))

    fun setNewsletters(context: Context, value: List<NewsletterBatch>) {
        prefs(context).edit().putString(KEY_NEWSLETTERS, NewsletterJson.encode(value)).apply()
    }

    /** Sign out: wipe the stored password. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
        // The saved GIFs are the one thing whose *files* live outside this preference file (see
        // [com.gios.lightchat.Gifs]), so clearing the list here would leave the bytes on the phone
        // with nothing left pointing at them.
        runCatching { java.io.File(context.filesDir, "gifs").deleteRecursively() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
