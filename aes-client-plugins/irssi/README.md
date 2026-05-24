# HexDroid AGM encryption irssi script

An irssi script that adds support for HexDroid's `+AGM` (AES-256-GCM) end-to-end
encryption scheme, for chatting securely with HexDroid users from a terminal IRC
client. It is wire-compatible with HexDroid and the HexChat `hexdroid_agm.py`
plugin, the same keys and safety numbers work across all three.

This script handles only the modern `+AGM` scheme. For legacy `+OK` Blowfish
encryption, use a dedicated FiSH script. Both can coexist; FiSH handles `+OK`,
this script handles `+AGM`.

## Installation

1. Install **CryptX** (provides AES-GCM and the CSPRNG). `Digest::SHA`,
   `MIME::Base64` and `JSON::PP` are already part of core Perl.

   ```
   cpan Crypt::AuthEnc::GCM Crypt::PRNG
   ```

   Or from your distro:

   - **Debian/Ubuntu:** `sudo apt install libcryptx-perl`
   - **Fedora:** `sudo dnf install perl-CryptX`
   - **Arch:** `sudo pacman -S perl-cryptx`
   - **macOS (Homebrew Perl):** `cpan Crypt::AuthEnc::GCM Crypt::PRNG`

2. Drop `hexdroid_agm.pl` into your irssi scripts directory:

   ```
   ~/.irssi/scripts/
   ```

3. In irssi, load it:

   ```
   /script load hexdroid_agm.pl
   ```

   To load it automatically on every start, symlink it into `autorun`:

   ```
   mkdir -p ~/.irssi/scripts/autorun
   ln -s ../hexdroid_agm.pl ~/.irssi/scripts/autorun/
   ```

You should see a `[+AGM] hexdroid_agm <version> loaded.` line. Type `/agm` for
usage.

## Commands

Commands are case-insensitive. The current network and channel/query are
inferred from the active window when `target` is omitted.

| Command | Description |
| --- | --- |
| `/agm` | Show usage |
| `/agm-info [target]` | Show the safety number (fingerprint) for `target` (default: active window) |
| `/agm-set <target> <base64>` | Install a key for `target`. Base64-encoded 32 bytes (with or without padding) |
| `/agm-gen [target]` | Generate a fresh random key for `target`. Prints the base64 line to share with the other party |
| `/agm-clear [target]` | Remove the key for `target`. Reverts to cleartext |
| `/agm-list` | Show every configured key across all networks |

Encryption is per `(network, target)`, keyed by irssi's network tag. The same
channel name on two networks has separate keys.

## Key storage

Keys live at `~/.irssi/agm_keys.json`, written mode-0600 (readable only by your
account). irssi does not encrypt its config directory, so treat this file like
an SSH private key, no shared storage, no cloud backup.

## Pairing with HexDroid

On HexDroid (mobile):

1. Open the channel or conversation.
2. Menu &rarr; **Secure Chat**.
3. **Generate key** &rarr; **Reveal key** &rarr; tap **Share** and send the
   base64 payload to yourself over something you trust (Signal, etc.).

In irssi, with that channel/query as the active window:

1. `/agm-set #channel <base64>` (or just `/agm-set <base64>` won't work, the
   target is required, but `/agm-gen` and `/agm-info` default to the active
   window).
2. Run `/agm-info #channel` and check the safety number matches what HexDroid
   shows.

Both sides now show a `[+AGM]` marker on messages and the wire traffic is
`PRIVMSG #channel :+AGM <ciphertext>`.

## How it hooks into irssi

- **Outbound:** the `send text` signal (and the `me` / `action` commands) are
  intercepted *before* the line is sent. The script encrypts, sends the
  ciphertext with `send_raw`, prints the plaintext locally, and stops the
  original signal so no cleartext leaves your client.
- **Inbound:** the `message public`, `message private` and `message irc action`
  signals are caught with `signal_add_first`; a `+AGM` line is decrypted and
  passed on with `signal_continue`, so it displays and logs through irssi's
  normal path.

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
query decrypts identically regardless of who sent it.

## Limitations

- **No FiSH `+OK` support.** Use a separate FiSH script for that.
- **No DH key exchange.** Keys are pre-shared; exchange them over a separate
  authenticated channel you trust.
- **No forward secrecy.** Compromising a key reveals all past and future
  messages under it. Rotate periodically and on suspicion.
- **CTCP ACTION is encrypted** (`/me`). The CTCP framing stays cleartext (peers
  without the key still see `* nick`), but the text is `+AGM ..`.
- **No multiline / chunking.** A very long single message can exceed the IRC
  line length once encrypted and be truncated by the server. Keep encrypted
  lines under a few hundred characters.
- **ASCII nicks assumed** for the query AAD ordering (true for IRC in practice).

## Threat-model caveats

- AGM hides content, not metadata. That two people are talking is trivially
  visible to anyone with packet capture.
- A compromised IRC server can inject messages, but the GCM tag means they
  decrypt to nothing, you see a line that failed to decrypt rather than forged
  content.
- Any other irssi script you load runs in the same interpreter and can read this
  script's keys. Don't load untrusted scripts alongside it.

## Bug reports

Open an issue at the HexDroid repository.
