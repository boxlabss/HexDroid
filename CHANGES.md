# HexDroid IRC — Development Changes

## Feature: Nick Mention Completion (`ChatScreen.kt`)

Added a `NickHints` composable that mirrors the existing `CommandHints` design.

**Trigger:** Type `@` followed by one or more characters in any channel buffer.

**Behaviour:**
- Scans the live nicklist and shows matching nicks as chips in the same popup panel used by `/command` completion.
- Chips show the full display nick including mode prefix (e.g. `@admin`, `+voice`).
- Detail row at the bottom shows `@base_nick` and a "Tap to mention" hint.
- Tapping a chip inserts `nick: ` (when the `@prefix` is the only thing typed) or `@nick ` (mid-sentence) — standard IRC mention convention.
- At most 16 matches are shown, sorted alphabetically by base nick.
- Nick hints and command hints are mutually exclusive: command hints take priority when the input starts with `/`.
- Only active in channel buffers (`#`, `&`); server buffers and DCC chat buffers are excluded.

**Files changed:** `ui/ChatScreen.kt`

---

## Feature: DCC Chat Notification & One-Tap Open

### Problem
When a DCC CHAT offer arrived, the user had to:
1. Notice the server buffer message.
2. Navigate to the Transfers screen via "More" > "File transfers".
3. Accept the offer there.
4. Navigate back to the `DCCCHAT:peer` buffer.

### Fix: Notification deep-links into the buffer (`NotificationHelper.kt`, `IrcViewModel.kt`)

- `notifyDccIncomingChat()` now accepts an optional `dccBufferKey` parameter.
- When the key is passed (always the case now), tapping the notification opens the `DCCCHAT:peer` buffer directly — the same as tapping a highlight notification for any other buffer.
- A secondary "Open Transfers" action button is added for users who want to accept/reject from the Transfers screen.
- The `DCCCHAT:peer` buffer is now created immediately when the offer arrives (not only on accept), so it exists to receive the notification deep-link.
- An informational message is pre-posted in the new buffer: `"*** DCC CHAT offer from X ... Use /dcc accept X or open Transfers to accept."`

### Fix: Transfers screen DCC chat card UI (`TransfersScreen.kt`)

- Pending chat offers now show a **badge** with the count next to the section heading.
- Each offer card uses `secondaryContainer` colouring to stand out visually.
- Added **Accept & Open** button that accepts the offer and navigates to the buffer in one tap (via new `onOpenBuffer` callback parameter).
- Protocol label chip displayed when the protocol is non-standard (e.g. `ssl-chat`).

---

## Security: TOFU Certificate Pinning (`IrcCore.kt`, `SettingsRepository.kt`, `IrcViewModel.kt`)

Replaced the all-or-nothing `allowInvalidCerts` toggle with proper **Trust On First Use (TOFU)** fingerprint pinning — the same model used by SSH.

### How it works

1. **First connect** with `allowInvalidCerts = true` (unchanged for initial setup):  
   After a successful TLS handshake the server's SHA-256 certificate fingerprint is computed, a `TlsFingerprintLearned` event is emitted, and the fingerprint is automatically persisted in `NetworkProfile.tlsTofuFingerprint` via `SettingsRepository.updateNetworkProfile()`.  
   A status line is shown in the server buffer confirming the fingerprint was learned.

2. **Subsequent connects** with a stored fingerprint:  
   The fingerprint is verified against the presented certificate. If they match, the connection proceeds normally (`(TOFU-pinned)` shown in the TLS info string instead of `(unverified)`).

3. **Fingerprint change** (cert rotation or MITM):  
   Connection is aborted. A `TlsFingerprintChanged` event fires. A high-visibility warning is posted in the server buffer naming both the expected and actual fingerprints. The user is guided to use `allowInvalidCerts` once to re-pin if it was a legitimate renewal.

### New fields / API

| Where | What |
|---|---|
| `IrcConfig.tlsTofuFingerprint: String?` | Passed into the TLS connection |
| `NetworkProfile.tlsTofuFingerprint: String?` | Persisted in JSON; `null` = not yet learned |
| `IrcEvent.TlsFingerprintLearned(fingerprint)` | Emitted on first-time learn |
| `IrcEvent.TlsFingerprintChanged(stored, actual)` | Emitted on mismatch |
| `SettingsRepository.updateNetworkProfile(id) { … }` | Targeted single-field update |

---

## Security: Secret Migration Reliability (`data/SettingsRepository.kt`)

**Problem:** The SASL password migration (`migrateLegacySecretsIfNeeded`) set the "migration done" flags *after* a try/catch block. If an exception occurred *outside* the catch (from `arr.toString()`, `secretStore.setSaslPassword()`, etc.), the flags would not be set, causing the migration to retry on every launch indefinitely.

**Fix:** Wrapped the entire migration body in `runCatching`. Regardless of whether the body succeeded or threw, the flags are always set at the end. Failures are logged to Logcat with a clear message rather than silently swallowed.

---

## Bug Fix: Post-Connect Commands Whitespace (`IrcViewModel.kt`)

**Problem:** `autoCommandsText` lines with leading/trailing whitespace (common when copy-pasting) were sent verbatim — a line like `" /msg nickserv identify pw"` was sent as a raw line starting with a space, not as a `/msg` command. This was a silent failure.

**Fix:** Each line is now `.trim()`-ed before the command-type check. Empty lines after trimming are filtered out.

---

## Bug Fix: Passive DCC Offer Validation (`DccManager.kt`)

**Problem:** `DccOffer.isPassive` was computed as `port == 0 && token != null`. Some misbehaving clients send `port == 0` without a token (malformed passive DCC). `receivePassive()` would proceed and then crash with a confusing `IllegalArgumentException` deep inside the transfer coroutine.

**Fix:** `receivePassive()` now validates both conditions upfront:
```kotlin
if (offer.port != 0) throw IllegalArgumentException("Passive DCC offer has non-zero port: ${offer.port}")
val token = offer.token ?: throw IllegalArgumentException("Passive DCC offer is missing token")
```
The caller (ViewModel) receives the exception and posts a readable error message in the buffer.

---

## Translations: Complete Coverage for All 14 Languages

**Problem:** String resources were only used in `SettingsScreen.kt`. All other screens (`ChatScreen`, `TransfersScreen`, `NetworkEditScreen`, etc.) used hardcoded English strings.

**Added string keys:** 47 previously-missing keys from the base `strings.xml` (network edit fields, chat kick/ban buttons, font/style pickers, vibration labels, DCC toggle), plus 29 new keys for:
- Transfers screen labels (send file, accept/reject, DCC chat section headings, etc.)
- Nick hint "Tap to mention" label
- Notification channel names (for Android Settings display)
- DCC notification title/body strings

**Languages updated:** German (de), French (fr), Spanish (es), Italian (it), Dutch (nl), Portuguese (pt), Polish (pl), Russian (ru), Turkish (tr), Arabic (ar), Japanese (ja), Korean (ko), Chinese (zh).

---

## Code Quality Notes

- `IrcCore.kt` — TOFU fingerprint check added with clear section comments. The `InsecureTrustManager` is still used when `allowInvalidCerts = true` and no fingerprint is stored yet, preserving backward compatibility for initial setup.
- `SettingsRepository.kt` — New `updateNetworkProfile(id) { transform }` replaces the pattern of reading all networks, mutating one, and writing all back from call sites.
- `NickHints` composable is entirely self-contained and follows the same code style as `CommandHints` for easy future maintenance.
