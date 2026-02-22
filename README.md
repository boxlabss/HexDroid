# HexDroid
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF.svg)](https://kotlinlang.org)
[![GitHub release](https://img.shields.io/github/v/release/boxlabss/HexDroid)](https://github.com/boxlabss/HexDroid/releases)
[![GitHub stars](https://img.shields.io/github/stars/boxlabss/HexDroid)](https://github.com/boxlabss/HexDroid/stargazers)
[![Build](https://github.com/boxlabss/HexDroid/actions/workflows/build.yml/badge.svg)](https://github.com/boxlabss/HexDroid/actions)
[![RB Status](https://shields.rbtlog.dev/simple/com.boxlabs.hexdroid)](https://shields.rbtlog.dev/com.boxlabs.hexdroid)

A fast, modern IRC client for Android.

[Google Play](https://play.google.com/store/apps/details?id=com.boxlabs.hexdroid) | [Direct Download](https://hexdroid.boxlabs.uk/releases/hexdroid-latest.apk) | [Documentation](https://hexdroid.boxlabs.uk/)

---

## About

HexDroid is a free and open source IRC client for Android devices. It provides a clean, modern interface while supporting the features users expect from a desktop client, including IRCv3 capabilities, SASL authentication, TLS encryption, Bouncer support, DCC file transfers and an array of commands.

**Requirements:** Android 8.0 (API 26) or higher

**License:** GPLv3

---

## Features

### Multi-Network Support

- Connect to multiple IRC networks simultaneously
- Save and manage network profiles
- Auto-connect on app startup (configurable per network)
- Auto-reconnect with exponential backoff
- Auto-join channels with optional keys
- Post-connect commands with configurable delay

### Bouncer support
- Supports ZNC/soju with playback support

### Security

- TLS/SSL connections (enabled by default, recommended)
- SASL authentication support:
  - PLAIN
  - SCRAM-SHA-256
  - EXTERNAL (client certificate authentication)
- Client certificate support (.pem, .crt + .key, .p12 formats)
- Server passwords

### IRCv3 Support

HexDroid implements modern IRCv3 specifications:

- `message-tags` and `server-time`
- `echo-message` and `labeled-response`
- `batch` for grouped messages
- `draft/chathistory` for history playback (where supported)
- `draft/event-playback`
- `account-notify` and `away-notify`
- `chghost` and `extended-join`
- `sasl` for secure authentication

### Character Encoding

- Automatic encoding detection
- Manual encoding selection per network
- Supported encodings include:
  - UTF-8 (default)
  - windows-1251 (Cyrillic)
  - KOI8-R
  - ISO-8859-1, ISO-8859-15
  - GB2312, Big5, Shift_JIS, EUC-JP, EUC-KR
and more

### DCC Transfers

- DCC SEND and DCC CHAT support
- Configurable incoming port range
- Transfer progress tracking
- Files saved to Downloads by default

### User Interface

- Material Design 3
- Light and dark themes
- Adjustable font size and family
- mIRC colour code rendering
- Clickable URLs
- Copy text from messages
- Nick completion
- Swipe gestures
- Command completion

### Notifications

- Highlight notifications (nick mentions)
- Private message alerts
- Custom highlight words
- Configurable sound and vibration
- Persistent notification for background connections

### Additional Features

- Optional chat logging with configurable retention
- Per-network ignore list
- Hide JOIN/PART/QUIT messages
- Lag indicator
- Channel list with search
- Channel op tools
- IRCop tools

---

## Installation

### Google Play

The recommended installation method:

[Get it on Google Play](https://play.google.com/store/apps/details?id=com.boxlabs.hexdroid)

### Direct APK Download

Download the latest APK directly:

[Download HexDroid Latest](https://hexdroid.boxlabs.uk/releases/hexdroid-latest.apk)

### Build from Source

```bash
git clone https://github.com/boxlabss/hexdroid.git
cd hexdroid
./gradlew assembleRelease
```

---

## Quick Start

1. Open the app and tap **Networks**
2. Tap **+** to create a new network profile
3. Enter the server hostname and port (typically 6697 for TLS)
4. Set your nickname
5. (Optional) Configure SASL authentication
6. Save and tap **Connect**

For detailed instructions, see the [Getting Started Guide](https://hexdroid.boxlabs.uk/getting-started.html).

---

## Commands

HexDroid supports standard IRC commands. Commands are case-insensitive.

### Common Commands

| Command | Description |
|---------|-------------|
| `/join #channel [key]` | Join a channel |
| `/part [#channel] [reason]` | Leave a channel |
| `/msg <target> <message>` | Send a private message |
| `/me <action>` | Send an action message |
| `/nick <newnick>` | Change your nickname |
| `/quit [reason]` | Disconnect from the server |
| `/whois <nick>` | Get information about a user |
| `/topic [#channel] [text]` | View or set channel topic |

### Channel Operator Commands

| Command | Description |
|---------|-------------|
| `/kick [#channel] <nick> [reason]` | Kick a user |
| `/ban [#channel] <nick>` | Ban a user |
| `/kickban [#channel] <nick> [reason]` | Ban and kick a user |
| `/op [#channel] <nick>` | Give operator status |
| `/deop [#channel] <nick>` | Remove operator status |
| `/voice [#channel] <nick>` | Give voice status |
| `/mode <target> <modes> [args]` | Set channel or user modes |

### Utility Commands

| Command | Description |
|---------|-------------|
| `/close [#channel or user]` | Part and close a channel, or close a query |
| `/ctcp <target> <command>` | Send a CTCP request |
| `/ctcpping <nick>` | Measure latency to a user |
| `/dns <host/ip>` | DNS lookup |
| `/ignore <user>` | Add user to ignore list |
| `/find <keyword>` | Search scrollback |
| `/sysinfo` | Share device information |
| `/raw <line>` | Send raw IRC command |

For a complete-ish command reference, see [Commands](https://hexdroid.boxlabs.uk/commands.html).

---

## Configuration

### Network Settings

Each network profile supports:

- Server hostname and port
- TLS toggle and certificate validation
- Nickname, username, and real name
- Server password
- SASL authentication (mechanism, credentials)
- Client certificate for EXTERNAL auth
- Auto-connect and auto-join settings
- Character encoding
- Post-connect commands

### Global Settings

- Theme (light/dark/system)
- Font family and size
- Notification preferences
- Highlight words
- Logging options
- DCC configuration

From v1.5.4 these can be backed up and restored from a json file
---

## Troubleshooting

### Connection Issues

- Verify the server hostname and port are correct
- Most TLS servers use port 6697; plaintext uses 6667
- Check that TLS is enabled/disabled as required by the server
- For SASL errors, verify your credentials and that the server supports your chosen mechanism

### Certificate Errors

If connecting to a server with a self-signed certificate, enable "Allow invalid certificates" in network settings. Note: this disables protection against man-in-the-middle attacks.

### Background Disconnections

Some Android devices aggressively restrict background apps. To maintain connections:

1. Disable battery optimisation for HexDroid
2. Ensure the persistent notification is visible
3. Consider locking the app in the recent apps list

See [dontkillmyapp.com](https://dontkillmyapp.com/) for device-specific instructions.

### Garbled Text

If text appears garbled, the server may use a non-UTF-8 encoding. Go to network settings and select the appropriate encoding.

For more help, see the [Troubleshooting Guide](https://hexdroid.boxlabs.uk/troubleshooting.html).

---

## Privacy

HexDroid does not collect analytics, advertising identifiers, or any user data. The app contains no ads or tracking.

Data is transmitted only to the IRC servers you choose to connect to. Local data (settings, logs, DCC files) is stored on your device and can be deleted by clearing app data or uninstalling.

See the full [Privacy Policy](https://hexdroid.boxlabs.uk/privacy-policy.html).

---

## Contributing

Contributions are welcome. Please open an issue to discuss proposed changes before submitting a pull request.

### Reporting Issues

When reporting bugs, please include:

- Device model and Android version
- HexDroid version
- Steps to reproduce the issue
- Relevant error messages or logs

---

## Support

- **Documentation:** [hexdroid.boxlabs.uk](https://hexdroid.boxlabs.uk/)
- **Email:** android@boxlabs.co.uk
- **IRC:** `#HexDroid` on `irc.afternet.org`

---

## License

HexDroid is free software licensed under the GNU General Public License v3.0.

See [LICENSE](LICENSE) for the full license text.

---

## Acknowledgements

HexDroid is built with:

- [Kotlin](https://kotlinlang.org/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Material Design 3](https://m3.material.io/)
