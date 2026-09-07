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
