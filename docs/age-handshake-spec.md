# `+AGE`: a Signal-style secure messaging layer for IRC

`+AGE` gives HexDroid end-to-end encryption over ordinary IRC, using the Signal
building blocks (X3DH-style handshake, Double Ratchet, sender keys for groups, TOFU
identity) adapted to what IRC actually is: no PKI, no prekey server, a line-oriented
PRIVMSG transport, and both parties usually online at once. It slots into the existing
`E2eScheme` as the `+AGE` scheme.

> **Read this first.** The protocol here is assembled from vetted primitives (Ed25519,
> X25519, HKDF-SHA256, AES-256-GCM); the curve math is never hand-rolled. This document
> specifies how the pieces fit, the wire formats, and the framing. Every verification
> failure **fails closed**: on any doubt, drop and never render, and never put plaintext
> on the wire for an `+AGE`-enabled target.

---

## 0. Threat model

Protects against outsiders (server and bouncer operators, channel lurkers, anyone not in
the conversation) reading or injecting traffic, and against insiders impersonating each
other (every message carries a per-sender signature, so a shared group key does not let
one member forge as another).

Does **not** protect: metadata (the server sees who is in a channel, plus message sizes
and timing); availability (an operator can kick, ban, `+m`, or netsplit with no key); and
**first contact** (identity is trust-on-first-use, so an active attacker present at the
very first key exchange can substitute keys unless fingerprints are compared out of band).
The UI states these limits plainly.

---

## 1. Cryptographic backend

All asymmetric and symmetric operations sit behind one interface, `AgePrimitives`, so the
protocol layer is library-independent and unit-testable. That interface is the trust
boundary; everything above it deals only in `ByteArray`.

The single backend is **BouncyCastle**, used through its low-level lightweight API
(`org.bouncycastle.crypto.*`): `Ed25519Signer` / `Ed25519PrivateKeyParameters` for
signing, and `X25519Agreement` / `X25519PrivateKeyParameters` for **native** X25519 key
agreement. It is deliberately used without registering a JCA provider, so it never
collides with Android's platform-repackaged `com.android.org.bouncycastle`. BC's X25519
rejects all-zero (low-order) shared secrets; that rejection is surfaced as an
`AgeException` so callers keep failing closed.

The symmetric half (SHA-256, HMAC-SHA256, HKDF-SHA256, AES-256-GCM, CSPRNG,
constant-time compare) lives in `JcaAgePrimitives` on top of the platform JCE, shared by
the backend. There is no second backend: the DH key is a real X25519 keypair, not an
Ed25519 keypair reused through a birational map.

Key representation everywhere above the interface:

- private key   = its 32-byte seed
- signing public = 32-byte Ed25519 encoded point (`signingPublicKey(seed)`)
- DH public      = 32-byte X25519 u-coordinate (`dhPublicKey(seed)`)

---

## 2. Identity keys

A long-term identity is **two independent keypairs**, never one shared key:

- **Ed25519** `(IK_sig_pub, IK_sig_sk)`, signing; authenticates who sent a message.
- **X25519** `(IK_dh_pub, IK_dh_sk)`, Diffie-Hellman; lets others seal to you and seeds
  the 1:1 handshake.

Two keys rather than one: reusing a single key for both signing and DH is a known footgun,
and the one-key alternative (an X25519 identity plus XEdDSA signatures) needs XEdDSA, which
the backend does not expose. Two keys are simpler to reason about and BouncyCastle gives
both directly.

Generation happens once, on first run: 32 CSPRNG bytes per seed. The bundle is persisted
and never regenerated unless the user explicitly resets identity, which invalidates every
pin peers hold (warn before doing it).

---

## 3. Key storage

X25519 and Ed25519 seeds are not hardware-backed in the Android Keystore, so identity
private keys live in software while in use, protected at rest the same way proxy passwords
already are:

1. On first run, generate a hardware-backed AES-256-GCM master key in `AndroidKeyStore`
   (alias `hexdroid.age.master`); `setUserAuthenticationRequired` is optional but
   recommended for the signing key.
2. Encrypt the identity seed bundle under the master key.
3. Write the ciphertext atomically (the existing atomic-keyfile pattern); raw seeds never
   touch disk.

Residual risk to document: while in use the seeds are in process memory. Zeroize buffers
after use where practical, and accept that a rooted device can read them.

---

## 4. IDENT, TOFU pinning, and safety numbers

IRC has no certificate authority, so identity is trust-on-first-use, pinned by key and
verified out of band.

### Self-signed IDENT

A client advertises itself with a signed bundle that binds the DH key to the signing key,
so a third party cannot mix and match keys in transit:

```
body = IK_sig_pub || IK_dh_pub || created_at
sig  = Ed25519_sign(IK_sig_sk, "hexdroid/+AGE/ident/v1" || body)
```

Wire: `AGE IDENT 1 <b64(IK_sig_pub)> <b64(IK_dh_pub)> <created_at> <b64(sig)>`. The
receiver recomputes the preimage, verifies `sig` against `IK_sig_pub`, and only then pins
`{IK_sig_pub, IK_dh_pub}` for that peer. A malformed or unbinding line is dropped, not
pinned.

### Pinning rules

- Nick is not identity on IRC. Pin by key; treat the nick as a hint.
- First IDENT for a peer pins silently (TOFU).
- A different key later under a known nick, or a pinned key arriving under a different
  nick, is a **loud warning** and is never auto-accepted (this is the nick-takeover / MitM
  signal).

### Safety number

```
fp = SHA-256("hexdroid/+AGE/identity/v1" || IK_sig_pub || IK_dh_pub)
```

Rendered as grouped hex (or a word list) via the existing `E2eFingerprint` formatting. Two
users compare `fp` over a trusted channel (in person, by voice) to upgrade TOFU to
verified; the UI shows a verified / unverified badge.

---

## 5. The sealed box (`seal` / `open`)

A sealed box encrypts a payload **to** a recipient's X25519 key. Anyone can seal; only the
holder of `IK_dh_sk` can open. It is anonymous and confidential by the recipient's key
alone, which is why a sealed handshake or invite is safe to carry over an unencrypted
PRIVMSG.

```
seal(rpk, plaintext, aad):
  (epk, esk) = X25519 ephemeral
  shared     = X25519(esk, rpk)
  okm        = HKDF-SHA256(ikm=shared, salt=epk||rpk, info="hexdroid/+AGE/seal/v1", L=44)
  ct         = AES-256-GCM(key=okm[0:32], nonce=okm[32:44], plaintext, aad)
  blob       = epk(32) || ct           ; epk fresh per seal => (key,nonce) unique

open(IK_dh_sk, blob, aad):
  epk    = blob[0:32]
  shared = X25519(IK_dh_sk, epk)
  okm    = HKDF-SHA256(ikm=shared, salt=epk||IK_dh_pub, info="hexdroid/+AGE/seal/v1", L=44)
  plaintext = AES-256-GCM-open(okm[0:32], okm[32:44], blob[32:], aad)   ; throws => reject
```

A bare seal has no forward secrecy: later compromise of `IK_dh_sk` opens past seals. For
FS, carry the payload over the 1:1 ratchet instead. The seal is used for the handshake
messages (which then establish the ratchet) and for group-key invites.

---

## 6. 1:1 sessions: X3DH-lite handshake, then Double Ratchet

Signal fetches a prekey bundle from a server so it can encrypt a first message to an
offline contact. IRC has no such server, but on IRC both parties are usually online, so
`+AGE` runs an interactive two-message handshake instead of an asynchronous one. This is
the deliberate structural difference from Signal; everything after it is standard.

### Who initiates

Both sides advertise IDENT. The **initiator is the lower fingerprint** (lowercase hex);
the other is the responder. Deterministic election means no glare.

### Handshake (3-DH, X3DH-lite)

```
A -> B : HELLO = seal_B( sign_A( IK_A_sig || IK_A_dh || EK_A ) )
B -> A : ACK   = seal_A( sign_B( EK_B ) )
```

`EK_A`, `EK_B` are fresh X25519 ephemerals. Each side computes the same shared secret from
three DHs:

```
SK = HKDF-SHA256(
       ikm  = DH(IK_A, EK_B) || DH(EK_A, IK_B) || DH(EK_A, EK_B),
       salt = 0^32,
       info = "hexdroid/+AGE/handshake/v1",
       L    = 32)
```

Mutual authentication comes from the identity keys appearing in the DHs and from the
signatures; ephemeral secrecy comes from `EK_A` / `EK_B`. Following X3DH, `DH(IK_A, IK_B)`
is intentionally omitted to preserve deniability (nothing binds the transcript to both
long-term keys alone). `openHello` and `openAck` verify each signature against the peer's
**pinned** signing key, so a HELLO racing in with a different identity cannot hijack the
session.

`SK` seeds the Double Ratchet: the responder's DH ratchet public is `EK_B`, so the
initiator can send immediately.

### Double Ratchet

Standard Signal Double Ratchet: a DH ratchet (a fresh X25519 keypair each time the
sending direction turns over) wrapped around symmetric sending and receiving chains.

- Root KDF: `(rk', ck) = HKDF-SHA256(salt=rk, ikm=DH_out, L=64)`.
- Chain KDF advances a chain key and yields one message key per message.
- A message is `AGE PM <b64(header_dh_pub)> <pn> <n> <b64(ct)>`. The header (ratchet public,
  previous-chain length `pn`, message number `n`) is cleartext; the plaintext is sealed
  with AES-256-GCM under the message key, with associated data
  `AD || header_bytes`, where `AD` is the per-conversation constant below.
- Skipped message keys are stored (bounded) so out-of-order and gap delivery still decrypt.

Two properties matter for IRC's lossy, reorder-prone delivery:

- **Per-conversation AD**: `AD = sort(IK_A_fp, IK_B_fp).join("|")`, identical on both
  sides, binds every message to the pair.
- **Snapshot on failure**: decrypt stages all ratchet mutation on a snapshot and keeps it
  only if the AEAD tag verifies. A forged or corrupt message therefore cannot advance or
  break the ratchet (no permanent session-wedging DoS); it is simply dropped.

Forward secrecy and post-compromise recovery are the standard ratchet guarantees.

---

## 7. Group sessions: sender keys over a shared `K_G`

Channels (and scripted `#game` tables) use a **sender-keys** model: one 256-bit group key
`K_G` shared by the members, plus per-sender Ed25519 signatures so authentication survives
the shared key.

```
out:  inner = canonical(channel_or_game_id, epoch, seq, sender_fp, payload)
      sig   = Ed25519_sign(sender_sig_sk, "hexdroid/+AGE/msg/v1" || inner)
      k_s   = HKDF-SHA256(K_G, info="hexdroid/+AGE/msgkey/v1" || sender_fp || be32(epoch))
      nonce = sender_fp[0:8] || be32(seq)      ; unique per (sender, seq) => no GCM reuse
      ct    = AES-256-GCM(k_s, nonce, inner || sig, aad)
```

Wire: `AGE MSG <id> <sender_fp> <epoch> <seq> <b64(ct)>` for scripted moves, and
`AGE CHAT <id> <sender_fp> <epoch> <seq> <b64(ct)>` for manual chat. Same construction; the
verb only tells the reader whether the payload is a game move or chat text.

Receiver: decrypt with the sender's `k_s`, verify `sig` against the sender's pinned signing
key, enforce a monotonic `seq` per sender (a replay cache plus per-sender counters stop
replay and reorder). Any failure drops and never renders. Deriving `k_s` per sender and per
epoch means the group key is never used directly as an AEAD key, and a rekey (new epoch)
cleanly separates key eras.

A shared key alone gives outsiders no read access; the per-sender signature is what stops
one member forging as another. For scripted card games this doubles as non-repudiable
commit-reveal for free.

---

## 8. Hostless keying for manual channel `+AGE`

A scripted game has a natural host who mints `K_G` and invites named players. Manual
`+AGE` (the padlock a user toggles on a channel) has no host, so it uses a hostless variant
of the same primitives.

**Owner election.** On enable, a client sends `AGE IDENT` to the target and remembers every
other client that announces there. The **owner** is deterministic: the lowest fingerprint
among all `+AGE` members who have announced, including ourselves. The owner, and only the
owner, mints `K_G` and seals an invite (section 10) to each other announced member;
non-owners wait for that invite. Later `+AGE` joiners are re-sealed the existing `K_G` as
they announce.

**Self-key on enable.** A channel almost never contains only `+AGE` clients, so the owner
mints `K_G` immediately on enable even with an empty roster, rather than waiting for a
second `+AGE` member. The channel is then usable at once. Non-`+AGE` members, and any
`+AGE` member not yet holding `K_G`, see only `AGE CHAT` ciphertext and ignore it, which is
the intended meaning of turning the padlock on in a mixed channel. If a lower-fingerprint
`+AGE` member later enables, they become owner, mint their own `K_G`, and invite everyone;
each side adopts the new key on the invite (a `key changed` reset onto the new epoch).

**Rekey on membership change.** Adding a member seals the current `K_G` to them. Removing a
member and keeping them out of future traffic rotates to `K_G'` at `epoch+1`, seals it to
each remaining member, and announces `AGE REKEY <id> <epoch+1>`; the removed member keeps
only the old key. At channel and poker-table sizes this O(N) re-seal is trivial; the
O(log N) tree schemes of MLS are not needed here.

---

## 9. Hold-and-flush: the IRC substitute for asynchronous prekeys

The 1:1 handshake needs the peer's key, which a non-`+AGE` peer never sends. Signal avoids
a first-message stall with server-stored prekeys; IRC cannot, but both clients are online,
so `+AGE` **holds** the outbound text and chooses the key only once it learns what the peer
is:

- **Ratchet comes up** (peer's `AGE IDENT` arrived and HELLO/ACK completed): the held
  messages are flushed through the ratchet, so the peer decrypts them normally with full
  forward secrecy. This is the case that lets a message the user typed before the handshake
  finished still reach a `+AGE` peer, under a key they actually hold.
- **No `AGE IDENT` within a short grace** (about 3 seconds, granted twice while a handshake
  is visibly in flight): the peer runs no `+AGE` client, so the client self-keys the PM
  target with a fresh `K_G` and flushes the held messages as `AGE CHAT`. That is ciphertext,
  garbled to the peer, readable only in our own local echo. From then on, PMs to that peer
  self-key immediately, until a real ratchet later supersedes them.

The message is echoed locally at send time either way, since it is our own text; only the
wire transmission is deferred. The single edge this leaves: between two `+AGE` peers, a
message flushed by the grace's last-resort self-key on a badly lagged handshake goes out
under a self-key and will not decrypt for the peer. Steady state uses the ratchet. This
trades a rare, tiny loss for never blocking a send, and never weakens fail-closed.

---

## 10. Sealed invites

The owner distributes `K_G` by sealing a signed payload to each member's DH key:

```
payload = { v:1, id, K_G(32), params, members:[{nick, fp}, ...], owner_sig_pub, issued_at, expires_at }
signed  = payload || Ed25519_sign(owner_sig_sk, canonical(payload))
aad     = "hexdroid/+AGE/invite/v1" || member_nick || id
blob    = seal(member_dh_pub, signed, aad)
```

Wire, chunked over PRIVMSG (section 11): `AGE INVITE <id> <i>/<n> <b64(blob_chunk)>`. The
recipient reassembles, opens, verifies the owner signature against the **pinned**
`owner_sig_pub` (warn if not already pinned), checks `expires_at`, an unseen `id`, and that
they appear in `members`, then stores `K_G`. Because `K_G` is fresh per session, forward
secrecy holds between sessions; for FS within a session, rely on rekey.

---

## 11. Wire framing

All `+AGE` traffic is one verb space carried in PRIVMSG, the same lineage as CTCP and DCC;
non-`+AGE` clients ignore it. Binary blobs are base64 (RFC 4648, no wrap). PRIVMSG's line
limit means anything large (invites) is chunked `... <i>/<n> <b64>` and reassembled.

```
AGE IDENT 1 <b64(sigPub)> <b64(dhPub)> <createdAt> <b64(sig)>
AGE HELLO <b64(sealed)>
AGE ACK   <b64(sealed)>
AGE PM    <b64(headerDhPub)> <pn> <n> <b64(ciphertext)>
AGE INVITE <id> <i>/<n> <b64(blobChunk)>
AGE MSG   <id> <senderFp> <epoch> <seq> <b64(ciphertext)>
AGE CHAT  <id> <senderFp> <epoch> <seq> <b64(ciphertext)>
AGE REKEY <id> <epoch>
AGE FRAG  <id> <i>/<n> <b64(lineChunk)>
```

Tokens are single-space separated; `createdAt` is decimal seconds; `epoch`, `pn`, `n`, and
`seq` are decimal integers; `<i>/<n>` is a 1-based chunk index over the total. A receiver
rejects any line whose token count or separators do not match exactly. Every HKDF and AAD
carries a versioned `"hexdroid/+AGE/*/vN"` domain-separation label, so a future scheme or a
v2 coexists cleanly.

`AGE INVITE` chunks the sealed invite blob. `AGE FRAG` is the general form: it wraps a
fragment of *any* over-long `AGE ...` line (the whole line is base64'd, then split into
`<=350`-char chunks, at most 64 fragments). The sender fragments only when the assembled
PRIVMSG would exceed a conservative 480-byte budget; short lines go out verbatim. The
receiver reassembles by `(sender, id)`, decodes back to the original line, and re-dispatches
it through the normal verb handling, so fragmentation is invisible to every rule above and
below. Fragments never nest. See the wire-format reference ("Fragmentation") for the exact
bounds and reset behaviour.

---

## 12. Fail-closed rules

- Every signature, tag, and pin check that fails drops the message and never renders it.
- Never transmit plaintext for an `+AGE`-enabled target. A defense-in-depth backstop in the
  send path refuses any payload that is not `AGE`-prefixed when `+AGE` is on for the target.
- A forged inbound message must not mutate ratchet or channel state (snapshot on failure).
- Constant-time comparison for tags and fingerprints.
- Enforce per-sender monotonic `seq` plus a replay cache; reject duplicates and old
  sequence numbers.
- Rekey on member removal; the removed member keeps only the old `K_G`.
- Surface a low-order / all-zero DH result as an `AgeException`, never a usable key.

---

## 13. Code map

```
crypto/AgeCore        ; AgePrimitives interface + JcaAgePrimitives (symmetric) +
                      ;   BouncyCastleAgePrimitives (Ed25519 + native X25519); AgeCodec (TLV)
crypto/AgeIdentity    ; two-keypair identity, Keystore-wrapped load/save, fingerprint
crypto/AgeStore       ; TOFU pin map (peer key -> pinned bundle, verified flag), warnings
crypto/AgeProtocol    ; AgeSeal (seal/open), AgeHandshake (X3DH-lite), AgeRatchet
                      ;   (double ratchet), AgeChannel (sender-keys group), AgeInvite, AgeWire
script/cap/AgeScriptCapabilities ; encryptChat/decryptChat, group membership
script/cap/AgeScriptBridge       ; hostless owner election, sealed invites, hold-and-flush
```

`+AGE` is registered in `E2eScheme`. `AgeChannel` is what the `.hex` `game.*` verbs and
`on GAME:` events sit on; manual channel and PM chat sit on `AgeScriptBridge` +
`AgeRatchet`.

---

## 14. Pitfalls checklist

- [ ] Two keys (sign + DH), never one without XEdDSA.
- [ ] Private seeds Keystore-wrapped at rest; zeroize in-memory buffers post-use.
- [ ] Pin by key, not nick; loud warning on key-change / nick-takeover mismatch.
- [ ] TOFU is not verified: drive users to compare fingerprints out of band; show the badge.
- [ ] Handshake signatures checked against the pinned key; reject identity mismatch.
- [ ] Bare seal has no FS; carry anything needing FS over the ratchet.
- [ ] Ratchet: snapshot on failure so a forged message cannot wedge the session.
- [ ] Per-sender monotonic `seq` plus replay cache; reject dup / old.
- [ ] Constant-time compare for tags and fingerprints; every failure fails closed.
- [ ] Domain-separate every HKDF and AAD with a versioned label.
- [ ] Rekey on member removal; the removed member keeps the old `K_G`.
- [ ] Hold-and-flush never blocks a send and never emits plaintext.
- [ ] Tell users what is not protected: metadata, membership, availability, first contact.
