# Changelog

All notable changes to HexDroid are documented here.

---

## [1.5.5] - 2026-03-07

### UI
- **Nicklist font scales with panel width** - Dragging the nicklist divider now also adjusts the nick font size so nicks stay on one line across the full drag range.
- **Better colour code support** - Added more control codes and improved the font style picker
- **DCC Chat notifications** - Incoming DCC CHAT offers show a notification that deep-links directly to the `DCCCHAT:peer` buffer, with an "Open Transfers" action button for accept/reject.
- **Transfers screen DCC chat cards** - Pending offers show a badge count, stand-out colouring, and an "Accept & Open" one-tap button.

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
