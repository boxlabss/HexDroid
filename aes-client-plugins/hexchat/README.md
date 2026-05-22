# HexDroid AGM encryption HexChat plugin

A HexChat plugin that adds support for HexDroid's `+AGM` (AES-256-GCM) end-to-end
encryption scheme, for chatting securely with HexDroid users from a desktop IRC
client.

This plugin handles only the modern `+AGM` scheme. For legacy `+OK` Blowfish
encryption, install the **fishlim** plugin that ships with HexChat. Both plugins
can coexist, fishlim handles `+OK`, this plugin handles `+AGM`, and HexDroid
can speak either when paired with a matching key.

## Installation

1. Install the `cryptography` Python package:

   ```
   pip install cryptography
   ```

   (Or, on Linux distros, `python3-cryptography` from your package manager.)

2. Drop `hexdroid_agm.py` into your HexChat addons directory:

   - **Linux/macOS:** `~/.config/hexchat/addons/`
   - **Windows:** `%APPDATA%\HexChat\addons\`

3. Restart HexChat, or run `/PY LOAD hexdroid_agm.py`.

Verify it loaded by typing `/AGM` you should see usage text.

## Commands

All commands are case-insensitive. The current network and channel are inferred
from the active tab.

| Command | Description |
| --- | --- |
| `/AGM` | Show usage |
| `/AGM-INFO [target]` | Show the configured scheme + fingerprint for `target` (default: current channel) |
| `/AGM-SET <target> <base64>` | Install a key for `target`. Base64-encoded 32 bytes (44 chars, with or without padding) |
| `/AGM-GEN [target]` | Generate a fresh random key for `target`. Prints the base64 line for the other party to `/AGM-SET` |
| `/AGM-CLEAR [target]` | Remove the key for `target`. Reverts to cleartext |
| `/AGM-LIST` | Show every configured key across all networks |

Encryption is per `(network, target)`. The same channel name on Libera and
OFTC have separate keys.

## Key storage

Keys live in plaintext at `~/.config/hexchat/hexdroid_agm_keys.json` (or
`%APPDATA%\HexChat\hexdroid_agm_keys.json` on Windows). The file is created
mode-0600 on Unix but HexChat itself does not encrypt its config directory,
so treat it like an SSH private key, readable only by your account, no
shared storage, no backup-to-cloud.

If you want stronger storage, integrate with your OS keyring (gnome-keyring,
macOS Keychain, Windows Credential Manager) patches welcome.

## Pairing with HexDroid

On HexDroid (the mobile side):

1. Open the channel
2. menu > "Secure Chat"
3. "Generate key" > "Reveal key" tap **Share**, send the share-sheet payload
   to yourself via Signal/SMS/etc.

On HexChat (the desktop side), in the same channel:

1. Paste the base64 line into `/AGM-SET #channel <base64>`
2. Verify the safety number from `/AGM-INFO #channel` matches what HexDroid
   shows

That's it. Both sides now show a lock indicator on messages and the wire
traffic is `PRIVMSG #channel :+AGM <ciphertext>`.

## Wire format

The plugin implements the format documented in `docs/agm-wire-format.md` in
the HexDroid repository. Summary:

```
+AGM <base64(version || nonce || ciphertext || tag)>

version    = 1 byte   (0x01)
nonce      = 12 bytes (random per message)
ciphertext = N bytes  (AES-256-GCM)
tag        = 16 bytes (GCM auth tag)

AAD = lowercase(target).encode('utf-8')
```

The AAD-binds-target rule is what prevents replay across channels, a
ciphertext intended for `#secret` will not decrypt under any key in `#public`.

## Limitations

- **No FiSH `+OK` support.** Install fishlim if you need that.
- **No DH key exchange.** Keys are pre-shared; share them via a separate
  authenticated channel (in person, Signal, anything you trust).
- **No forward secrecy.** Compromising the key reveals all past and future
  messages encrypted under it. Rotate keys periodically and on suspicion.
- **CTCP ACTION is encrypted** (the `/me`-style messages). The CTCP framing
  itself stays cleartext so non-`+AGM` peers still see "`* nick`" but the
  text is `+AGM ..`. Matches what HexDroid does.
- **No multiline.** Long messages are split at the IRC line-length limit and
  each chunk is its own GCM payload with its own nonce.

## Threat-model caveats

- Identifying that two people are *talking* (traffic analysis) is trivial for
  any party with packet capture access. AGM hides content, not metadata.
- The plugin runs in HexChat's Python sandbox, any plugin can read any other
  plugin's globals. If you load untrusted plugins, your AGM keys are
  trivially exfiltratable.
- A compromised IRC server can still inject messages. The AGM tag means
  those injections decrypt to garbage and you see a "decrypt failed" line
  rather than fake content from your friend.

## Bug reports

Open an issue at the HexDroid repository.
