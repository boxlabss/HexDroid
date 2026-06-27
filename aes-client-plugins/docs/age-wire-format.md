# HexDroid `+AGE` wire format

Version 1. Curve25519 identity, sealed boxes, an encrypted group channel, and a 1:1
double ratchet for IRC.

This document is the authoritative reference for the `+AGE` scheme. Any client that
wants to interoperate with HexDroid `+AGE` traffic needs to implement this exactly. It
is the asymmetric successor to [`+AGM`](agm-wire-format.md): where AGM is a pre-shared
symmetric key, AGE adds per-identity keys, key exchange, per-game forward secrecy, and
(for 1:1) a forward-secret/post-compromise-secure ratchet.
AGE is an addition alongside AGM, registered as a second scheme.

## Goal

End-to-end encryption for IRC game/group traffic and private messages, with:
Per-identity keypairs (no out-of-band key sharing as in AGM v1)
Authentication *between* members (a shared group key alone lets any member forge as any
other, so every message is individually signed)
Per-game forward secrecy (a fresh random group key per game) and, on 1:1, a double
ratchet for forward secrecy and post-compromise security
Trust-on-first-use identity pinning with out-of-band fingerprint verification

This is **not** intended to compete with Signal. It is a pragmatic asymmetric layer for
IRC that needs no PKI and no protocol changes, carried entirely inside PRIVMSG.

## Curve and cipher parameters

**Signing:** Ed25519 (sign/verify)
**Key agreement:** X25519 (Curve25519 Diffie-Hellman)
**Hash / KDF:** SHA-256, HKDF-SHA256 (RFC 5869)
**AEAD:** AES-256-GCM: 32-byte key, 12-byte nonce, 16-byte tag (as in AGM)

An identity is **two** keypairs, never one: an Ed25519 signing key and an X25519 DH key.
Reusing one key for both signing and DH is a known footgun, and the one-key alternative
(X25519 identity + XEdDSA) needs XEdDSA, which most libraries don't expose. Each private
key is its 32-byte seed; each public key is the 32-byte encoded point.

## Canonical encoding (TLV)

Everything that is **signed or sealed** is encoded with a deterministic length-prefixed
form, never JSON. Signature verification must be over a byte-exact canonical form, and
JSON ordering/whitespace/number formatting are not deterministic.

```
field   := u32_be(length) || bytes        # a byte string or UTF-8 string
u32     := 4 bytes, big-endian            # written raw, NOT length-prefixed
u64     := 8 bytes, big-endian            # written raw, NOT length-prefixed
```

Field order is fixed by each message definition below and MUST match between encode and
decode. A reader that runs past the buffer end MUST fail closed.

## Wire format

All `+AGE` traffic is one sub-protocol verb space carried in PRIVMSG, same lineage as
CTCP/DCC; non-`+AGE` clients ignore it. Binary blobs are base64'd (RFC 4648, **no wrap**;
padding optional on receive). Line shapes:

```
AGE IDENT 1 <b64(edPub)> <b64(dhPub)> <createdAt> <b64(sig)>
AGE INVITE <id> <i>/<n> <b64(blobChunk)>
AGE MSG <gameId> <senderFp> <epoch> <seq> <b64(ciphertext)>
AGE REKEY <gameId> <epoch>
```

Tokens are single-space separated. `createdAt` is decimal seconds; `epoch` and `seq` are
decimal integers; `<i>/<n>` is the 1-based chunk index over total. A receiver MUST reject
any line whose token count or separators don't match exactly.

## Identity, IDENT announcement, and TOFU

IRC has no PKI, so identity is **trust-on-first-use**, pinned **by key**, verified
out-of-band. A client serializes its identity (private, at rest) as:

```
identity := u32(version=1) || field(sigSeed) || field(sigPub) || field(dhSeed) || field(dhPub)
publicBundle := field(sigPub) || field(dhPub)
```

A client announces itself with `AGE IDENT`, carrying its Ed25519 public key, X25519
public key, a creation timestamp, and an Ed25519 signature that binds all three together
(so an active attacker can't swap the DH key under a victim's signing key in transit):

```
sig = Ed25519_sign( sigSeed, field("hexdroid/+AGE/ident/v1") || field(edPub) || field(dhPub) || u64(createdAt) )
```

The receiver recomputes that TLV preimage from the line's `edPub`/`dhPub`/`createdAt`,
verifies `sig` against `edPub`, and only then **pins** `{edPub, dhPub}` for that peer. A
line that is malformed or whose signature doesn't bind is dropped, not pinned.

Pinning rules (these are the MITM / nick-takeover signal):
- Nick is **not** identity on IRC. Pin by key; treat the nick as a hint.
- First IDENT for a peer → pin silently (TOFU).
- A *different* key later under a known nick, or a pinned key under a different nick →
  **loud warning**, never auto-accept.

## Safety numbers (fingerprints)

The protocol-level peer id is the full 32-byte fingerprint:

```
fp = SHA-256( field("hexdroid/+AGE/identity/v1") || field(sigPub) || field(dhPub) )
```

Note the fingerprint is taken over the **TLV encoding** of the label and the two keys
(each length-prefixed), not a bare concatenation. The 32-byte digest is used as the
peer id in pin maps, member lists, and nonce derivation; its lowercase hex form is the
map key.

For out-of-band verification the **first 80 bits** are base32-encoded with the Crockford
alphabet (no `0/1/I/O`) into 16 characters, grouped in quads:

```
K4XR-T9BS-MN2P-QW7V
```

This is deliberately longer than the 40-bit per-key `+AGM` safety number: an `+AGE`
identity guards everything downstream, so the second-preimage bar is set higher. Users
compare it over a trusted channel to upgrade TOFU → verified; show a verified/unverified
badge.

## Sealed box (`seal`/`open`)

A sealed box encrypts a payload **to** a recipient's X25519 key. Anyone can seal; only
the holder of the DH seed can open. It is anonymous and confidential by the recipient's
key alone — which is why a sealed invite is safe to send over an *unencrypted* PM.

```
seal(rpk, plaintext, aad):
  esk    = random seed ; epk = X25519_pub(esk)
  shared = X25519(esk, rpk)
  okm    = HKDF-SHA256(ikm=shared, salt=epk||rpk, info="hexdroid/+AGE/seal/v1", L=44)
  ct     = AES-256-GCM(key=okm[0:32], nonce=okm[32:44], plaintext, aad)
  blob   = epk(32) || ct                         # ct includes the 16-byte tag
```

`open` recovers `epk = blob[0:32]`, computes `shared = X25519(dhSeed, epk)`, re-derives
`okm` with `salt = epk||dhPub`, and AES-GCM-opens the rest. The nonce is **derived, not
random**: `epk` is fresh per seal, so `(key, nonce)` is unique and GCM is never reused. A
blob shorter than 48 bytes (32 epk + 16 tag) MUST be rejected. A bare seal has **no
forward secrecy** — a later compromise of the recipient's DH seed opens past seals; for
FS, carry the payload over the 1:1 ratchet instead.

## Invites (host invites a player to a game)

The invite is a signed payload, sealed to the invitee:

```
payload := u32(version=1) || str(gameId) || field(groupKey) || str(params)
           || u32(memberCount) || { str(nick) || str(fpHex) } * memberCount
           || field(hostSigPub) || u64(issuedAt) || u64(expiresAt)
sig     := Ed25519_sign(hostSigSeed, "hexdroid/+AGE/invite-sign/v1" || payload)
signed  := field(payload) || field(sig)
aad     := "hexdroid/+AGE/invite-aad/v1" || inviteeNick      # raw UTF-8, NOT TLV
blob    := seal(inviteeDhPub, signed, aad)
```

`gameId` is **not** in the AAD (the opener doesn't know it until after decryption) but it
is inside the signed body, so the host signature already protects it from substitution.
The blob is base64'd and chunked (<350 base64 chars per chunk) into `AGE INVITE <id>
…` lines, correlated by `<id>` and reassembled (cap 64 chunks).

Invitee side: reassemble -> `open` -> verify the host signature against the **pinned**
host sig key (warn if not already pinned) -> check `expiresAt`, an unseen `gameId`, and
that the invitee appears in `members` -> accept and store `groupKey`. `groupKey` is a
fresh random 256-bit key per game, that alone gives forward secrecy *between* games.

## Channel messages (sign-then-encrypt)

Every `#game` line is signed with the sender's Ed25519, then `inner || sig` is encrypted
under the group key `K_G`:

```
inner  := str(gameId) || u32(epoch) || u32(seq) || str(senderFp) || field(move)
sig    := Ed25519_sign(senderSigSeed, "hexdroid/+AGE/msg-sign/v1" || inner)
signed := field(inner) || field(sig)
nonce  := senderFp[0:8] || u32_be(seq)                 # 12 bytes; unique per (sender, seq)
aad    := "hexdroid/+AGE/msg/v1" || gameId || senderFp || u32_be(seq)    # raw concat
ct     := AES-256-GCM(K_G, nonce, signed, aad)
```

Wire: `AGE MSG <gameId> <senderFp> <epoch> <seq> <b64(ct)>`. The nonce is **not**
transmitted, the receiver reconstructs it from `senderFp` and `seq`, both on the wire.
`senderFp[0:8]` is the first 8 bytes (16 hex chars) of the sender's fingerprint.

Receiver, in order, failing closed (drop, never render) at any step:
1. `gameId` matches and `epoch` matches the current epoch (else drop as stale/foreign).
2. `senderFp` is a known member → its pinned Ed25519 key.
3. AES-GCM-open under `K_G` with the reconstructed nonce and AAD.
4. Verify `sig` over `"msg-sign/v1" || inner` against the sender's pinned key.
5. Re-parse `inner` and cross-check `gameId/epoch/seq/senderFp` against the wire header,
   so a tampered cleartext header can't desync from the signed content.
6. Enforce **strictly increasing** `seq` per sender within an epoch (replay/reorder
   guard). The sign tag (`msg-sign/v1`) and the AAD tag (`msg/v1`) are distinct labels.

## Membership and rekey

`groupKey` lifetime is bounded by an `epoch` counter. Adding a member mid-game re-seals
`K_G` to the newcomer (a new invite) and announces them. **Removing** a member rotates to
a fresh `K_G'`, re-seals it to each *remaining* member, and announces `AGE REKEY <gameId>
<epoch+1>`; the removed member keeps the old key, so anything they must not read uses
`K_G'`. Rekeying resets the per-sender `seq` space for the new epoch. At poker-table
sizes (≤ ~8) this O(N) re-seal is trivial; MLS-style TreeKEM is unnecessary here.

## 1:1 handshake and double ratchet (forward secrecy)

Private messages use a two-message X3DH-style handshake to seed a Double Ratchet. Both
handshake messages are sealed and signed:

```
A → B  HELLO = seal_B( field(body) || field(sig) ),  body = field(A.sigPub)||field(A.dhPub)||field(EK_A_pub)
               sig = sign(A.sigSeed, "hexdroid/+AGE/hello-sign/v1" || body),  aad = "hexdroid/+AGE/hello/v1"
B → A  ACK   = seal_A( field(EK_B_pub) || field(sig) )
               sig = sign(B.sigSeed, "hexdroid/+AGE/ack-sign/v1" || EK_B_pub),  aad = "hexdroid/+AGE/ack/v1"
```

Shared secret from three DHs (the same three on both sides, in this order):

```
dh1 = X25519(IK_A, EK_B)   dh2 = X25519(EK_A, IK_B)   dh3 = X25519(EK_A, EK_B)
SK  = HKDF-SHA256(ikm = dh1||dh2||dh3, salt = 0^32, info = "hexdroid/+AGE/handshake/v1", L=32)
```

The ratchet then runs the standard Double Ratchet:

```
root KDF :  (rk', ck) = HKDF-SHA256(ikm = DH_out, salt = rk, info="hexdroid/+AGE/ratchet/rk/v1", L=64)
chain KDF:  mk = HMAC-SHA256(ck, 0x01) ;  ck' = HMAC-SHA256(ck, 0x02)
message  :  okm = HKDF-SHA256(mk, salt=0^32, info="hexdroid/+AGE/ratchet/msg/v1", L=44)
            ct  = AES-256-GCM(okm[0:32], okm[32:44], plaintext, ad || header)
header   :  field(dhPub) || u32(pn) || u32(n)             # cleartext, authenticated as AAD
```

Out-of-order messages are handled by storing skipped message keys, capped at
`MAX_SKIP = 1000` to bound a memory-exhaustion DoS. **Decryption is transactional:** a
forged or corrupt message MUST NOT advance the ratchet, derive/verify on a snapshot and
commit the chain advance only after the AEAD tag verifies, otherwise restore prior state
and reject. (Skipping this is a real session-break/remote-DoS bug.) The 1:1 ratchet's
PRIVMSG verb framing is not yet finalized in this version; the ratchet *state machine and
on-the-wire message structure* above are stable, the `AGE`-verb envelope for it is
forthcoming.

## Failure handling

| Failure mode                                   | Receiver behaviour                              |
| ---------------------------------------------- | ----------------------------------------------- |
| IDENT signature doesn't verify                 | Reject; do not pin                              |
| Known nick presents a new/different key        | Loud warning; do not auto-accept (possible MITM)|
| Seal/open tag mismatch (wrong recipient)       | Reject (fail closed)                            |
| Channel MSG bad tag/AAD                         | Drop, never render                              |
| Channel MSG bad per-sender signature           | Drop, never render                              |
| Wire header ≠ signed inner header              | Drop (tampered header)                          |
| `seq` ≤ last seen for that sender in epoch     | Drop as replay/reorder                          |
| Stale or foreign `epoch`                        | Drop                                            |
| Unknown sender fingerprint (not a member)      | Drop                                            |
| Invite expired / replayed / invitee not listed | Reject                                          |
| Ratchet AEAD open fails                         | Reject **and roll back** ratchet state          |
| Skipped-key store would exceed `MAX_SKIP`      | Reject (bounded to stop memory DoS)             |
| Malformed base64 / truncated TLV               | Reject                                          |

Senders MUST fail closed: if any signing, sealing, or encryption step fails, drop the
message rather than transmitting plaintext. Every verification uses constant-time
comparison.

## Threat model

`+AGE` protects:
- Confidentiality of group/PM content from outsiders (server/bouncer operators, channel
  lurkers, anyone not invited).
- Authentication *between* members: per-sender Ed25519 signatures stop a member forging
  as another under the shared key.
- Integrity (a tampered message is rejected, not silently mangled).
- Forward secrecy *between* games (fresh `K_G` per game) and, on 1:1, forward secrecy and
  post-compromise security from the ratchet.
- Replay/reorder (per-sender monotonic `seq` per epoch; ratchet message numbers).

It does **not** protect: metadata (the server sees who is in `#game`, message sizes and
timing); availability (an op can kick/ban/`+m`/netsplit with no key); and **first
contact** TOFU pins whatever key you first see, so an active attacker present at first
contact can substitute keys unless fingerprints are compared out of band. Say this in the
UI. A bare seal has no forward secrecy (route over the ratchet if that matters).

This spec covers the *protocol*: framing, canonical encoding, KDF/AAD domain separation,
and message flow. The curve primitives come from a vetted library; the protocol itself
still warrants an independent cryptographic review before it is relied on.

## Reference implementation

**HexDroid** (`app/src/main/java/com/boxlabs/hexdroid/crypto/`)

- **`AgeCore.kt`** primitives (`AgePrimitives`, `Curve25519AgePrimitives`), the canonical
  TLV writer/reader and base64 (`AgeCodec`), and the identity fingerprint (`AgeFingerprint`).
- **`AgeIdentity.kt`** identity types (`AgeIdentity`, `AgePublicIdentity`), the TOFU pin
  store (`AgeStore`), and the signed IDENT announce/verify (`AgeIdent`).
- **`AgeProtocol.kt`** sealed box (`AgeSeal`), game invites (`AgeInvite`), the channel
  sign-then-encrypt group layer (`AgeChannel`), the 1:1 X3DH-style handshake (`AgeHandshake`)
  and double ratchet (`AgeRatchet`), and the verb framing + chunking (`AgeWire`).
- **`AgeSelfTest.kt`** conformance tests (`AgeSelfTest`, `AgeRatchetSelfTest`).

Shared with `+AGM`: `crypto/AesGcm.kt`, `crypto/Crockford32.kt`.
