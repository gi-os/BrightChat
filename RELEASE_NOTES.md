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
