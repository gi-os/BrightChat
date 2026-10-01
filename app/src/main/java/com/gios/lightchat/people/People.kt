package com.gios.lightchat.people

import com.gios.lightchat.Conversation

/**
 * Manual decisions about who is who: chats joined by hand, and pairs split by hand.
 * Each entry is two conversation guids. See [People.merge].
 */
data class PeopleLinks(
    val joined: Set<Pair<String, String>> = emptySet(),
    val split: Set<Pair<String, String>> = emptySet(),
) {
    fun isSplit(a: String, b: String): Boolean = (a to b) in split || (b to a) in split

    fun join(a: String, b: String): PeopleLinks =
        copy(joined = joined + (a to b), split = split - (a to b) - (b to a))

    fun unjoin(a: String, b: String): PeopleLinks =
        copy(joined = joined - (a to b) - (b to a), split = split + (a to b))
}

/**
 * One chat per person: a person's iMessage, WhatsApp, Signal… one-to-ones shown as one row.
 *
 * ### When two chats are the same person
 *
 * Automatically only when it is hard to be wrong, because a wrong join sends a message to the
 * wrong person:
 *
 * - both are one-to-ones (groups are never joined),
 * - they resolve to the **same full name** (two words or more): the address book's name for the
 *   iMessage handle, the network's own name for a Beeper chat,
 * - or they share a **phone number or email** (see `beeper/BeeperIdentities`), which joins them
 *   whatever the names say,
 * - they are on **different networks**, and no network has two chats with that name. Two
 *   WhatsApp chats both called "Alex Kim" are two people, or one we can't tell apart, so neither
 *   joins anything.
 *
 * A hand join ([PeopleLinks.joined]) overrides all of that; a hand split ([PeopleLinks.split]) is
 * never undone by a later automatic match.
 *
 * ### What the joined row is
 *
 * The member that is starred, else the iMessage one, else the newest, with every member's guids.
 * Keeping an existing guid (rather than inventing `person:…`) is what keeps pins, favorites,
 * nicknames, notes and chat backgrounds working unchanged. Its preview is the newest member's.
 *
 * Pure: the list, the names and the links in, the rows out. No Android, so JUnit covers it.
 */
object People {

    /**
     * [nameOf] gives the name a one-to-one resolves to, or null when it has none worth matching
     * on (a bare number, an unsaved handle).
     */
    fun merge(
        conversations: List<Conversation>,
        nameOf: (Conversation) -> String?,
        links: PeopleLinks,
        favorites: Set<String>,
        keysOf: (Conversation) -> Set<String> = { emptySet() },
    ): List<Conversation> {
        val candidates = conversations.filter { !it.isGroup && !it.isAgent }
        val byGuid = candidates.associateBy { it.guid }
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
            parent[x] = r
            return r
        }
        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        candidates.forEach { parent[it.guid] = it.guid }

        // Automatic: same full name, different networks, one chat per network.
        candidates
            .mapNotNull { c -> nameOf(c)?.let(::normalize)?.takeIf(::isFullName)?.let { it to c } }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { group -> group.size > 1 }
            .filter { group -> group.groupBy(::networkOf).values.all { it.size == 1 } }
            .forEach { group ->
                for (i in group.indices) for (j in i + 1 until group.size) {
                    if (!links.isSplit(group[i].guid, group[j].guid)) union(group[i].guid, group[j].guid)
                }
            }
        // Automatic: the same phone number or email, different networks. A number is a harder
        // match than a name, so this joins whatever the names say, but the same guards apply: one
        // chat per network, and never over a hand split.
        candidates
            .flatMap { c -> keysOf(c).map { it to c } }
            .groupBy({ it.first }, { it.second })
            .values
            .map { group -> group.distinctBy { it.guid } }
            .filter { group -> group.size > 1 }
            .filter { group -> group.groupBy(::networkOf).values.all { it.size == 1 } }
            .forEach { group ->
                for (i in group.indices) for (j in i + 1 until group.size) {
                    if (!links.isSplit(group[i].guid, group[j].guid)) union(group[i].guid, group[j].guid)
                }
            }
        // Joins chain: a name match and a number match can each be sound and still, together,
        // put two different people's iMessage chats in one row. So the guards are checked again on
        // what came out — one chat per network, no hand split inside — and a component that fails
        // keeps only what was joined by hand.
        val auto = candidates.groupBy { find(it.guid) }.values.filter { it.size > 1 }
        for (component in auto) {
            val overlapping = component.groupBy(::networkOf).values.any { it.size > 1 }
            val split = component.any { a -> component.any { b -> a.guid < b.guid && links.isSplit(a.guid, b.guid) } }
            if (overlapping || split) component.forEach { parent[it.guid] = it.guid }
        }

        // By hand. Always wins: a person may have two iMessage chats (a number and an email).
        links.joined.forEach { (a, b) -> if (a in byGuid && b in byGuid) union(a, b) }

        val components = candidates.groupBy { find(it.guid) }.values.filter { it.size > 1 }
        if (components.isEmpty()) return conversations
        val absorbed = components.flatten().mapTo(HashSet()) { it.guid }
        val people = components.map { person(it, favorites) }
        return (conversations.filterNot { it.guid in absorbed } + people).sortedByDescending { it.lastDate }
    }

    fun person(members: List<Conversation>, favorites: Set<String>): Conversation {
        val sorted = members.sortedByDescending { it.lastDate }
        val newest = sorted.first()
        // Stable whatever arrives next: the row's guid carries its nickname, notes and background,
        // so it must not move from WhatsApp to Signal because Signal spoke last.
        val primary = sorted.firstOrNull { it.guid in favorites }
            ?: sorted.firstOrNull { !it.isBeeper }
            ?: sorted.minBy { it.guid }
        return primary.copy(
            guids = sorted.flatMap { it.guids }.distinct(),
            members = sorted,
            lastText = newest.lastText,
            lastDate = newest.lastDate,
            lastFromMe = newest.lastFromMe,
            lastSender = newest.lastSender,
            lastReaction = newest.lastReaction,
            unread = sorted.any { it.unread },
            network = networkOf(newest),
        )
    }

    /** The network a chat is on, as the thread's dividers and the list row name it. */
    fun networkOf(c: Conversation): String = c.network ?: "iMessage"

    /**
     * A phone number or an email in the form matching uses: lowercased email, or every digit with
     * a bare ten-digit number taken as North American. Not [com.gios.lightchat.Contacts.key]'s
     * last-ten-digits form, which makes +39 347 123 4567 and +1 347 123 4567 the same person.
     * Null for anything too short to be a person's number (short codes, typos).
     */
    fun matchKey(address: String, countryCode: String = "1"): String? {
        val a = address.trim()
        if (a.contains("@")) return a.lowercase().takeIf { it.length > 3 }
        val digits = a.filter { it.isDigit() }
        if (digits.length < 8) return null
        // International form: "+…", or "00…" as dialled from most of the world.
        if (a.startsWith("+")) return digits
        if (digits.startsWith("00")) return digits.drop(2)
        // National form, stored as dialled at home: the phone's own country code goes in front,
        // after the trunk 0 most countries dial before a national number (Italy keeps its 0).
        if (countryCode == "1") {
            return when {
                digits.length == 10 -> "1$digits"
                digits.length == 11 && digits.startsWith("1") -> digits
                else -> digits
            }
        }
        if (digits.startsWith(countryCode) && digits.length > countryCode.length + 7) return digits
        val national = if (digits.startsWith("0") && countryCode != "39") digits.drop(1) else digits
        return countryCode + national
    }

    /** The calling code for an ISO country (`us`, `it`…), for [matchKey]'s national numbers. */
    fun callingCode(iso: String?): String = when (iso?.lowercase()) {
        "it" -> "39"; "gb", "uk" -> "44"; "fr" -> "33"; "de" -> "49"; "es" -> "34"; "pt" -> "351"
        "nl" -> "31"; "be" -> "32"; "ch" -> "41"; "at" -> "43"; "ie" -> "353"; "se" -> "46"
        "no" -> "47"; "dk" -> "45"; "fi" -> "358"; "pl" -> "48"; "gr" -> "30"; "mx" -> "52"
        "br" -> "55"; "ar" -> "54"; "co" -> "57"; "in" -> "91"; "au" -> "61"; "nz" -> "64"
        "jp" -> "81"; "kr" -> "82"; "cn" -> "86"; "il" -> "972"; "za" -> "27"
        else -> "1"
    }

    fun normalize(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Two words with letters in them. "Alex" alone is too common to join anything on. */
    fun isFullName(normalized: String): Boolean {
        val words = normalized.split(' ').filter { w -> w.any { it.isLetter() } }
        return words.size >= 2
    }
}
