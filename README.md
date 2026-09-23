<img src="docs/icon.png" alt="" width="72" align="left" />

# BrightChat

[**⬇ Download the latest APK**](https://github.com/gi-os/BrightChat/releases/latest) · free, open source.

An **iMessage client** for the [Light Phone III](https://www.thelightphone.com/),
talking to an always-on, self-hosted [BlueBubbles Server](https://github.com/BlueBubblesApp/bluebubbles-server)
reached privately over [Tailscale](https://tailscale.com/). Package
`com.gios.lightchat`.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightChat.png" alt="Scan to open BrightChat in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightChat there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

**Current version: v2.35.x.** See [Version history](#version-history).

> ### About this fork
>
> This is [gi-os](https://github.com/gi-os)'s fork of
> **[craigeley/chat](https://github.com/craigeley/chat)**. Craig Eley wrote the app;
> this fork renames it to BrightChat (`com.gios.lightchat`) to sit with the rest of the
> [gi-os Light Phone tools](https://github.com/gi-os/awesome-light) and adds everything
> from [Favorites / Known / Unknown tabs](#favorites-known-unknown-tabs) onward, below.
> Nothing here is upstream's responsibility — send bugs in these features to this repo,
> not to Craig. Craig's original README is preserved at the bottom of this file, as he
> wrote it, for attribution and history.
>
> **Coming from the old `com.craigeley.chat` build?** The package id changed, so this
> installs alongside it rather than updating it. Uninstall the old one, re-run setup,
> re-run both `adb` grants, and re-add the app in Obtainium — it sees a different
> package. Starred chats don't carry over either.

Open the app to a list of conversations (newest activity first); tap one to read the
thread, or tap **New** to start one (searches your contacts by name/number/email). Tap
**Messages** at the top for settings, **Refresh** to re-pull.

## What this is and why

Craig Eley built the original `chat` to talk to a BlueBubbles server over Tailscale, no
Google push involved, it adds:

- **Favorites / Known / Unknown tabs.** The conversation list is three lists behind an
  icon bar: starred chats, chats with a name in the Mac's address book, and everything
  else. Long-press a row to star it. Exiting a thread returns you to where you were in
  the list rather than the top of it.
- **Full-colour photos.** Tapping a photo lifts LightOS's greyscale for exactly as long
  as the viewer is open. Needs a one-time adb grant — see [Optional features](#optional-features).
- **Heads-up messages.** A minimal box over whatever you're doing when a text arrives,
  with the sender, the message, and a buzz. Needs a one-time adb grant.
- **A photo picker that works.** The system one reads MediaStore, which nothing keeps
  current on LightOS, so photos you just took were never offered. This one reads DCIM
  and Pictures directly. Multi-select, an inline camera, and the whole thing runs in
  colour — with the grayscale grant, picking and framing a photo aren't guesswork.
- **GIFs, from the same library Discord uses.** A **GIF** key beside the `+` opens a picker —
  trending, search, and a grid you can scroll with the wheel. Holding one saves it, which keeps
  the file on the phone rather than a link to somebody else's server, so the Saved tab works with
  no signal. See [GIFs](#gifs).
- **Videos send as well as receive.** Clips sit in the same grid as the photos, each
  showing how long it runs, and go out as normal iMessage attachments. The file is
  streamed off disk rather than read into memory, so the size of a recording is the
  connection's problem and not the phone's; anything over 100MB is refused up front
  instead of failing slowly. Playback of what arrives was already there.
- **The brightness wheel scrolls.** Threads, the conversation list, contact search, the
  photo grid — see [The wheel](#the-wheel).
- **Background delivery that survives sleep.** See
  [Don't miss messages while the phone sleeps](#dont-miss-messages-while-the-phone-sleeps).

<p>
<img src="docs/screenshots/thread.png" width="260" alt="A thread in BrightChat on a Light Phone III">
</p>

## Quick start

You need three things running before the app is any use:

1. A LightPhoneIII with the full Android layer exposed (see
   [this guide](https://acrobat.adobe.com/id/urn:aaid:sc:US:0c80fa32-de30-406f-85ca-93ccd92c3c4b)).
2. A BlueBubbles server on an always-on Mac, signed into your iMessage account.
3. Tailscale installed on that Mac *and* on the modified LPIII.

**1. Install BlueBubbles Server** from [bluebubbles.app](https://bluebubbles.app/install/)
on the Mac. During setup it asks for a server password — remember it, the app uses it to
authenticate. Skip the Google Firebase section entirely, and set the Proxy Service to
LAN only; Tailscale handles the rest.

**2. Put the Mac and the phone on the same tailnet.** Install
[Tailscale](https://tailscale.com/download) on both, same account. On the Mac, expose
the BlueBubbles port (default `1234`) over HTTPS:

```sh
tailscale serve --bg 1234
```

This gives the Mac a stable `https://<machine>.<tailnet>.ts.net` URL with TLS, reachable
only from your own devices. `tailscale serve status` shows the URL.

**3. Install the app.** Grab the newest signed APK from
[Releases](https://github.com/gi-os/BrightChat/releases) or track this repo in
**Obtainium**.

**4. Configure it.** On first launch, enter the `https://…ts.net` URL and the
BlueBubbles server password. The app validates them against the server and stores them
on the device.

That's the whole path to a working thread list — the parts that take real time are
setting up BlueBubbles and Tailscale on the Mac, not the app itself.

## Configuration and usage

### Optional: enable the Private API (tapbacks, read receipts, typing)

By default BlueBubbles can only send via AppleScript, which can't send tapbacks, mark
chats read, or send typing indicators. Those need BlueBubbles' **Private API**, which
injects a helper into Messages — and that requires turning off two macOS protections.
Skip this and everything else still works.

1. **Disable Library Validation** (lets the helper load into Messages):

   ```sh
   sudo defaults write /Library/Preferences/com.apple.security.libraryvalidation.plist DisableLibraryValidation -bool true
   ```

2. **Disable System Integrity Protection (SIP).** Boot into Recovery (Apple Silicon:
   hold the power button → *Options*; Intel: hold ⌘R at boot), open Terminal, run
   `csrutil disable`, reboot, verify with `csrutil status` (should read `disabled`).
   On Apple Silicon this also disables running iOS apps on the Mac. A VM snapshot first
   is wise.

3. **Flip it on in the server.** BlueBubbles Server → *Settings* → **Private API**
   toggle on — the server injects the helper itself, no separate bundle. Hit refresh on
   the **Private API Status** box; it should report the helper connected.
   (`GET /api/v1/server/info` shows `"private_api": true` and `"helper_connected": true`
   — the app reads this to decide whether to offer tapbacks etc.)

### Beeper (beta): WhatsApp, Signal and the rest

BrightChat can also sign in to a [Beeper](https://www.beeper.com) account. Chats from every
network Beeper bridges (WhatsApp, Signal, Telegram, Instagram, Messenger, Discord and others)
appear in the same list as iMessage. Each row names its network.

1. Open **Settings → Beeper (beta)**. With no Mac set up, use **No Mac? Sign in with Beeper**
   on the first screen instead.
2. Type your Beeper email. Beeper emails you a six-digit code.
3. Type the code.
4. Type your **recovery key** (Beeper → Settings → Security). Without it, new messages arrive
   but older ones stay encrypted.

In a Beeper chat you can send text, photos, videos and files, reply, react, edit your own
messages, and rename or leave a group. These work whether or not the Mac's Private API is on.

**Notifications.** A Beeper message alerts the same way an iMessage does: a notification, a
buzz, and the on-screen box. Your Beeper mute and mention settings apply, and an alert goes away
when you read the chat on another device. While the phone sleeps, Beeper sends a wake-up
(room and message IDs only, no text) through [ntfy](https://ntfy.sh) to a random topic for
this phone. BrightChat then syncs once and decrypts the message on the phone. To use your own
ntfy server, tap **Change push server** under Beeper. The server needs a public HTTPS address
and `base-url` set; ntfy's Matrix gateway is on by default.

Not yet in this release: emoji verification with another device, emoji reactions outside the
six tapbacks, and adding people to a bridged group. The
**Show log** line under the Beeper settings shows what the connection did; include it in a bug
report.

iMessage stays on BlueBubbles. Beeper runs on the phone and talks to Beeper's servers; no Mac
is needed for it.

**One row per person.** With Beeper signed in, a person you talk to on iMessage and WhatsApp (or
any two networks) is one row. BrightChat joins two chats only when both are one-to-ones, both
resolve to the same full name (the address book's name for the iMessage chat, the network's name
for the Beeper one), they are on different networks, and no network has two chats with that
name. Anything else stays separate until you join it: open the chat, tap the name, then
**Link another chat**. **Unlink** on the same page splits a chat off, and it stays split.

In a person's thread the line under the name picks what you see: **All**, one network, or
**Calls**. Calls lists the phone's own calls with that person (BrightChat asks for call-history
access the first time) and the call notices WhatsApp or Signal post. The **via** line above the
keyboard shows where the next message goes, the network they last wrote from by default; tap it
to change. A reply always goes back to the network of the message it answers.

Each chat's network shows as a small two-letter tile after the name: iM iMessage, SMS, WA
WhatsApp, SG Signal, TG Telegram, IG Instagram, MS Messenger, DC Discord, SL Slack, IN LinkedIn,
TW Twitter, GC Google Chat, BS Bluesky, BE Beeper. **Settings → Default network** picks the one
you use most (iMessage to start): its chats carry no tile, and messages to a person go out on it
when they have a chat there.

The Dial tab also lists **Recent** calls under the speed dials: phone calls (named from your
contacts) and WhatsApp or Signal call notices. Tap a phone call to ring back, or a Beeper call to
open that chat.

Without Beeper none of this appears, and the app looks and works as before.

### Optional features

**Full-colour photo viewing.** The Light Phone's grayscale is Android's accessibility
color-correction filter, which apps can lift with a permission grantable only over adb.
Tapping a photo in a thread then shows it in full colour for exactly as long as the
viewer is open — the phone returns to grayscale the moment you dismiss it (the same
trick as [zero](https://github.com/vandamd/zero)'s red-text mode):

```sh
adb shell pm grant com.gios.lightchat android.permission.WRITE_SECURE_SETTINGS
```

One-time; survives app updates. Without it, photos open in grayscale like the rest of
the phone.

**Heads-up messages.** A text arriving while you're elsewhere buzzes and drops a small
box over whatever you're doing: sender, two lines of message, gone in four and a half
seconds. Tap it to open the thread, swipe up to dismiss it early. It also wakes the
panel, so a message arriving with the phone face-down still shows. Getting a window up
from the background needs one appop — on Android 14 this is what exempts an app from
background-activity-start restrictions:

```sh
adb shell appops set com.gios.lightchat SYSTEM_ALERT_WINDOW allow
```

One-time; survives reboots and app updates. Without it you still get the buzz and the
notification, just not the box.

### Don't miss messages while the phone sleeps

Delivery is a socket BrightChat holds open itself — there's no Google push on this phone
— and a socket doesn't survive the phone sleeping. BrightChat backs it with an
`setAndAllowWhileIdle` alarm, the one kind that fires during Doze, which re-pulls the
list and notifies for anything missed.

What actually throttles that isn't Doze, it's **App Standby buckets**. The longer the
phone goes unused without BrightChat being opened, the further Android demotes it —
active → working set → frequent → rare → restricted — and each step defers its alarms
harder. By `rare`, an alarm asking for five minutes is held for **two hours**;
`restricted` holds it for a day — which means the exact situation the poll exists for
(phone face-down overnight, app not opened) is the situation Android throttles it out
of. One command turns that off rather than negotiating with it:

```sh
adb shell dumpsys deviceidle whitelist +com.gios.lightchat
```

This puts the app in the **exempt** bucket: no alarm deferral at all, and network access
during Doze instead of a ~10 second window per alarm. BrightChat notices and polls every
5 minutes instead of 10. Survives reboots and app updates.

**Settings tells you whether it took.** The bottom of the Settings screen reads either
`Background delivery: unrestricted` or `Background delivery: throttled (rare)`, plus
when the app last actually heard from the server — the only way to tell from the phone
that background delivery stopped hours ago rather than nobody having texted you.

For instant delivery after a reboot without opening the app, enable Tailscale's
**Always-on VPN** (Android Settings → Network → VPN) and leave "Block connections
without VPN" **off** — the live socket reconnects the moment the tunnel comes up.

### Unknown senders

**Silent by default.** An iMessage account that has been around a while gets a steady trickle
from short codes, delivery notices, two-factor senders and whoever last had your number — and on
this phone each one buzzes, wakes the panel and drops a box over whatever you were doing. So out
of the box only your contacts and named groups do that.

The messages still arrive: they appear in the list with an unread mark and open normally, they
just don't interrupt. **Settings → Unknown senders** switches it, and "known" is the same test
the list's Known tab uses — a named group, or any participant in your address book.

### GIFs

The **GIF** key beside the `+` in a thread opens the picker: **Trending** when it opens, a search
box, and **Saved** and **Recent** beside them. Tap a GIF to arm it, tap again — or tap **Send** at
the foot — to send it. **Hold** one to save it.

What goes out is the file, not a link, so it arrives as an ordinary iMessage attachment and plays
inline wherever the other person reads their messages. GIFs that arrive here play too, in the
thread and full screen.

**Search works out of the box.** The service is [KLIPY](https://klipy.com/developers), which is
what Discord's GIF search runs on since Google shut the Tenor API down on 30 June 2026, and the
released APK ships with a key — so there is nothing to set up.

That key's allowance is **per key, not per install**: every BrightChat draws on the same one, so a
busy hour is a busy hour for all of them, and searching then says so. The answer is your own key,
free and a minute on their partner panel, in **Settings → GIFs** — typed, or scanned as a QR code
(`qrencode` over the key on a laptop, same as the transcription key). It takes precedence over the
shipped one.

The shipped key is a repository secret (`KLIPY_KEY`), not a line in this repo, and reaches the APK
scrambled rather than as a readable string — which stops `strings app.apk` and the scrapers, and
nothing more than that. **Build it yourself and GIF search is off** until you put a key in
Settings; everything else about the picker, saving included, works regardless.

**Saving keeps the GIF, not a bookmark.** A saved GIF is copied onto the phone, so the Saved tab
works with no key, no tunnel and no signal — and it survives the provider losing, re-slugging or
switching off the original, which is not a hypothetical. Recent is the last two dozen you actually
sent, cached rather than kept.

### Photos from Roll

[Roll](https://github.com/gi-os/Roll), the camera app, sends straight here: its send
button opens your contacts, you pick a person, and BrightChat opens on that thread with the
photograph already sent. No chooser in between.

That works because BrightChat registers as an image share target and reads the recipient from
the share's `address` extra — the same convention the stock messaging apps use, so anything
else that shares a photo to BrightChat works too, it just lands on the conversation list and
waits for you to pick a thread.

### The contact page

Tap a conversation's title — a group or a 1:1 — and you get everything it has
accumulated: the people in it, a note, every photograph anyone sent, and every link.

The photographs are a grid of what the phone holds for that conversation, newest first, and
tapping one opens it full screen, in colour, the same viewer the thread uses. The links are
every URL anyone sent, once each, with who sent it and when.

Neither is the whole conversation, and the page says so. It reads the newest couple of
hundred messages; scrolling to the bottom — or tapping **Look further back**, for a page
too short to scroll — asks the Mac for the page under it. So a conversation you never
scroll costs nothing, and one you do costs a page at a time. A chat whose thread has never
been opened holds nothing at all, and the page says that rather than claiming there are no
photographs in it.

The **Note** row opens [BrightNotebook](https://github.com/gi-os/BrightNotebook), which keeps
one note per conversation and makes it on the first tap. Nothing is read back across the
gap — the row is a door, not a preview — and if Notebook isn't installed the row says
so and does nothing.

The note is keyed by the conversation's normalised handles (`+12125550148`), never by its
chat guid: a guid belongs to one Mac's `chat.db`, so restoring a backup, or moving to
another Mac, would strand every note ever written. The cost is that changing a group's
membership changes its key, and the old group note stays in Notebook but stops being
reachable from here. A 1:1, which is what the note is mostly for, never moves.

### The wheel

Turning the brightness wheel scrolls whatever is up: a thread, the conversation list,
contact search on the new-message screen, a group's member list, the photo grid, and
the setup form. Only the turns — the wheel click and the camera button belong to
[BrightControl](https://github.com/gi-os/BrightControl), which owns them phone-wide and
passes bare notches through to `com.gios.*` for exactly this.

It works because the wheel arrives as an ordinary key event. Light patched
`/system/usr/keylayout/Generic.kl` to label scancodes 19 and 20 `WHEEL_CCW`/`WHEEL_CW`,
and nothing in `PhoneWindowManager` intercepts them, so they reach the focused window
like any other key — which is also why an app that ignores the keycode appears to have
a dead wheel. `LightKeys` in `light-common` resolves the labels at runtime and falls back to the raw
scancode, gated on the sensor's device name so a paired keyboard's `r` can't scroll a
thread.

Reading a text is the case that makes the handling fussy. The keys are claimed in
`dispatchKeyEvent`, above the view hierarchy, so a notch reaches the thread rather than
the compose bar that has focus — and both halves of each DOWN+UP pair are swallowed,
because a wheel that types into a half-written message is worse than one that does
nothing. The thread's list is `reverseLayout`, which reverses its scroll axis too, so
the sign is flipped for that one list. Notches are frame-timed rather than applied as
they land — the sensor fires every ~35 ms, faster than a frame — and the first notch
after a pause is held until a second confirms it, since the wheel sits under a thumb and
a stray brush shouldn't move the message you were reading. `WheelScroll` in `light-common` has the
numbers.

## Building

```sh
./gradlew :app:assembleDebug
```

## Contributing

Solo repo, no PR workflow for the `gi-os` additions: commits go straight to `develop`,
which is this repo's **default branch** — not `main`. Every push to `develop` triggers
CI (the house `build.yml`, adopted in v1.0.7), which builds, signs, and publishes a
GitHub Release. **A push is a release, not a cosmetic action** — verify before pushing,
not after.

Signing is mandatory and the keystore is **not** committed (unlike the other `gi-os`
repos) — it lives in repo secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` /
`KEY_ALIAS` / `KEY_PASSWORD`, with the certificate pinned in `signing-fingerprint.txt`.
Two more secrets are read the same way and are both optional — `REPORT_TOKEN`, which
shake-to-report files issues with, and `KLIPY_KEY`, the GIF search key the APK ships
with. A build without either still works; the feature it belongs to is simply off.
`versionCode` is the workflow run number; `versionName` in the committed
`build.gradle.kts` (currently `1.0.0`) is only the `major.minor` base — CI stamps
`major.minor.RUN` at build time and tags it `vX.Y.Z`.

## Version history

Real tags, oldest to newest (the early `0.x` history predates the `gi-os` fork's
`vX.Y.Z` CI convention and was tagged directly off build-script version bumps):

| Version | What changed |
| --- | --- |
| v0.1.0 | First release: BlueBubbles client over AppleScript send |
| v0.1.1 – v0.1.2 | App icon, capitalized name |
| v0.1.3 – v0.1.4 | Group-chat send fixes, including forked-group sends via AppleScript |
| v0.1.5 | Replies, group management, failed-send surfacing, absolute list times |
| v0.1.6 | Unread markers, notification deep-links, full-screen image viewer |
| v0.5.0 | Full-colour photo viewing (lifts LightOS's grayscale while the viewer is open) |
| v0.6.x–ci / v0.7.1–v0.7.2 | Renamed to **BrightChat** (`com.gios.lightchat`, repo `gi-os/BrightChat`); Favorites/Known/Unknown tabs; own photo picker with inline camera; heads-up box + buzz for incoming messages |
| v0.7.3 | Inline camera, photo picker runs in colour |
| v0.7.4 | Heads-up overlay, mark-all-read, double-tap tapbacks, no `null` chats |
| v0.7.5 | Fix: don't miss messages that arrive while the app is away |
| v0.7.6 | Fix: actually check for messages while the phone is asleep |
| v1.0.7 | Fix: always notify, even after the phone has been off for hours; adopts the house CI workflow |
| v1.0.8 | Hardware wheel scrolls threads, the conversation list, contact search, and the photo grid |
| v1.0.9 | README: documents what the wheel needs |
| v1.0.10 – v1.0.13 | Unknown senders are silent by default; messages are kept on the phone and only the delta is synced; receiving photos shared from Roll |
| v1.1.14 | A contact page for every conversation: photos, links, and a note kept in BrightNotebook |
| v1.2.x | Groups offered to Roll's send picker (`ChatsProvider`) and addressed by guid on the way back in; a login code from an unknown sender always alerts, and is served to LightKeyboard for three minutes |
| v1.3.x | Call the person you're texting — `ACTION_DIAL` to the default calling app, from the thread header for a 1:1 and from every name on the contact page |
| v1.4.x | Calling actually works: `ACTION_CALL` to the telecom stack (falling back to `ACTION_DIAL` if `CALL_PHONE` is refused), and the `tel:` URI no longer percent-encodes the `+` out of an E.164 number |
| v1.5.x | The call screen is brought up after placing (`TelecomManager.showInCallScreen`, retried past the radio) instead of the call connecting in the background; Call confirms on a second tap |
| v1.6.x | The phone app is opened outright after placing a call, since LightOS's dialer ignores `showInCallScreen` |
| v1.7.x | Placing a call backgrounds BrightChat to the home screen instead of launching the phone app, so the call screen has the foreground and hanging up doesn't land back in the thread |
| v1.8.x | The step aside happens on the tap rather than 1.8 seconds later — the delay existed for a retry ladder that no longer exists |
| v1.9.x | Fixes v1.8 going home without calling: `ACTION_CALL` starts an activity that places the call, and the immediate home launch cancelled it. Uses `TelecomManager.placeCall` instead, which needs no activity |
| v2.0.x | A dialer that is also the contacts list — T9 search over the phone's address book, press-and-hold 1–9 to speed dial. Favorites is now the front page, and starred chats can be pinned to the top of it |
| v2.1.x | Opening a chat no longer opens the keyboard; Save an unknown sender to contacts; the dialer lists nobody until you type; pinning moved to the row's long-press |
| v2.2.x | The contacts list is both address books — the phone's and BlueBubbles' — so nobody you message is a stranger to the dialer; the speed dials are listed at rest and can be cleared |
| v2.3.x | Names saved on the phone now name people everywhere — the conversation list, the thread header and notifications read the handset's contacts as well as the server's |
| v2.4.x | The New Message picker searches the phone's address book too, so you can start a conversation with somebody you just saved |
| v2.5.x | Videos download and play inside the thread, on a platform `VideoView` — LightOS has no video player to hand one off to |
| v2.6.x | Shake the phone to report a bug, and say what went wrong |
| v2.7.x | Reporting moves to the shared `light-common` library |
| v2.8.x | Notifications name who texted, and who reacted |
| v2.9.x | light-common 1.2.0, LightSync backup, R8 full mode |
| v2.10.x | Announce calls — text people which number you're calling from; no more notification for the chat you were just in |
| v2.11.x | On-screen alerts can be turned off; **Agents** — named AI chats with full markdown, plus QR-code agent setup |
| v2.12.x | Per-chat backgrounds with a stackable filter editor (dither, B&W, opacity, corner blur/fade, shade swatches) |
| v2.13.x | Fix: a failed agent reply left an id-0 row in the thread, and the next send crashed the list (light-reports#19) |
| v2.14.x | Draggable Fill crop in the background editor, photo-swap cache fix, slide the thread left to peek at message times |
| v2.15.x | Local nicknames: rename any conversation on-device, including the self-chat |
| v2.16.x | Fix: sending several photos could leave two thread rows under one message guid and crash the list (light-reports#21) |
| v2.17.x | **Newsletter** — named batches of chats and contacts that one message, photos and all, goes out to separately |
| v2.18.x | Fix: photos still uploading lost their bubble when a fetch landed, and never came back (light-reports#22) |
| v2.19.x | **Send video** — clips appear in the picker with their running time and stream straight off disk, so a recording never has to fit in memory |
| v2.20.x | **Audio clips** — voice memos play in-app with a draggable scrubber, and BrightChat is a share target for BrightRecorder |
| v2.21.x | **Whisper transcription** — open a voice memo, press WORDS, read what was said; any OpenAI-shaped server |
| v2.22.x | **Dictate a message** — tap, speak, tap; the words are appended to the draft and the recording is deleted on return |
| v2.23.x | The Speak key is always visible, in all three composers, as a word |
| v2.24.x | The screen stays on while a newsletter goes out |
| v2.25.x | One settings page that scrolls, with sections; QR-code input for the Whisper key |
| v2.26.x | The Speak key is a drawn microphone, shown only when transcription is configured |
| v2.27.x | Fix: the colour lift could lose a one-shot race to LightOS and never retry, leaving photos grayscale until an activity swap (light-reports#35) |
| v2.28.x | **Delete conversation** on the contact page — tap the header, two-tap confirm at the foot of the page (light-reports#36) |
| v2.29.x | LightNotebook's journal can ask who you talked to on a day (names and counts, never text); `main` is no longer a release trigger |
| v2.30.x | One box for a message, not two: the heads-up box stands aside when BrightControl is drawing banners for every app |
| v2.31.x | **GIFs** — a picker on KLIPY (what Discord moved to when Google shut Tenor down), saved GIFs kept as files on the phone, and GIFs that animate instead of showing their first frame |
| v2.32.x | GIF search waits until you stop typing (and the keyboard's Search key skips the wait); the grid draws a sharper rendition; the GIF key lines up with the + |
| v2.33.x | Long-press is star/unstar on every tab — a star is no longer a one-way trip; pinning moved to the swipe reveal on Favorites |
| v2.34.x | Fix: starting a message to a number saved under two contacts no longer crashes the list (light-reports#291) |
| v2.35.x | Fix: an old message no longer resurfaces as the row's preview when a late read receipt or delivery stamp arrives (light-reports#292) |
| v2.47.x (nightly) | **Beeper notifications**: alerts for WhatsApp, Signal and the rest, following your Beeper mute settings, with an ntfy wake-up while the phone sleeps. Beeper works with no Mac set up at all |
| v2.46.x (nightly) | Dial tab: **Voicemail** in the corner rings your carrier voicemail; the keypad folds away until you tap **Keypad** |
| v2.45.x (nightly) | **People** (Beeper on only): one row per person across iMessage and Beeper networks, a thread with All · iMessage · WhatsApp · Calls, "on WhatsApp" dividers, a "via" line choosing where the next message goes, call history (phone calls and bridge call notices), and Link / Unlink on the contact page. Beeper failures send a redacted log report by themselves |
| v2.44.x (nightly) | **Beeper (beta)**: WhatsApp, Signal, Telegram, Instagram and the rest beside iMessage, in the same list and thread. Sign in from Settings or the first-run screen. Nightly channel: a push is a prerelease, `[release]` makes it official |
| v2.43.x | Reply and Edit on their own row under the tapbacks — on one line they wrapped letter by letter |
| v2.42.x | Hold your own message → Edit (Private API, 15-minute window); "Edited" under edited turns; a late receipt cannot undo an edit |
| v2.41.x | Every "Couldn’t …" on screen also raises the SEND ERROR? chip by itself (`fail()` → `Trouble.record`) |
| v2.40.x | Bug reports name the screen they came from (every one said `home`); "Buzz on tap" is written to disk synchronously so a process kill cannot undo it (light-reports#476) |

## Licence

[MIT](LICENSE).

The Beeper login flow, the `/keys/claim` repair and the recovery-key verification are adapted
from [fenleon/chats](https://github.com/fenleon/chats) and
[Beeper4LightOS](https://github.com/ironfeet/Beeper4LightOS), both MIT, as is the ntfy
push pattern (from fenleon/chats). Matrix is
[Trixnity](https://gitlab.com/connect2x/trixnity) (Apache-2.0).

---

## Upstream README (craigeley/chat)

The following is Craig Eley's original README for `craigeley/chat`, kept as he wrote
it, for attribution and history. It describes the base app this fork builds on; some
details (package id, install instructions) are superseded by the sections above.

> # chat
>
> An **iMessage client** for the [Light Phone III](https://www.thelightphone.com/),
> inspired by and based on the apps created by [vandamd](https://github.com/vandamd).
> The app works by talking to an always-on, self-hosted
> [BlueBubbles Server](https://github.com/BlueBubblesApp/bluebubbles-server), reached
> privately over [Tailscale](https://tailscale.com/).
>
> I built this to replace OpenBubbles on my LPIII, which I found to be both a battery
> hog and very flaky in terms of displaying and ordering messages correctly.
>
> Open the app to a list of conversations (newest activity first); tap one to read the
> thread, or tap **New** to start one (searches your contacts by name/number/email).
> Tap **Messages** at the top for settings, **Refresh** to re-pull.

<!-- bright-footer:begin -->
---

## Bright\*

**It's not Light, it's Bright.**

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightNotebook](https://github.com/gi-os/BrightNotebook) · [BrightControl](https://github.com/gi-os/BrightControl) · [BrightWay](https://github.com/gi-os/BrightWay) · **BrightChat** (you are here) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->

## Support

These apps are free, open, and built on my own time. Sponsorship pays the bills that don't go away: build servers, test hardware, and the crash reporter that keeps them shipping. Donation or not my code is always free for the world to use.

[Sponsor on GitHub](https://github.com/sponsors/gi-os)
