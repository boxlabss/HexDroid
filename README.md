# HexDroid
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF.svg)](https://kotlinlang.org)
[![GitHub release](https://img.shields.io/github/v/release/boxlabss/HexDroid)](https://github.com/boxlabss/HexDroid/releases)
[![GitHub stars](https://img.shields.io/github/stars/boxlabss/HexDroid)](https://github.com/boxlabss/HexDroid/stargazers)
[![Build](https://github.com/boxlabss/HexDroid/actions/workflows/build.yml/badge.svg)](https://github.com/boxlabss/HexDroid/actions)
<<<<<<< HEAD
[![RB Status](https://shields.rbtlog.dev/simple/com.boxlabs.hexdroid)](https://shields.rbtlog.dev/com.boxlabs.hexdroid)
=======
>>>>>>> 93f897c (much needed bug fixes)

A fast, modern IRC client for Android.

[Google Play](https://play.google.com/store/apps/details?id=com.boxlabs.hexdroid) · [Direct Download](https://hexdroid.boxlabs.uk/releases/hexdroid-latest.apk) · [Documentation](https://hexdroid.boxlabs.uk/)

---

## About

HexDroid is a free and open source IRC client for Android, built in Kotlin with Jetpack Compose. It aims for feature parity with desktop clients: full IRCv3 capability negotiation, SASL authentication (PLAIN, SCRAM-SHA-256, EXTERNAL), TOFU TLS certificate pinning, bouncer support (ZNC and soju), DCC file transfers and chat, a complete channel operator toolkit, and IRCop tools.

**Requirements:** Android 8.0 (API 26) or higher  
**License:** GNU General Public License v3.0

---

## Features

### Multi-Network

Connect to multiple IRC networks simultaneously. Each network has its own profile with independent settings for nickname, auto-connect, auto-join channels, post-connect commands (with configurable pre-join delay), character encoding, SASL credentials, and TLS certificate. Networks can be reordered by drag-and-drop and marked as favourites.

### Security

- TLS/SSL on by default (port 6697); plaintext requires an explicit opt-in dialog
- **TOFU certificate pinning** — the server's SHA-256 fingerprint is stored on first connect and verified on every subsequent connection; a prominent warning names both fingerprints if it ever changes
- SASL: PLAIN, SCRAM-SHA-256, EXTERNAL (client certificate)
- Client certificate import: PEM (combined cert+key), CRT+KEY, or PKCS#12 formats
- SASL passwords and TLS private keys stored in Android Keystore via `EncryptedSharedPreferences`
- Server passwords supported

### IRCv3

HexDroid negotiates a comprehensive set of capabilities. All are enabled by default unless noted.

**Core message infrastructure**

| Cap | Notes |
|---|---|
| `message-tags` | Base for all tag-based features |
| `server-time` | Timestamps on every message, including history replay |
| `echo-message` + `labeled-response` | Outbound message confirmation; deduplication via msgid |
| `batch` | chathistory, event-playback, and labeled-response grouping |
| `message-ids` | Unique msgid per message; prevents duplicates during chathistory overlap |
| `standard-replies` | Structured FAIL/WARN/NOTE from Ergo, soju, InspIRCd |
| `utf8only` | Signals UTF-8 intent to server |

**History & read state**

| Cap | Notes |
|---|---|
| `chathistory` / `draft/chathistory` | Replay on join; BEFORE paging on scroll-to-top; LATEST for unread catch-up |
| `draft/event-playback` | JOIN/PART/MODE events included in history batches |
| `draft/read-marker` / `soju.im/read` | Read pointer sync; drives the unread separator line |

**Membership & presence**

| Cap | Notes |
|---|---|
| `away-notify` | `* nick is now away (message)` / `* nick is back` in every shared channel; initial away state seeded from WHOX flags on join |
| `account-notify` | Services login/logout notifications |
| `extended-join` | JOIN line shows `[logged in as account]` for authenticated users |
| `chghost` | Live ident/hostname changes reflected in nicklist |
| `setname` | Receive realname changes; send your own with `/setname` |
| `multi-prefix` | Full mode prefix stack in NAMES (e.g. `@+nick`) |
| `invite-notify` | Notifies all channel members of `/invite` |
| `monitor` / `draft/extended-monitor` | Nick watch list; MONONLINE/MONOFFLINE notifications |
| WHOX (005 ISUPPORT) | `WHO %uhsnfar` on join seeds ident, host, account, and initial away state |

**Messaging**

| Cap | Notes |
|---|---|
| `account-tag` | Sender account exposed on every PRIVMSG/NOTICE |
| `draft/typing` | Composing indicators; sending opt-in (off by default), receiving opt-out |
| `draft/message-reactions` | Emoji reactions via TAGMSG `+draft/react`; displayed as status lines |
| `+draft/reply` | Reply-to msgid threading; forwarded on PRIVMSG and RELAYMSG |
| `draft/relaymsg` | Relay bot messages attributed to the relayed nick (off by default) |

**Channel management**

| Cap | Notes |
|---|---|
| `draft/channel-rename` | Buffer keys, nicklists, and selected buffer all updated on server-issued RENAME |

**Bouncer / vendor**

| Cap | Notes |
|---|---|
| `soju.im/bouncer-networks` + `soju.im/bouncer-networks-notify` | Multi-upstream support via soju |
| `soju.im/read` | soju read-marker protocol (parallel to `draft/read-marker`) |
| `soju.im/no-implicit-names` / `draft/no-implicit-names` | Suppress auto-NAMES on JOIN (avoids flood on reconnect) |
| `znc.in/server-time-iso` | Legacy ZNC < 1.7 timestamps |
| `znc.in/playback` | ZNC *playback module for missed-message replay |

### Character Encoding

Automatic detection starting from UTF-8, with fallback to windows-1251, KOI8-R, ISO-8859-1/15, GB2312, Big5, Shift_JIS, EUC-JP, EUC-KR, and more. Manual override per network for legacy servers.

### DCC

- DCC SEND and DCC CHAT
- Active, passive, and auto (try passive, fall back to active) modes
- Configurable incoming port range
- Rich transfer progress cards with one-tap accept from notification
- Incoming DCC CHAT offers create a buffer immediately and deep-link from notification
- Configurable download folder

### User Interface

- Material Design 3 with light, dark, and Matrix (green-on-black) themes
- Adjustable font family (Open Sans, Inter, Monospace, custom TTF/OTF) and size, separately for UI and chat
- mIRC colour rendering with 99-colour picker and gradient picker
- Nick `@` autocomplete and `/command` completion with inline hint chips
- Channel op panel: topic, key, user limit; ban/quiet/except/invex list management
- IRCop tools panel (visible when umode +o): K/G/D/Z-line, Shun, Kill, SAJoin/SAPart, WALLOPS/GLOBOPS/LOCOPS, MOTD, Links, Uptime
- Per-network ignore list (case-insensitive; covers chat, notices, and DCC offers)
- Channel list with live search
- Typing indicators displayed per-buffer
- Lag indicator, swipe gestures, compact mode
- Intro tour for new users
- Backup/restore: network profiles and settings exported as JSON

### Localisation

Full UI translation for 13 languages — Arabic, Chinese (Simplified), Dutch, French, German, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Turkish — switchable inside the app.

---

## Installation

**Google Play (recommended)**

[Get it on Google Play](https://play.google.com/store/apps/details?id=com.boxlabs.hexdroid)

**Direct APK**

[Download HexDroid Latest](https://hexdroid.boxlabs.uk/releases/hexdroid-latest.apk)

**Build from source**

```bash
git clone https://github.com/boxlabss/hexdroid.git
cd hexdroid
./gradlew assembleRelease
```

---

## Quick Start

1. Open the app — the intro tour walks through the main screens
2. Tap **Networks → +** and enter a server hostname and port (6697 for TLS)
3. Set your nickname; optionally configure SASL credentials
4. Save and tap **Connect**
5. Use `/join #channel` or tap **Channel list** to browse

---

## Commands

Commands are case-insensitive. Most accept the current channel as the default target where one is not specified.

### Messaging

| Command | Description |
|---------|-------------|
| `/join #channel [key]` | Join a channel |
| `/part [#channel] [reason]` | Leave a channel |
| `/cycle [#channel]` | Part and immediately rejoin |
| `/close [target]` | Part and close a buffer |
| `/msg <target> <text>` | Send a private message |
| `/notice <target> <text>` | Send a NOTICE |
| `/me <action>` | Send a CTCP ACTION |
| `/amsg <text>` | Message all open channels |
| `/ame <action>` | Action to all open channels |
| `/away [message]` | Set away; no argument clears it |
| `/setname <realname>` | Change your realname (requires `setname` CAP) |
| `/quit [reason]` | Disconnect from the network |

### Channel Operator

| Command | Description |
|---------|-------------|
| `/topic [text]` | View or set the channel topic |
| `/mode [target] <modes>` | Set channel or user modes |
| `/kick [#channel] <nick> [reason]` | Kick a user |
| `/ban [#channel] <nick>` | Ban a user (+b) |
| `/unban [#channel] <nick>` | Unban a user |
| `/kickban [#channel] <nick> [reason]` | Kick and ban |
| `/op <nick>` | Grant operator (+o) |
| `/deop <nick>` | Remove operator (-o) |
| `/voice <nick>` | Grant voice (+v) |
| `/devoice <nick>` | Remove voice (-v) |
| `/invite <nick> [#channel]` | Invite a user |
| `/banlist` | Show the ban list (+b) |
| `/quietlist` | Show the quiet list (+q) |
| `/exceptlist` | Show the ban exception list (+e) |
| `/invexlist` | Show the invite exemption list (+I) |

### User / Server

| Command | Description |
|---------|-------------|
| `/nick <newnick>` | Change nickname |
| `/whois <nick>` | User info, channels, idle time |
| `/who <target>` | Extended WHO query |
| `/ignore <nick>` | Add to ignore list |
| `/unignore <nick>` | Remove from ignore list |
| `/monitor +nick[,nick]` | Add nicks to watch list |
| `/monitor -nick[,nick]` | Remove nicks from watch list |
| `/monitor C` | Clear the watch list |
| `/monitor L` | List watched nicks |
| `/monitor S` | Request status of all watched nicks |
| `/markread [target]` | Mark a buffer as read |
| `/oper <user> <password>` | Authenticate as IRC operator |

### Utility

| Command | Description |
|---------|-------------|
| `/ctcp <target> <command>` | Send a CTCP request |
| `/ctcpping <nick>` | CTCP PING round-trip time |
| `/dns <host/ip>` | DNS lookup |
| `/find <keyword> [limit]` | Search the current buffer's scrollback |
| `/grep <keyword>` | Alias for `/find` |
| `/names [#channel]` | List users in a channel |
| `/list` | Open the channel list |
| `/motd [server]` | Fetch the server MOTD |
| `/admin [server]` | Server administrator info |
| `/info [server]` | Server software info |
| `/version [nick]` | CTCP VERSION query |
| `/time` | Request server time |
| `/sysinfo` | Share device/platform info in chat |
| `/raw <line>` | Send a raw IRC line |

### DCC

| Command | Description |
|---------|-------------|
| `/dcc send <nick>` | Offer a file to a user |
| `/dcc chat <nick>` | Open a DCC CHAT session |
| `/dcc accept <nick>` | Accept a pending offer |
| `/dcc reject <nick>` | Reject a pending offer |

---

## Configuration

### Network Profile

Each network profile stores: hostname · port · TLS on/off · TOFU fingerprint (auto-managed) · allow invalid certs · nickname · alternate nick · username (ident) · realname · server password · SASL (PLAIN/SCRAM-SHA-256/EXTERNAL + credentials) · client certificate (PEM, CRT+KEY) · bouncer toggle + network ID · auto-connect on startup · auto-reconnect · autojoin channels (with per-channel keys) · post-connect command delay · service auth command · arbitrary post-connect commands · character encoding.

The **Advanced IRCv3 caps** section exposes individual capability toggles. **Bouncer caps** (soju/ZNC-specific) appear only when the bouncer toggle is enabled.

### App Settings

| Section | Options |
|---------|---------|
| **Appearance** | Theme (light/dark/matrix/system), UI font, chat font, font size, timestamp format, compact mode, topic bar, nicklist defaults |
| **Highlights** | Highlight on own nick, custom keyword list |
| **Notifications** | Per-type enable/disable, sound, vibration intensity |
| **Privacy** | Send typing indicators (off by default), receive typing indicators |
| **IRC** | Hide JOIN/PART/QUIT spam, hide MOTD on connect, reconnect interval, keep-alive |
| **History** | Max scrollback per buffer, chathistory limit |
| **Logging** | Enable, storage location, retention period |
| **File Transfers** | Enable DCC, transfer mode (auto/active/passive), incoming port range, download folder |
| **Backup & Restore** | Export all profiles and settings as JSON; import from a JSON file |

---

## Troubleshooting

**Connection fails immediately**  
Verify hostname, port, and TLS setting. Most servers use port 6697 with TLS. Port 6667 requires explicitly allowing plaintext in the network dialog.

**Certificate warning on reconnect**  
HexDroid pins the server's TLS fingerprint on first connect (TOFU). If the server rotated its certificate, enable *Allow invalid certificates* in the network profile, reconnect once to learn the new fingerprint, then disable it again.

**SASL authentication fails**  
Verify credentials and that the server supports your chosen mechanism. SCRAM-SHA-256 is preferred. For EXTERNAL, a valid client certificate must be imported under TLS Client Certificate in the network profile.

**Garbled text / wrong characters**  
The server uses a non-UTF-8 encoding. Open the network profile → Advanced → Character Encoding and select the correct charset (e.g. windows-1251 for Russian/Cyrillic networks).

**Disconnects when the screen is off**  
Disable battery optimisation for HexDroid: Settings → Apps → HexDroid → Battery → Unrestricted. Keep the persistent notification visible. See [dontkillmyapp.com](https://dontkillmyapp.com/) for manufacturer-specific steps (OnePlus, Xiaomi, Huawei, Samsung).

**Chat history not loading**  
The server must advertise `chathistory` or `draft/chathistory`. Ergo 2.11+ and soju 0.7+ both support it. Scroll to the very top of a buffer to trigger a page load.

**Bouncer shows wrong channel list or no messages**  
Enable the bouncer toggle in the network profile. This activates `BOUNCER BIND` (soju), suppresses autojoin, and requests playback of messages received while offline.

---

## Privacy

HexDroid contains no ads, analytics, crash reporters, or third-party tracking SDKs. The app communicates only with the IRC servers you configure. All data is stored locally on-device and deleted with the app.

Typing indicators are **disabled by default**. Enable *Send typing indicators* in Settings → Privacy only if you are comfortable with others knowing when you are composing.

Full [Privacy Policy](https://hexdroid.boxlabs.uk/privacy-policy.html).

---

## Contributing

Open an issue before submitting a pull request for non-trivial changes. Bug reports should include: device model, Android version, HexDroid version, steps to reproduce, and relevant logcat output.

---

## Support

- **Documentation:** [hexdroid.boxlabs.uk](https://hexdroid.boxlabs.uk/)
- **Email:** android@boxlabs.co.uk
- **IRC:** `#HexDroid` on `irc.afternet.org`

---

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

---

*Built with [Kotlin](https://kotlinlang.org/) · [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Material Design 3](https://m3.material.io/)*
