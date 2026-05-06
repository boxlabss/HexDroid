# Changelog

All notable changes to HexDroid are documented here.
---
## [1.6.2] - 2026-06-06

### Chathistory marker
- **New** Added a chathistory marker similar to the scrollback one. Shows the date of last messages.

### AWAY notify suppression
- **New "Hide away/back notifications" setting.** Added to Settings to suppress the inline `* user is away` / `* user is back` lines emitted by the away-notify IRCv3 capability.

### Coloured channel events
- **New setting.** Joins, parts, quits, kicks, and nick changes are now colour-coded by default, green for joins, orange for parts, brown for quits, red for kicks, cyan for nick changes. Helps visual scanning on busy channels.
-  Toggle in Settings "Coloured channel events" if you prefer plain output.

### Bouncer (soju + ZNC) multinetwork
- **Discover-and-clone import.** When connected through a soju or ZNC bouncer without a network chosen, the Networks screen now shows a "Bouncer networks" section listing every upstream the bouncer reports (name, host, connection-state).
Tap **Import** on any upstream and HexDroid creates a new local profile that inherits the bouncer host/port/TLS/SASL credentials and binds to that upstream via `bouncerNetworkName`
  - **soju** path: structured `BOUNCER NETWORK` push protocol (`soju.im/bouncer-networks`).
  - **ZNC** path: opportunistically scrapes the `*status ListNetworks` table reply. 
- **Refresh.** A refresh button on each section sends `BOUNCER LISTNETWORKS` (soju) or `PRIVMSG *status :ListNetworks` (ZNC)

### Sidebar
- **Toolbar** Added toggle/clear unread chats/search buttons

### Memory
- **`recentJoinAtMs` and `pingTimeoutTimestamps` weren't purged on disconnect.**  Both are now cleared in `cleanupNetworkMaps` so per-network state lifecycle is consistent.

### IRCv3 multiline (`draft/multiline`)
- **implemented for messages longer than 512 bytes or containing line breaks** when the server supports the multiline cap

### Notice routing
- **Service-bot welcome notices route to the channel they greet you in.** When you join a channel and the network's services bot (X3, ChanServ, etc) sends a welcome notice to your nick instead of the channel, the notice is now scanned for any channel name you have an open buffer for and routed there.

### Concurrency
- **Race on outbound writes fixed.** `writeLine` was called from at least four IO-pool coroutines (writer queue, PING loop, PONG handler, registration sequence).
Two concurrent `outputStream.write(...)` calls on the same `SSLOutputStream` could interleave their byte arrays, producing a malformed IRC line that some servers tolerate and others kill the connection on,
and corrupting the TLS record stream so the peer drops with `decryption_failed`. Most likely manifestation: rare unexplained disconnects under heavy outbound traffic. Writes are now serialised by a per-connection `Mutex`.
- **`ConcurrentModificationException` in network-callback handlers fixed.** `onAvailable` and `onLost` iterated over `desiredConnected` while the loop body called `connectNetwork`/`disconnectNetwork`, which mutate the same set via downstream coroutines.
On a rapid mobile/WiFi handover the iteration could collide with a queued mutation. Both callbacks now snapshot `desiredConnected.toList()` before iterating.

### Bouncer / playback
- **Cross-path duplicate-on-reconnect.** PMs received while disconnected from a ZNC bouncer were displaying twice with the same timestamp on reconnect because ZNC's automatic `*playback` dump and a subsequent CHATHISTORY LATEST request both replayed the line, and the `*playback` dump arrives without a `BATCH` wrapper so it wasn't classified as history at the time of first delivery. The msgid-based dedup in 1.6.1 didn't catch this because the two paths can carry different msgids. Now: the content fingerprint `(timeMs, from, text-hashCode)` is computed for *every* message with a sender (not just history-without-msgid), so a future replay of any retained line dedupes against it regardless of which path delivered the original.
- **SASL failure no longer logged twice per failure.** The 904/905/906 handler was emitting both an `EmitError` AND an `EmitAuthFailed`, so each numeric produced a "ERROR" line *and* an "AUTH" line.
- **SASL failure no longer logged again after MOTD.** Bouncers (notably soju when forwarding upstream-IRC SASL outcomes) deliver a second 906 for the same conceptual auth failure once the bouncer-side MOTD finishes. A new `saslAuthFailedEmitted` flag in the SASL state machine suppresses the relayed second numeric so you see one auth-fail line per session, not one for the bouncer + one for the upstream.

### WHOIS
- **Numerics 330, 338, 378, 379, 671 now formatted properly.** `/whois <nick>` was producing lines like `[330] Jordan Eck is logged in as` and `[671] Jordan is connected via SSL` because the formatter had no cases for these and they fell through to the bracketed-numeric default. Now formatted as `Jordan Eck is logged in as Jordan`, `Jordan is using a secure connection`, etc.

### Connection robustness
- **Auto-reconnect halts on authentication failure.** When the server rejects credentials via 464 (PASS) HexDroid now stops the reconnect loop until you take manual action.

### Other
- Added `/quote` (same as `/raw`) sends a verbatim IRC line to the server.

---
## [1.6.1] - 2026-04-19

### Bouncer support
- **Correct soju vs ZNC username syntax.** New "Bouncer type" dropdown on the network edit screen. soju uses `user/network@clientid`; ZNC uses `user@clientid/network`. Previously HexDroid only emitted the soju-style form, which silently misrouted ZNC per-client buffers.
- Separate **Network name** and **Client ID** fields (was a single field).
- `/znc <cmd>` and `/bouncerserv <cmd>` (alias `/bnc`) slash commands.
- `BouncerServ` replies routed to the server buffer.
- Spec-correct `BOUNCER NETWORK` parsing: handles the `*` deletion sentinel and reports unconfigured upstreams as a hint.
- **Subcommand hints** for `/ns`, `/cs`, `/ms`, `/hs`, `/bs`, `/as` (services) and `/znc`, `/bouncerserv`, `/bnc` (bouncer control). Type the parent command plus a space and a chip row of sub-verbs appears — narrowing as you type — so common commands like `ListNetworks`, `JumpNetwork`, `network status`, `IDENTIFY`, `GHOST` are one tap away. Verbs sourced from the authoritative docs (Atheme/Anope for services, `wiki.znc.in` for ZNC, `soju(1)` for BouncerServ).

### Bouncer bug fixes
- **Double playback on ZNC ≥1.7** — `znc.in/server-time-iso` is no longer requested when the server already supports `server-time`.
- **Stray `*playback` query window** — our own echoed `PLAY` commands are filtered so they don't open a query buffer.
- **Duplicate playback on reconnect** — `PLAY` and `CHATHISTORY LATEST` are skipped when a JOIN is part of buffer replay.
- **Oversized IRC lines** (>32 KiB) are now dropped with a one-shot notice instead of disconnecting. Limit raised from 8 KiB to 32 KiB.
- **Duplicate self-messages** from echo-message + chathistory overlap — msgid-based second-layer dedup.
- **SASL status messages shown twice** — 903–908 numerics were emitted both as a Status/Error event by the SASL state machine AND as raw ServerText by the generic numeric handler. Now suppressed in the generic path so each event appears exactly once.
- **`*status` / bouncer module NOTICEs opened query buffers** — ZNC replies to `/msg *status` (and the new `/znc` shorthand) with NOTICE, not PRIVMSG. The NOTICE handler didn't know about pseudo-user routing, so each reply created a `*status` query window. Routing now applies to both PRIVMSG and NOTICE.
- **Incomplete pseudo-user list** — the routing fixed above now matches any nick starting with `*` (ZNC's reserved module prefix) rather than a hardcoded list. `*playback`, `*clientbuffer`, `*log`, and any user-loaded ZNC module now route correctly to the server buffer.

### TLS
- **TOFU fingerprint pinning now enforced.** Learn-on-first-connect (when "Allow invalid certs" is on), verify-on-subsequent-connect, refuse mismatches with a clear warning. Fingerprints are normalised so hand-pasted values (uppercase, missing colons) still work.
- Shared `SSLContext` cache enables TLS session resumption on reconnect.

### Connection
- **Rejoin on kick** setting (off by default). Rejoin once after 1.5s; a second kick within 60 seconds is suppressed to avoid loops against +i/+k/+b modes.
- Autoconnect fan-out staggered by 500 ms to avoid bouncer rate limits.

### Other fixes
- Typing indicators no longer sent to DCC chat buffers (was producing `401` noise).
- Orphaned SASL/server passwords in `SecretStore` are cleaned up when `importBackup` removes their profiles.
- **Chat crash on channel merge** — fixed an `IllegalArgumentException` from `LazyColumn` that could fire when two buffers with case-variant names (e.g. `#Channel` and `#channel`) merged. The chat display-item cache was keyed on `(count, newest-id, oldest-id)`, which could stay stable across a sort that reordered interior messages, producing duplicate LazyColumn keys. Cache now keys on the message list by reference identity.
- **"Internal OpenSSL error or protocol error"** shown as a raw library message after Play Store updates — the mapping now walks the exception cause chain and matches the BoringSSL state-machine-confusion signature that fires when the previous process was killed without a TLS close_notify. Rendered as "TLS session interrupted — connection will retry", and the existing exponential backoff handles the rest.
- **`/ban nick`, `/kick nick`, `/kb nick` were silently broken** — the parser treated the first arg as the channel name and required `/ban #channel nick` to actually do anything. Fixed: nick comes first by default, optional channel can come either before or after, matching the existing `/op` / `/voice` convention.

### Channel-op improvements
- **Mask-aware bans** — `/ban`, `/kb`, `/mute` now accept a mask-type keyword: `nick` (default, `nick!*@*`), `user` (`*!ident@*`), `host` (`*!*@host`), `domain` (`*!*@*.example.com`), `account` (IRCv3 extban `$a:account`). Examples: `/ban spammer host`, `/kb spammer account flood`. The host/user/domain/account types issue a WHOIS in the background and apply the ban once the reply arrives; if the user is offline or services-unauthenticated, falls back to a nick mask with a status message.
- **Raw masks pass through** — `/ban *!*@evil.example` or `/unban $a:troublemaker` now work; previously the literal mask was wrapped in another `nick!*@*`.
- **`/mute` and `/unmute` (aliases `/quiet`, `/unquiet`)** — set/clear `+q` on ircds that support it, fall back to `+b` otherwise. Same mask-type syntax as `/ban`.

### Backup format
- Bumped to **v3**. Adds `bouncerKind`, `bouncerClientId`, `tlsTofuFingerprint` on profiles and `rejoinOnKick` in settings. `minCompatVersion` stays at 1 — older builds can still open v3 backups, they just won't understand the new fields.

### Breaking
- `effectiveAuthIdentity` no longer appends `/network` when the username already contains `@`. Users who hand-rolled `myname@phone` as a workaround should move the `@phone` portion to the new **Client ID** field.

---
## [1.6.0] - 2026-04-15
- **Improvements** several improvements to app performance. 
- **Bug fixes** DCC outgoing, welcome splash and more

## [1.5.9] - 2026-04-10
- **Improvements** several improvements to app performance
- **Bug fixes** SASL, CTCP and replies. incoming Secure DCC file transfer offers

## [1.5.8] - 2026-03-30
- **UI** improvements: reply gestures, scroll to unread marker
- **SDCC** add SSEND support for encrypted file transfers 
- **IRCv3** Improve replies and typing support
- **Other** Improvements for RAM usage and more

## [1.5.7] - 2026-03-21
- **ASCII Rendering** for bots that provide ascii/ansii art, render this in groups to fit the chat screen with no line spaces.
- **Nick colours** improve nick colours by splitting hues so each nick has a unique one. Also add option to select a custom colour in Settings
- **irc:// links** IRC links eg: irc:// or ircs:// (for SSL ports)
- **/LIST improved** added sorting by name/users to the channel list
- **Notifications** Tapping a highlighted message now scrolls to that message.
- **Bug fixes** /join #channel [key] would not parse if there was a [key] - improve IntroTour

## [1.5.6] - 2026-03-13
- **Inline Media** - Added inline image previews with a "Load preview" button and inline YouTube videos (using https://github.com/PierfrancescoSoffritti/android-youtube-player)
- Images are capped to 5MB. Two options added to Settings (Enable, Wifi only) - disabled by default
- **Labels for SASL certs** - Make clear you can either select .crt/.pem bundles, crt + key OR PKCS12 .p12/.pfx
- **Unread marker** - Bug fix: unread marker would display too often, now it only displays if you scroll up past the visible messages
- **Fixes** - Improved scrolling, ANSI colour support, alerts in the notification drawer, and input cursor

## [1.5.5] - 2026-03-07

### UI
- **Nicklist font scales with panel width** - Dragging the nicklist divider now also adjusts the nick font size so nicks stay on one line across the full drag range.
- **Better colour code support** - Added more control codes and improved the font style picker
- **DCC Chat notifications** - Incoming DCC CHAT offers show a notification that deep-links directly to the `DCCCHAT:peer` buffer, with an "Open Transfers" action button for accept/reject.
- **Transfers screen DCC chat cards** - Pending offers show a badge count, stand-out colouring, and an "Accept & Open" one-tap button.
- Added readline marker
- **Chromebook / hardware keyboard Enter sends the message** — pressing Enter on a physical keyboard now sends, matching desktop chat conventions. **Shift+Enter** inserts a newline for multi-line composition. Up/Down arrow still recall input history but only when the cursor is on the first/last line, so they don't fight cursor movement during multi-line edits.

### IRCv3
- **caps** - added several, including typing indicators (disabled by default)
- `away-notify` fully implemented - `AWAY` messages drive live status lines in every channel where the nick is present. Away state is seeded from WHOX flags on join and cleaned up on `QUIT`, `NICK`, and disconnect.
- `extended-join` account display - JOIN lines show `[logged in as account]` when the user is authenticated with services.
- WHOX away flags parsed from 354 replies to seed initial away state for all channel members.
- `draft/relaymsg` receive handler - relay bot messages are attributed to the relayed nick, including ACTION and reply-tag support.
- `draft/channel-rename` race condition fixed - map mutations no longer execute twice on CAS retry inside `StateFlow.update {}`.
- `CAP NEW` / `CAP DEL` events now emitted and displayed in the server buffer.
- `ChannelRenamed`, `MessageReaction`, `ChannelModeChanged` event handlers added.
- `WhoxReply` handler now correctly seeds away state from the WHOX `isAway` field.

### Translations
- All translations are complete. Arabic, Chinese, Dutch, French, German, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Turkish

### Commands
- **`/setname`** - Change your realname via `SETNAME` (requires `setname` CAP).
- **`/query` command** - `/query <nick>` opens a PM buffer and switches to it immediately. `/query <nick> <message>` also sends the message.
- **Command completion additions** - `query`, `ns`, `cs`, `as`, `hs`, `ms`, `bs` are now listed in the `/`-completion bar above the input field.
- **Nick `@` mention completion** - Type `@` followed by characters in a channel buffer to get a nick completion popup. Tapping inserts `nick: ` at the start of a line or `@nick ` mid-sentence.

### Bug Fixes
- **Auto scroll** - Tapping a buffer while it is still loading no longer disables auto-scroll.
- **Font size slider** - Tapping the slider caused the settings screen to jump to the top. Also improved font size range.
- Post-connect commands with leading/trailing whitespace are now trimmed before dispatch.
- Passive DCC offers with `port == 0` but no token no longer crash; a readable error is shown.

### Security
- **TOFU certificate pinning** - Replaced the all-or-nothing `allowInvalidCerts` toggle with Trust On First Use fingerprint pinning. First connect learns and persists the SHA-256 fingerprint; subsequent connects verify it. A fingerprint change aborts the connection with a clear warning.
- **Secret migration reliability** - SASL password migration flags are now always written regardless of errors, preventing indefinite retry on each launch.

---

## [1.5.4] - 2026-02-22

### App

- Fixed compilation warnings and deprecations
- Themed adaptive icon with monochrome layer for Android 13+
- Battery improvements: Compose now uses `collectAsStateWithLifecycle`

### UI

- Drag-and-drop network reordering in the Networks screen and sidebar
- New "Matrix" theme (green-on-black)
- MOTD on connect resizes to fit the server buffer
- Improved message input box

### Tools

- Improved channel op tools panel
- Added IRCop tools (displayed in the menu when the user has umode +o)
- Added backup/restore: export all network configurations and settings to a JSON file

### Connection

- Fixed SSL handshake failures on certain MediaTek, MIUI, and OneUI devices
- Improved bouncer support (ZNC, soju)

---

## [1.5.3] and earlier

See [GitHub Releases](https://github.com/boxlabss/HexDroid/releases) for earlier release notes.