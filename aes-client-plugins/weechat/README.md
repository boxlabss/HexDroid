# HexDroid AGM encryption WeeChat script

A WeeChat script that adds support for HexDroid's `+AGM` (AES-256-GCM)
end-to-end encryption scheme, for chatting securely with HexDroid users from a
terminal IRC client. It is wire-compatible with HexDroid and with the HexChat
`hexdroid_agm.py` plugin and the irssi `hexdroid_agm.pl` script, the same keys
and safety numbers work across all of them.

This script handles only the modern `+AGM` scheme. For legacy `+OK` Blowfish
encryption, use a dedicated FiSH script. Both can coexist; FiSH handles `+OK`,
this script handles `+AGM`.

## Installation

1. Install the `cryptography` Python package into the interpreter WeeChat uses:

   ```
   pip install cryptography
   ```

   (Or, on Linux distros, `python3-cryptography` from your package manager.)
   WeeChat must have been built with Python support. check with
   `/script list` or look for `python` in `/help`.

2. Drop `hexdroid_agm.py` into your WeeChat python scripts directory:

   ```
   ~/.local/share/weechat/python/      (WeeChat >= 3.2)
   ~/.weechat/python/                  (older WeeChat)
   ```

   To load it automatically on every start, symlink it into `autoload`:

   ```
   ln -s ../hexdroid_agm.py ~/.local/share/weechat/python/autoload/
   ```

3. Load it now:

   ```
   /script load hexdroid_agm.py
   ```

You should see a `[+AGM] hexdroid_agm <version> loaded.` line. Type `/help agm`
for usage.

## Commands

All subcommands are case-insensitive. The current network (server) and
channel/query are taken from the active buffer when `target` is omitted.

| Command | Description |
| --- | --- |
| `/agm` | Show usage |
| `/agm info [target]` | Show the safety number (fingerprint) for `target` (default: current buffer) |
| `/agm set <target> <base64>` | Install a key for `target`. Base64-encoded 32 bytes (with or without padding) |
| `/agm gen [target]` | Generate a fresh random key for `target`. Prints the base64 line to share with the other party |
| `/agm clear [target]` | Remove the key for `target`. Reverts to cleartext |
| `/agm list` | Show every configured key across all networks |

Encryption is per `(network, target)`, keyed by WeeChat's server name. The same
channel name on two networks has separate keys.

A `[+AGM]` indicator is shown in the status bar when the current buffer has a
key configured. To make it visible, add the `agm` bar item to a bar, e.g.:

```
/set weechat.bar.status.items "[time],[buffer_count],...,agm"
```

(or just append `,agm` to your existing `weechat.bar.status.items`).

## Key storage

Keys live at `<weechat_data_dir>/hexdroid_agm_keys.json`, written mode-0600
(readable only by your account). WeeChat does not encrypt its config directory,
so treat this file like an SSH private key — no shared storage, no cloud backup.

## Pairing with HexDroid

On HexDroid (mobile):

1. Open the channel or conversation.
2. Menu > **Secure Chat**.
3. **Generate key** > **Reveal key** > tap **Share** and send the base64
   payload to yourself over something you trust (Signal, etc.).

In WeeChat, with that channel/query as the active buffer:

1. `/agm set #channel <base64>`
2. Run `/agm info #channel` and check the safety number matches what HexDroid
   shows.

Both sides now show a `[+AGM]` marker on messages and the wire traffic is
`PRIVMSG #channel :+AGM <ciphertext>`.

## How it hooks into WeeChat

- **Outbound:** the `irc_out1_privmsg` modifier (which fires before WeeChat
  splits the line) encrypts the message. WeeChat has already echoed your
  plaintext locally, so you see what you typed while ciphertext goes on the
  wire. On any encryption error the script **fails closed** and drops the
  message rather than sending cleartext, and prints a warning.
- **Inbound:** the `irc_in2_privmsg` modifier decrypts `+AGM` lines and rewrites
  just the text portion of the message (preserving IRCv3 tags), so WeeChat's
  native pipeline runs on the plaintext. highlights, logging, notifications and
  timestamps all behave exactly as for an unencrypted message.

## Wire format

Implements the format in `docs/agm-wire-format.md`. Summary:

```
+AGM <base64-nopad(version || nonce || ciphertext || tag)>

version    = 1 byte   (0x01)
nonce      = 12 bytes (random per message)
ciphertext = N bytes  (AES-256-GCM)
tag        = 16 bytes (GCM auth tag)
```

The AAD (which the GCM tag is bound to) is the **canonical conversation id**,
lowercased UTF-8:

- **channel:** the channel name
- **query:** the two participants' nicks, lowercased, sorted, joined with a NUL

so a ciphertext from one conversation cannot be replayed into another, and a
query decrypts identically regardless of who sent it. A bounded per-conversation
nonce cache additionally drops replays back into the *same* conversation.

## Limitations

- **No FiSH `+OK` support.** Use a separate FiSH script for that.
- **No DH key exchange.** Keys are pre-shared; exchange them over a separate
  authenticated channel you trust.
- **No forward secrecy.** Compromising a key reveals all past and future
  messages under it. Rotate periodically and on suspicion.
- **CTCP ACTION is encrypted** (`/me`). The CTCP framing stays cleartext (peers
  without the key still see `* nick`), but the text is `+AGM ..`.
- **Multiline:** long messages are split into multiple `+AGM` lines, each with
  its own nonce, so they survive the IRC line-length limit. NOTICEs are not
  encrypted (parity with HexDroid/HexChat/irssi).
- **ASCII nicks assumed** for the query AAD ordering (true for IRC in practice).

## Threat-model caveats

- AGM hides content, not metadata. That two people are talking is trivially
  visible to anyone with packet capture.
- A compromised IRC server can inject messages, but the GCM tag means they
  decrypt to nothing — you see a "decrypt failed" line rather than forged
  content from your friend.
- Any other WeeChat script you load runs in the same interpreter and can read
  this script's keys. Don't load untrusted scripts alongside it.

## Bug reports

Open an issue at the HexDroid repository.
