# Changelog

All notable changes to HexDroid are documented here.

---

## [Unreleased]

### IRCv3 Compliance

- **away-notify fully implemented** — `AWAY` messages from other users now drive live status lines (`* nick is now away (message)` / `* nick is back`) in every channel where the nick is present. State transitions are tracked precisely: only the first away event prints a line; subsequent away-message updates (same nick, different message) are silently absorbed. Away state is seeded from WHOX flags (`H`/`G`) on channel join so the client has correct initial state before any `AWAY` messages arrive. State is cleaned up on `QUIT`, `NICK`, and `Disconnected`.

- **extended-join account display** — JOIN lines now include `[logged in as account]` when `extended-join` is active and the joining user is authenticated with services.

- **WHOX away flags parsed** — The `WHO %uhsnfar,42` query sent on join now reads the flags field (`H` = here, `G` = gone/away) from 354 replies and uses it to seed initial away state for all channel members.

- **draft/relaymsg receive handler added** — When `draft/relaymsg` is enabled (off by default), `RELAYMSG` commands from relay bots are now parsed and displayed as chat messages attributed to the relayed nick, including ACTION support and reply-tag forwarding.

- **draft/channel-rename race condition fixed** — The `ChannelRenamed` handler mutated `chanNickCase` and `chanNickStatus` inside a `StateFlow.update {}` lambda; since `update` retries on CAS collision, those mutations could execute twice and corrupt the maps. Fixed by moving the map mutations to a single `_state.value =` assignment.

- **CAP NEW / CAP DEL events now emitted** — `IrcEvent.CapNew` and `IrcEvent.CapDel` were defined and had ViewModel handlers, but `IrcAction` had no corresponding action types and `IrcSession` never fired them. Added `EmitCapNew` / `EmitCapDel` action types; the CAP NEW and CAP DEL handlers now emit them, and `IrcCore`'s dispatcher forwards them to the event flow. Server buffer now shows `*** Server added capabilities: …` and `*** Server removed capabilities: …`.

- **ChannelRenamed, MessageReaction, ChannelModeChanged handlers added** — Three event types were defined and emitted by `IrcCore` but had no ViewModel handlers. `ChannelRenamed` now migrates buffer keys, nicklists, `chanNickCase`, `chanNickStatus`, and the selected buffer pointer. `MessageReaction` prints `* nick reacted with 👍` / `* nick removed reaction 👍` in the target buffer. `ChannelModeChanged` is an explicit no-op (already handled by the `ChannelModeLine` path).

- **WhoxReply handler was a no-op** — The handler called `_state.update { st -> st }` (returned state unchanged). Replaced with proper away-state seeding from the WHOX `isAway` field.

### New Command

- **`/setname`** — Change your own realname via `SETNAME` (requires `setname` CAP). Prints a usage hint or a "cap not available" message if the server doesn't support it.

### Translations

- **140 strings added to all 13 locales** — The following screens were previously untranslated (showing English in all locales): network edit sections, TLS certificate fields, encoding descriptions, chat modes panel, channel ban/quiet/except/invex lists, nick action sheet (Whois, Ignore, DCC, Kick/Ban), ignore list descriptions, transfers screen sections and status strings, settings appearance/backup/battery options, About screen, and theme names. All 140 strings are now fully translated in Arabic, Chinese, Dutch, French, German, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, and Turkish.

- **Duplicate `network_edit_title` key removed** from base `strings.xml` (appeared on lines 154 and 219).

---

## [1.5.4] — 2026-02-22

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
