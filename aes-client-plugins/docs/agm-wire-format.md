# HexDroid `+AGM` wire format

Version 1. AES-256-GCM end-to-end encryption for IRC messages.

This document is the authoritative reference for the `+AGM` scheme. Any client
that wants to interoperate with HexDroid AGM-encrypted channels needs to
implement this exactly.

## Goal

Encrypted message content over IRC's PRIVMSG/NOTICE primitives, with:
Authentication (a tampered message is rejected, not silently corrupted)
Replay protection across channels (a ciphertext for `#a` won't decrypt in `#b`)
Per-message nonces (no IV reuse across messages with the same key)
Pre-shared key (no key exchange in v1; users share keys out of band)

This is **not** intended to compete with Signal or any other E2EE apps. It's a pragmatic upgrade
path from FiSH/Blowfish for IRC users who want better authenticated encryption
without changing the IRC protocol.

## Wire format

```
+AGM <base64(version || nonce || ciphertext || tag)>
```

Where:

| Field        | Size       | Description                                          |
| ------------ | ---------- | ---------------------------------------------------- |
| `version`    | 1 byte     | Format version. `0x01` for this spec.                |
| `nonce`      | 12 bytes   | Random per message, from a CSPRNG.                   |
| `ciphertext` | N bytes    | AES-256-GCM of the UTF-8-encoded plaintext.          |
| `tag`        | 16 bytes   | GCM authentication tag.                              |

The base64 alphabet is the standard RFC 4648 alphabet. Padding (`=`) is
optional, both padded and unpadded encodings must be accepted on receive.
Senders MAY emit either; the reference implementations emit unpadded.

A space separates the `+AGM` literal from the base64 blob. Receivers MUST
require exactly one space and MUST reject any other separator.

## Cipher parameters

**Algorithm:** AES-256-GCM (`AES/GCM/NoPadding` in Java/Kotlin terms)
**Key length:** 32 bytes (256 bits)
**Nonce length:** 12 bytes (96 bits, GCM standard)
**Tag length:** 16 bytes (128 bits)

## Additional Authenticated Data (AAD)

The AAD binds a ciphertext to its conversation, so a ciphertext for one
conversation fails to authenticate if replayed into another. The AAD is the
lowercase UTF-8 encoding of a **canonical conversation identifier**, which both
endpoints MUST be able to compute identically regardless of message direction.

### Channels

For a channel the identifier is simply the channel name, lowercased:

```python
aad = channel.lower().encode('utf-8')   # e.g. b'#secret'
```

The channel name is the same string for every participant, so no further
canonicalization is needed.

### Queries (private messages)

A query has no single shared name: the sender addresses the recipient's nick,
while the receiver sees the sender's nick. Binding the AAD to whichever nick a
given side happens to see would make the sender's AAD and the receiver's AAD
differ, and **every query would fail to decrypt**. Instead, bind to the
*unordered pair* of the two nicks:

```python
a, b = sorted([my_nick.lower(), peer_nick.lower()])
aad = (a + "\x00" + b).encode('utf-8')   # e.g. b'alice\x00bob'
```

where `peer_nick` is the recipient when encrypting and the sender when
decrypting. Because the pair is sorted, both endpoints derive the identical
byte string `alice\x00bob` no matter who is sending. A `NUL` (`0x00`) separates
the two nicks; `NUL` cannot appear in an IRC nick, so the encoding is
unambiguous.

Nicks are lowercased with a locale-independent (Unicode-default / ASCII)
casefold and sorted by their UTF-8 byte order. IRC nicks are ASCII in practice,
so this ordering is stable across platforms.

This still binds the ciphertext to the conversation — a ciphertext from the
`{alice, bob}` conversation will not authenticate under the `{alice, carol}`
conversation — while remaining symmetric, so the message actually decrypts.

## Key material

Keys are 32 bytes of cryptographically random data. Implementations MUST NOT
derive keys from user passphrases without an explicit, salted KDF. the v1
scheme assumes high-entropy random key material from the start.

Key distribution is out of scope for this spec. The reference implementations
support:
Manual copy/paste (base64-encoded key)
Share-sheet handoff (e.g. via Signal, SMS)

## Safety numbers (fingerprints)

For out-of-band verification users compute a fingerprint:

```
sha256(scheme_byte || raw_key)[:5]
```

`scheme_byte` is `0x00` for AGM. The 40-bit truncation is base32-encoded with
the Crockford alphabet (no `0/1/I/O`) into 8 characters, with a hyphen
between the first and second halves: `K4XR-T9BS`.

Both endpoints display this fingerprint. Users compare them out of band (over
Signal, in person, etc.) to detect MITM at setup time.

## Multi-line and chunking

A single AGM payload corresponds to one IRC line. Messages longer than the
IRC line-length limit MUST be split before encryption, with each chunk encoded
as its own `+AGM` line with its own nonce. Receivers reassemble at the
plaintext layer if they want to; the protocol does not specify a multi-line
batch mechanism.

The IRC line-length budget after base64 overhead is approximately:

```
plaintext_max ≈ (max_payload_bytes − 5 − 4) × 3/4 − 13
              ≈ 354 bytes (UTF-8) for the typical 400-byte PRIVMSG budget
```

(5 bytes for `+AGM `, ~4 bytes for base64 rounding, 3/4 for base64 inflation,
13 for version+nonce header.)

## CTCP framing

CTCP messages (the `\x01CMD args\x01` envelope) are encrypted as follows:

- The CTCP framing bytes (`\x01`, the command name, the space, the trailing
  `\x01`) stay **cleartext**.
- The argument string after the command is encrypted as a `+AGM` payload.

So `/me waves` arriving at the wire as `PRIVMSG #foo :\x01ACTION waves\x01`
becomes `PRIVMSG #foo :\x01ACTION +AGM <base64>\x01`.

This is so non-AGM clients still see a well-formed CTCP and can render it as
"`* nick`" with garbled-but-readable text rather than producing a malformed
CTCP error. The CTCP command name remains a fingerprint to attackers (you can
see "this person sent an ACTION") but the content is hidden.

CTCP queries with no arguments (`\x01VERSION\x01`) pass through untouched
since there is no content to protect.

## Failure handling

| Failure mode                       | Receiver behaviour                                  |
| ---------------------------------- | --------------------------------------------------- |
| Wrong key (tag mismatch)           | Display the raw `+AGM ...` line with a tamper hint  |
| Wrong scheme version               | Same as above                                       |
| Malformed base64                   | Same as above                                       |
| Truncated payload                  | Same as above                                       |
| Cross-conversation replay (AAD)    | Same as above (tag fails)                           |
| Duplicate nonce in a conversation  | Drop as a replay (see nonce cache below)            |
| No key configured for target       | Pass through, render the `+AGM ...` line verbatim   |

Receivers MUST NOT decrypt under a key from a different target as a fallback
(e.g. trying the `#foo` key on a message that arrived in `#bar`). That would
break the cross-conversation replay protection.

Receivers SHOULD also keep a bounded, per-conversation cache of recently seen
nonces and drop any message whose `(conversation, nonce)` pair has already been
accepted. Every nonce is a fresh 96-bit random value, so a repeat means a
re-injected ciphertext (a replay), not a coincidence. The AAD binding alone
stops replay only into a *different* conversation, not back into the same one.

Senders SHOULD fail closed: if encryption fails for any reason, drop the message
rather than transmitting it as plaintext.

## Threat model

AGM v1 protects:
- Message content confidentiality against passive eavesdroppers (IRC ops,
  ISPs, bouncer operators if the IRC connection is TLS-terminated at the
  client).
- Integrity (a tampered message is rejected, not silently mangled).
- Cross-channel replay, plus replay back into the same conversation (via the
  receiver-side nonce cache described under Failure handling).
- Accidental plaintext leakage on the sender side (senders fail closed on any
  encryption error rather than transmitting cleartext).

A v2 scheme (`+AGE`) is planned to add per-conversation X25519 key exchange
and a double-ratchet for forward secrecy / post-compromise security. v1
intentionally ships first because it's a strict improvement over FiSH
without protocol complexity.

## Reference implementations

**HexDroid**: `app/src/main/java/com/boxlabs/hexdroid/crypto/AesGcmCipher.kt`
**HexChat plugin**: `aes-client-plugins/hexchat/hexdroid_agm.py` in the HexDroid repo.
