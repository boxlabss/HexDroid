# HexDroid
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF.svg)](https://kotlinlang.org)
[![GitHub release](https://img.shields.io/github/v/release/boxlabss/HexDroid)](https://github.com/boxlabss/HexDroid/releases)
[![GitHub stars](https://img.shields.io/github/stars/boxlabss/HexDroid)](https://github.com/boxlabss/HexDroid/stargazers)
[![Build](https://github.com/boxlabss/HexDroid/actions/workflows/build.yml/badge.svg)](https://github.com/boxlabss/HexDroid/actions)

A fast, modern IRC client for Android.

[Google Play](https://play.google.com/store/apps/details?id=com.boxlabs.hexdroid) · [Direct Download](https://hexdroid.boxlabs.uk/releases/hexdroid-latest.apk) · [Documentation](https://hexdroid.boxlabs.uk/)

---

## About

HexDroid is a free and open source IRC client for Android devices. It provides a clean, modern interface while supporting the features users expect from a desktop client, including IRCv3 capabilities, SASL authentication, TLS encryption, Bouncer support, DCC file transfers and an array of commands.

**Requirements:** Android 8.0 (API 26) or higher · **License:** GPLv3

---

## Features

- **Multi-network** — connect to multiple servers simultaneously, each with independent nick, SASL, TLS, autojoin, and encoding settings
- **IRCv3** — 40+ capabilities including `chathistory`, `away-notify`, `server-time`, `echo-message`, `draft/typing`, MONITOR, bouncer-specific caps, and more
- **Security** — TOFU certificate pinning, SASL (PLAIN / SCRAM-SHA-256 / EXTERNAL), client certificates, Android Keystore credential storage
- **Localisation** — Arabic, Chinese, Dutch, French, German, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Turkish

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
| `draft/typing - typing` | Composing indicators; sending opt-in (off by default), receiving opt-out |
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

---

## Installation

**Google Play (recommended)**

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="60">](https://play.google.com/store/apps/details?id=com.boxlabs.hexdroid)

**Direct APK:** [hexdroid-latest.apk](https://hexdroid.boxlabs.uk/releases/hexdroid-latest.apk)

**Build from source:**
```bash
git clone https://github.com/boxlabss/hexdroid.git
cd hexdroid
./gradlew assembleRelease
```

---

## Quick Start

1. Tap **Networks → +** and enter a server hostname and port (6697 for TLS)
2. Set your nickname; optionally configure SASL credentials
3. Save and tap **Connect**
4. Use `/join #channel` or tap **Channel list** to browse

Full documentation at [hexdroid.boxlabs.uk](https://hexdroid.boxlabs.uk/).

---

## Privacy

No ads, analytics, crash reporters, or third-party SDKs. The app communicates only with the IRC servers you configure. All data is stored locally and deleted with the app.

---

## Support

- **Docs:** [hexdroid.boxlabs.uk](https://hexdroid.boxlabs.uk/)
- **Email:** android@boxlabs.co.uk
- **IRC:** `#HexDroid` on `irc.afternet.org`

---

## Contributing

Open an issue before submitting a pull request for non-trivial changes. Bug reports should include device model, Android version, HexDroid version, steps to reproduce, and relevant logcat output.

---

*Built with [Kotlin](https://kotlinlang.org/) · [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Material Design 3](https://m3.material.io/)*