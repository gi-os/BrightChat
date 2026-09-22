## BrightChat v2.42 — edit a message you already sent

**Hold one of your own messages and the menu has Edit beside Reply.** The words come into
the compose field, the button reads Save, and the row shows the new text the moment you press
it — put back if the Mac refuses. iMessage allows edits for fifteen minutes after sending, so
the option appears only inside that window, only on your messages, and only on ones with
words in them; a photo has nothing to edit. Like tapbacks and replies it needs the server's
Private API, and the Mac has to be on Ventura or later, which is where Apple added editing.

"Edited" appears under a turn that was, yours or theirs, on the same line as the receipt.

One thing had to be right underneath. After an edit the message arrives again several times —
the edit's own echo, then a delivery stamp, then a read stamp — and a stamp serialized before
the edit reached the database carries the old words. The thread merge used to take such a row
whole, which would flip the text back. Now a row's words and edit stamp only move forward:
an update that knows nothing of an edit cannot undo one. Two tests in `ThreadMergeTest`.

Asked for on Discord by Bhughes1335.

## BrightChat v2.41 — a failure reports itself

**Every "Couldn’t …" the app puts on screen now also raises the SEND ERROR? chip.** Until
now the only failures that reached the tracker were the ones somebody was annoyed enough to
shake the phone about, which is a biased sample of exactly the wrong kind: the quiet ones —
a photo that would not send, an attachment that would not download, a rename the server
refused — left a sentence on screen for a moment and nothing anywhere else.

Twelve of those sentences, the send rollback and the general server-error path go through
one `fail()` now, which writes the sentence to the screen and the same words, with the
exception's class and message, to light-common's `Trouble`. That is what puts the chip up.
It is deduped per sentence per hour, so a tunnel that is down asks once rather than on every
tap. Nothing in the detail is a message body: this app's transport fails with HTTP statuses
and socket errors.

## BrightChat v2.40 — a report now says where it came from

**Every bug report this app ever filed said it came from the `home` screen.** The field the
report reads is set by the app's navigation, and nothing in this app ever set it, so a shake on
a thread, a shake on the dialer and a shake in Settings all read the same. The router in
`MainActivity` already decides which screen draws in one `when`; the same decision now names
the screen — `thread`, `dialer`, `settings`, `list-favorites`, `newsletter-compose` and so on —
right after it draws.

This came out of [light-reports#476]: the phone stopped taking touches while a message was being
sent, came back after the screen slept and a couple of minutes more, and "Buzz on tap" was back
on afterward. The report carried no trace (the app did not die) and said `home`, which it was
not. The freeze itself is not fixed here: a phone that ignores the power button for minutes is
below the app, and nothing in the send path blocks the main thread. What this release does is
make the next such report say `thread`, and remove the one way the app itself could have lost
that switch — the setting was written with `apply()`, which queues the write, and a queued write
is gone if the process is killed before it lands. It is `commit()` now. One boolean from a tap
on Settings; nobody will feel the difference.

Tied to [light-reports#476] — the phone stopped responding to touches while sending.

## BrightChat v2.39 — one switch turns every buzz off

**Settings has a Panel section now, with "Buzz on tap" in it.** Turn it off and nothing you
touch in the app buzzes: no tap, no long press, no menu pick, on any screen.

Nearly two hundred places in this app tick the motor. Gating them one at a time would have
meant editing every screen, and the next button added would have arrived ungated, which is the
kind of bug that comes back. So the switch went where they all already look. Compose hands every
one of them the same haptic-feedback object, and the app's theme — the one piece all three of
its windows go through — now hands them a wrapped one with the switch in front. Every button
that exists and every button that will exist is covered without knowing the switch is there.

The buzz a new message makes is deliberately not covered. That is an alert, not feedback on
something you just touched, and someone who wants a quiet keypad still wants to know a text
arrived. It stays under the phone's own notification settings.

On by default: nothing on these screens has a button border, and the tick is how a tap says it
landed.

## BrightChat v2.38 — unread dots stop clearing themselves

**A chat you never opened no longer loses its unread dot.**

When the Mac reads a chat, the server sends a `chat-read-status-changed` event and the app
drops that chat's dot. The event carries no timestamp and the server's chat.db poller will
report `read: true` about the state just before a new message, so the app checks the claim
first: it asks for the chat's newest messages and looks for a `dateRead` stamp.

The check answered yes or no, and it answered no by saying "not unread" — which cleared the
dot. So every way of failing to get an answer cleared it: the tunnel being down, a timeout,
the event arriving before the API client was built, or a chat whose newest rows are all mine
or all group renames, none of which carry a `dateRead` at all. The clear is written through
to disk, so a dot lost that way stayed lost, and the message behind it went unread forever.

The check now has a third answer. `ReadReceipt.verdict` returns read, unread, or unknown,
and the dot is cleared only on a straight read. Everything else leaves it alone — a dot that
lingers a few minutes longer costs a glance, a dot that vanishes costs the message. The same
verdict now backs the socket's notification-dismissal check, which had the same three cases
and already treated them the right way round; the two were opposite readings of the same
boolean, which is how one of them came out backwards.

It shows up most on chats you don't open on the phone, because those are the ones whose dot
is the only thing telling you they're there.

- 141 tests.

## BrightChat v2.37 — video plays in the app, header actions are icons, and a Settings hint

**Header polish, and a look at what Phase 3 already shipped.** Video attachments already play
in-app (a full-screen `VideoView`, no hand-off to a non-existent player) and photos already
support pinch-to-zoom, drag-to-pan and double-tap-to-zoom in the full-screen viewer — both
landed in earlier Phase 3 work and are confirmed still working. This release is the next round
of polish on top:

- The list header's "New" and the thread header's "Call" are icons now — a "+" and a phone
  glyph, matching the app's existing hand-drawn icon set (no new dependency) — with "New
  conversation" / "Call" kept as the accessibility label.
- The list title doubles as the way into Settings, with nothing marking it as tappable. The
  first time the list shows each time you open the app, the title briefly fades to "Settings"
  and back — once, not a loop — as a quiet hint that it's there.

- 133 tests.

## BrightChat v2.35 — an old message no longer resurfaces in the list preview

**A conversation row no longer shows an old message again after newer ones arrived.**

The live socket was folding every `updated-message` into the list row's preview the same way it
folds a brand-new one — but an `updated-message` can be a read receipt or delivery stamp landing
on a message from hours or days ago. The row took that older message's text and date and rewound
itself to it, so the list showed a message the user had long since moved past ("cleaners are
done", again) until the next refresh re-sorted it. The socket path now applies the same
out-of-order guard the sweep path always had — an event older than the row's newest activity
leaves the preview alone — so a late receipt can no longer walk the row backwards.

Fixes [light-reports#292] — the home screen showed an old message again.

- 133 tests.
