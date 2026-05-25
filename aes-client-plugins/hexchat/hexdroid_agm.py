"""
HexDroidIRC - An IRC Client for Android
Copyright (C) 2026 boxlabs

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.



hexdroid_agm.py

HexChat plugin for HexDroid's +AGM end-to-end encryption.

Adds AES-256-GCM encrypt/decrypt to HexChat so desktop users can interoperate
with HexDroid mobile users running the same key.

This plugin is single-purpose: it only handles the +AGM scheme. For +OK
(Blowfish / FiSH), install the fishlim plugin that ships with HexChat.

Commands:
  /AGM                        usage
  /AGM-INFO [target]          show key info for target (default: current channel)
  /AGM-SET <target> <base64>  install a 32-byte AES-256-GCM key for target
  /AGM-GEN [target]           generate a fresh random key for target
  /AGM-CLEAR [target]         remove the key for target
  /AGM-LIST                   list every configured key

Storage: ~/.config/hexchat/hexdroid_agm_keys.json (Linux/macOS) or
         %APPDATA%/HexChat/hexdroid_agm_keys.json (Windows).
File mode 0600 on Unix.

Wire format (documented in docs/agm-wire-format.md in the HexDroid repo):

   +AGM <base64(version || nonce || ciphertext || tag)>

   version    = 1 byte   (0x01)
   nonce      = 12 bytes (random per message)
   ciphertext = N bytes  (AES-256-GCM)
   tag        = 16 bytes (GCM auth tag)
   AAD        = canonical conversation id (lowercased, UTF-8):
                  channel -> the channel name
                  query   -> sorted({my_nick, peer_nick}) joined with NUL
"""

from __future__ import annotations

__module_name__        = "hexdroid_agm"
__module_version__     = "1.0.1"
__module_description__ = "HexDroid +AGM (AES-256-GCM) encryption support"

import base64
import collections
import hashlib
import json
import os
import secrets
import sys

try:
    import hexchat  # type: ignore
except ImportError:
    # Not running inside HexChat. There's nowhere useful to report to (and sys.stderr
    # may itself be None under HexChat), so just re-raise for a standalone interpreter.
    raise

# HexChat's embedded interpreter leaves sys.stdout / sys.stderr as None. Any warning or
# traceback emitted during the rest of this module's import then gets written to None and
# dies with "'NoneType' object has no attribute 'write'" - which MASKS the real error and
# surfaces as a useless "Failed to load module" message. The usual trigger is a
# CryptographyDeprecationWarning from the 'cryptography' package about HexChat's bundled
# Python version, raised right inside the import below. Route both streams to the HexChat
# window (installed BEFORE that import) so such warnings are visible and never fatal.
class _HexChatStream:
    def write(self, s):
        s = s.rstrip("\r\n")
        if s:
            hexchat.prnt(s)
    def flush(self):
        pass
    def isatty(self):
        return False

if sys.stdout is None:
    sys.stdout = _HexChatStream()
if sys.stderr is None:
    sys.stderr = _HexChatStream()

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    hexchat.prnt(
        "\x0304[+AGM]\x03 The 'cryptography' Python package is required but is not "
        "installed in HexChat's Python interpreter. Install it for that interpreter, "
        "then reload the script."
    )
    raise


# --------------------------------------------------------------------------- #
# Constants

WIRE_PREFIX     = "+AGM "       # any text starting with this is +AGM (with trailing spacee)
VERSION_BYTE    = 0x01
NONCE_LEN       = 12
TAG_LEN         = 16            # GCM tag length in bytes (AES-256-GCM standard)
KEY_LEN         = 32            # AES-256
HEADER_OVERHEAD = 1 + NONCE_LEN + TAG_LEN  # 29 bytes plain + ~33% b64 overhead


# --------------------------------------------------------------------------- #
# Key storage. JSON file mapping (network, target) -> base64 key.
#
# Keys are stored as base64. Target names are lowercased on write so case
# differences between "#Foo" and "#foo" don't create duplicate entries.

def _config_dir():
    # HexChat respects XDG_CONFIG_HOME on Linux, APPDATA on Windows. The
    # `hexchat.get_info("configdir")` API returns the right path on all OSes.
    return hexchat.get_info("configdir") or os.path.expanduser("~/.config/hexchat")


KEYFILE = os.path.join(_config_dir(), "hexdroid_agm_keys.json")


def _load_keys():
    """Read the on-disk keystore. Returns a dict of dicts:
       { network_name: { target_lower: base64_key, ... }, ... }
    Empty dict on any error (file missing, malformed) we never crash here
    because that would render every encrypted channel unreadable.
    """
    if not os.path.exists(KEYFILE):
        return {}
    try:
        with open(KEYFILE, "r", encoding="utf-8") as fh:
            obj = json.load(fh)
        if not isinstance(obj, dict):
            return {}
        # Sanitise: drop entries that don't decode to 32 bytes, since they'd
        # crash AESGCM construction later anyway.
        out = {}
        for net, targets in obj.items():
            if not isinstance(targets, dict):
                continue
            clean = {}
            for tgt, b64 in targets.items():
                if not isinstance(b64, str):
                    continue
                try:
                    raw = base64.b64decode(b64 + "==", validate=False)
                except Exception:
                    continue
                if len(raw) != KEY_LEN:
                    continue
                clean[tgt.lower()] = base64.b64encode(raw).decode("ascii")
            if clean:
                out[net] = clean
        return out
    except Exception:
        return {}


def _save_keys(d):
    """Write the in-memory keystore back to disk. File mode 0600 on Unix so
    only the current user can read the key bytes; on Windows the mode argument
    is ignored and the file inherits the user-profile ACL which is also
    restrictive enough for the use case.
    """
    tmp = KEYFILE + ".tmp"
    # Open with the strict mode from the very start (O_EXCL + 0600) so the file
    # never briefly exists world-readable between create and chmod, and so a
    # pre-planted symlink at the tmp path can't redirect the write.
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_EXCL
    try:
        fd = os.open(tmp, flags, 0o600)
    except FileExistsError:
        # Stale tmp from a previous crash: remove and retry once.
        try:
            os.unlink(tmp)
        except OSError:
            pass
        fd = os.open(tmp, flags, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(d, fh, indent=2, sort_keys=True)
    os.replace(tmp, KEYFILE)


_keys = _load_keys()  # module-level cache, kept in sync with the JSON file.


def _get_key(network, target):
    """Return the 32-byte raw key for (network, target), or None."""
    if not network:
        return None
    b64 = _keys.get(network, {}).get(target.lower())
    if not b64:
        return None
    try:
        return base64.b64decode(b64 + "==", validate=False)
    except Exception:
        return None


def _set_key(network, target, raw):
    if len(raw) != KEY_LEN:
        raise ValueError(f"AES-256-GCM key must be exactly {KEY_LEN} bytes, got {len(raw)}")
    if not network:
        raise ValueError("Current network unknown; switch to the network tab and retry")
    _keys.setdefault(network, {})[target.lower()] = base64.b64encode(raw).decode("ascii")
    _save_keys(_keys)


def _clear_key(network, target):
    if not network or network not in _keys:
        return False
    if target.lower() not in _keys[network]:
        return False
    del _keys[network][target.lower()]
    if not _keys[network]:
        del _keys[network]
    _save_keys(_keys)
    return True


# --------------------------------------------------------------------------- #
# Wire format encode / decode.
#
# These are the only two functions that touch the cipher, everything else
# threads through them. The AAD binds a ciphertext to its conversation so a
# ciphertext from #secret is unreadable when injected into #public.

def _aad_context(my_nick, target):
    """Canonical conversation identifier used as AAD.

    Channels: the channel name (lowercased) - identical for every participant.
    Queries:  the unordered pair of {my_nick, peer_nick}, lowercased and sorted,
              joined with a NUL. Because it is sorted, both endpoints compute the
              same bytes regardless of who is sending, so queries actually decrypt
              (binding to the bare per-side nick would make the two sides' AADs
              differ and every query would fail). NUL can't appear in a nick, so
              the join is unambiguous.

    Returns a *str*; callers lowercase+encode it (see _encrypt/_decrypt).
    """
    if _is_channel(target):
        return target.lower()
    a, b = sorted([my_nick.lower(), target.lower()])
    return a + "\x00" + b


def _encrypt(plaintext, key, aad_context):
    """Encrypt `plaintext` (str) and return the full wire line, e.g.
       "+AGM AQID...base64...". `aad_context` is the canonical conversation id
       from _aad_context().
    """
    nonce = secrets.token_bytes(NONCE_LEN)
    aesgcm = AESGCM(key)
    aad = aad_context.lower().encode("utf-8")
    ct = aesgcm.encrypt(nonce, plaintext.encode("utf-8"), aad)
    payload = bytes([VERSION_BYTE]) + nonce + ct
    return WIRE_PREFIX + base64.b64encode(payload).decode("ascii").rstrip("=")


def _decrypt(wire, key, aad_context):
    """Decrypt a "+AGM <base64>" line. Returns the plaintext, or None on any
    failure (malformed payload, wrong key, tampered tag, replayed across
    conversations, or a replayed/duplicate nonce within a conversation).
    `aad_context` is the canonical conversation id from _aad_context().
    """
    if not wire.startswith(WIRE_PREFIX):
        return None
    b64 = wire[len(WIRE_PREFIX):].strip()
    try:
        # Tolerate padded and unpadded base64. The cryptography library's
        # b64decode is strict about padding so we add it back.
        padded = b64 + "=" * ((4 - len(b64) % 4) % 4)
        raw = base64.b64decode(padded, validate=False)
    except Exception:
        return None
    if len(raw) < 1 + NONCE_LEN + TAG_LEN:
        return None
    if raw[0] != VERSION_BYTE:
        return None
    nonce = raw[1:1 + NONCE_LEN]
    ct_and_tag = raw[1 + NONCE_LEN:]
    try:
        aesgcm = AESGCM(key)
        aad = aad_context.lower().encode("utf-8")
        pt = aesgcm.decrypt(nonce, ct_and_tag, aad)
    except Exception:
        return None
    # Replay guard.  Checked only after the tag verifies.
    # A repeated (key, conversation, nonce) means the ciphertext was re-injected
    # the AAD-bound tag stops cross-conversation replay but not a replay back into
    # the SAME conversation. Drop the duplicate.
    if _replay_seen(key, aad_context, nonce):
        return None
    return pt.decode("utf-8", errors="replace")


# Bounded LRU of authenticated (key, conversation, nonce) triples, namespaced by a
# short key digest so distinct keys keep independent histories. In-memory and
# session-scoped; OrderedDict gives O(1) LRU via move_to_end + popitem(last=False).
_REPLAY_CACHE_MAX = 2048
_seen_nonces: "collections.OrderedDict[bytes, bool]" = collections.OrderedDict()


def _replay_seen(key, aad_context, nonce):
    """Return True if this (key, conversation, nonce) was seen before (a replay),
    else record it and return False. Every AGM nonce is a fresh random 96-bit
    value, so a repeat is a re-injected ciphertext, never a coincidence.
    """
    kid = hashlib.sha256(key).digest()[:8]
    rk = kid + b"\x00" + aad_context.lower().encode("utf-8") + b"\x00" + bytes(nonce)
    if rk in _seen_nonces:
        _seen_nonces.move_to_end(rk)
        return True
    _seen_nonces[rk] = True
    if len(_seen_nonces) > _REPLAY_CACHE_MAX:
        _seen_nonces.popitem(last=False)
    return False


# --------------------------------------------------------------------------- #
# Fingerprint (safety number). Matches HexDroid's E2eFingerprint.kt: SHA-256
# over (scheme_ordinal_byte || key), truncated to 40 bits, base32-encoded with
# the Crockford alphabet (no 0/1/I/O) and a hyphen at midpoint: "K4XR-T9BS".
#
# The scheme byte is 0x00 for AGM since AGM is the first enum value in
# E2eScheme.kt. Keep this in sync if the enum order ever changes.

_CROCKFORD = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # 32 chars, no 0/1/I/O


def _fingerprint(key):
    digest = hashlib.sha256(bytes([0x00]) + key).digest()
    # Take the first 5 bytes = 40 bits, base32-encode into 8 chars (5 bits each).
    n = (
        (digest[0] << 32)
        | (digest[1] << 24)
        | (digest[2] << 16)
        | (digest[3] << 8)
        | digest[4]
    )
    out = []
    for i in range(7, -1, -1):
        sym = (n >> (i * 5)) & 0x1F
        out.append(_CROCKFORD[sym])
        if i == 4:
            out.append("-")
    return "".join(out)


# --------------------------------------------------------------------------- #
# HexChat hooks
#
# Outbound: hook the user's input via "Your Message" (channel send) and "Your
# Action" (`/me`). The hook returns EAT_ALL after re-sending the message via
# `hexchat.command("msg ...")` with the encrypted body, so HexChat's own send
# pipeline never sees the cleartext on the wire side.
#
# Inbound: hook "Channel Message" and "Channel Action" + "Private Message",
# decrypt if the text starts with +AGM, and re-emit the event with the
# plaintext. The `hexchat.emit_print` call re-fires the same event so any
# downstream hooks (logs, notifications, etc.) see the decrypted text.

def _current_network():
    return hexchat.get_info("network") or hexchat.get_info("server") or ""


def _current_channel():
    return hexchat.get_info("channel") or ""


def _is_channel(name):
    return name and name[0] in "#&+!"


def _print_status(msg):
    hexchat.prnt(f"\x0304[+AGM]\x03 {msg}")


# Outbound: intercept the user's input at the COMMAND layer, not via print events.
#
# hook_command("") fires for plain text BEFORE it is sent, so returning EAT_ALL
# actually suppresses the cleartext send. word_eol[0] is the whole typed line. We
# send the ciphertext with `raw` (which does NOT echo locally) and then emit_print
# the plaintext ourselves, giving a single readable local line. emit_print fires a
# *print* event, which does not trigger command hooks, so there is no recursion.

def _on_send_message(word, word_eol, userdata):
    """Fires when the user sends plain text to a channel or query (hook_command("")).
    Replaces it with an encrypted PRIVMSG when a key is configured for the target.
    """
    cleartext = word_eol[0] if word_eol else ""
    # Empty input, or our own re-sent ciphertext somehow looping back: ignore.
    if not cleartext or cleartext.startswith(WIRE_PREFIX):
        return hexchat.EAT_NONE
    network = _current_network()
    # In a channel this is the channel name; in a query dialog it is the peer's
    # nick. Either way it is the key-storage target for the conversation.
    target = _current_channel()
    if not target:
        return hexchat.EAT_NONE
    key = _get_key(network, target)
    if not key:
        return hexchat.EAT_NONE  # no encryption configured; let HexChat send as normal
    my_nick = hexchat.get_info("nick") or "me"
    try:
        ct = _encrypt(cleartext, key, _aad_context(my_nick, target))
    except Exception as e:
        # Fail CLOSED: eat the input so HexChat does not fall through and send the
        # plaintext.
        _print_status(f"ENCRYPTION FAILED — message NOT sent: {e}")
        return hexchat.EAT_ALL
    # `raw` sends the line without a local echo (unlike `msg`, which would print the
    # ciphertext); we render the plaintext ourselves below.
    hexchat.command(f"raw PRIVMSG {target} :{ct}")
    # Display the cleartext locally with a lock prefix so the user sees what they
    # sent, the same way HexDroid renders local echo with a padlock.
    hexchat.emit_print("Your Message", my_nick, "\u00033[+AGM]\u0003 " + cleartext)
    return hexchat.EAT_ALL


def _on_send_action(word, word_eol, userdata):
    """Fires on /me (hook_command("ME")). Encrypts only the inner action text; the
    CTCP envelope (\x01ACTION ...\x01) stays cleartext, matching HexDroid.
    """
    # For a "ME" command, word[0] == "me" and word_eol[1] is the action text.
    text = word_eol[1] if len(word_eol) > 1 else ""
    if not text or text.startswith(WIRE_PREFIX):
        return hexchat.EAT_NONE
    network = _current_network()
    # Channel name, or peer nick when the /me is in a query dialog.
    target = _current_channel()
    if not target:
        return hexchat.EAT_NONE
    key = _get_key(network, target)
    if not key:
        return hexchat.EAT_NONE
    my_nick = hexchat.get_info("nick") or "me"
    try:
        ct = _encrypt(text, key, _aad_context(my_nick, target))
    except Exception as e:
        # Fail CLOSED (see _on_send_message). Never let a /me fall through to plaintext.
        _print_status(f"ENCRYPTION FAILED — /me NOT sent: {e}")
        return hexchat.EAT_ALL
    # Send a raw CTCP ACTION so the framing stays clear and only the inner text is
    # encrypted. `raw` does not echo locally, so we emit_print the plaintext below.
    hexchat.command(f"raw PRIVMSG {target} :\x01ACTION {ct}\x01")
    hexchat.emit_print("Your Action", my_nick, "\u00033[+AGM]\u0003 " + text)
    return hexchat.EAT_ALL


def _sanitize_for_recv(s):
    """Strip bytes that could break the raw-line / CTCP framing when the decrypted
    text is spliced back into a line for RECV. A peer holds the shared key, so they
    are authenticated - but authenticated is not the same as trusted: a malicious
    peer could embed CR/LF to inject arbitrary IRC protocol lines into our client, a
    NUL to truncate, or \\x01 to forge a CTCP. We remove exactly those. Display
    formatting (colour \\x03, bold \\x02, underline \\x1f, reverse \\x16, italic
    \\x1d, reset \\x0f) is harmless and preserved. This sanitisation is the one piece
    that turns RECV re-injection from a footgun into a safe approach.
    """
    return s.translate({0x00: None, 0x0a: None, 0x0d: None, 0x01: None})


# Reentrancy guard. RECV pushes the decrypted line back through HexChat's inbound
# pipeline, which re-enters THIS hook. The re-injected trailing no longer starts with
# "+AGM " so it would fall through anyway, but the flag makes our own line skip
# processing outright and avoids a spurious "decrypt failed" notice in the unlikely
# case a decrypted plaintext legitimately begins with "+AGM ".
_reinjecting = False


def _on_server_privmsg(word, word_eol, userdata):
    """Inbound interception at the raw-server layer. For an AGM line we decrypt, then
    re-inject the plaintext via RECV so HexChat's FULL native pipeline runs on the
    cleartext - highlight-on-your-nick, message-vs-hilight classification, tab
    activity colour, tray/sound notification, logging and timestamps all happen
    exactly as they would for an unencrypted message. (The old print-hook + emit_print
    approach ran all of that against the ciphertext, so e.g. a ping inside an
    encrypted message never actually highlighted.)

    word[0]=":nick!user@host"  word[1]="PRIVMSG"  word[2]=target  word_eol[3]=":trailing"
    """
    global _reinjecting
    if _reinjecting:
        return hexchat.EAT_NONE
    if len(word) < 3 or len(word_eol) < 4:
        return hexchat.EAT_NONE
    prefix = word[0]
    # Defensive: if IRCv3 message tags ever reach this named hook (HexChat normally
    # strips them for named server events), prefix would start with "@", bail rather
    # than misparse. Verify on-device; remove if confirmed unnecessary.
    if prefix.startswith("@"):
        return hexchat.EAT_NONE
    target = word[2]
    trailing = word_eol[3]
    if trailing.startswith(":"):
        trailing = trailing[1:]

    # CTCP ACTION (/me) carries the encrypted text inside \x01ACTION ...\x01. Any other
    # CTCP (VERSION/PING/DCC/...) is never our payload, so leave it for HexChat.
    is_action = False
    inner = trailing
    if trailing.startswith("\x01ACTION ") and trailing.endswith("\x01"):
        is_action = True
        inner = trailing[len("\x01ACTION "):-1]
    elif trailing.startswith("\x01"):
        return hexchat.EAT_NONE

    if not inner.startswith(WIRE_PREFIX):
        return hexchat.EAT_NONE          # not an AGM line - HexChat handles it normally

    sender = prefix[1:].split("!", 1)[0] if prefix.startswith(":") else prefix.split("!", 1)[0]
    network = _current_network()
    # Key lives under the conversation's "other side": the channel for channel
    # messages, the sender's nick for a query (target == us).
    key_target = target if _is_channel(target) else sender
    key = _get_key(network, key_target)
    if not key:
        return hexchat.EAT_NONE          # no key: leave the +AGM line visible

    my_nick = hexchat.get_info("nick") or ""
    pt = _decrypt(inner, key, _aad_context(my_nick, key_target))
    if pt is None:
        _print_status(f"Decrypt failed for message from {sender} in {key_target}")
        return hexchat.EAT_NONE          # leave the +AGM line visible for diagnosis

    safe = _sanitize_for_recv(pt)
    marker = "\u00033[+AGM]\u0003 "
    if is_action:
        # Marker goes INSIDE the CTCP envelope so the \x01 framing stays intact.
        new_trailing = "\x01ACTION " + marker + safe + "\x01"
    else:
        new_trailing = marker + safe
    line = "%s PRIVMSG %s :%s" % (prefix, target, new_trailing)

    _reinjecting = True
    try:
        hexchat.command("RECV " + line)
    finally:
        _reinjecting = False
    return hexchat.EAT_ALL


# --------------------------------------------------------------------------- #
# Commands

def _cmd_agm(word, word_eol, userdata):
    _print_status("Commands: /AGM-GEN [target] | /AGM-SET <target> <base64> | "
                  "/AGM-CLEAR [target] | /AGM-INFO [target] | /AGM-LIST")
    return hexchat.EAT_ALL


def _cmd_agm_gen(word, word_eol, userdata):
    target = word[1] if len(word) > 1 else _current_channel()
    if not target:
        _print_status("Usage: /AGM-GEN [target]   (need a channel or specify one)")
        return hexchat.EAT_ALL
    network = _current_network()
    raw = secrets.token_bytes(KEY_LEN)
    try:
        _set_key(network, target, raw)
    except Exception as e:
        _print_status(f"Failed to store key: {e}")
        return hexchat.EAT_ALL
    b64 = base64.b64encode(raw).decode("ascii").rstrip("=")
    fp = _fingerprint(raw)
    _print_status(f"Generated AGM key for {target} on {network}.")
    _print_status(f"Safety number: {fp}")
    _print_status(f"On the other device run:  /AGM-SET {target} {b64}")
    _print_status("(Verify the safety number matches before sending anything sensitive.)")
    return hexchat.EAT_ALL


def _cmd_agm_set(word, word_eol, userdata):
    if len(word) < 3:
        _print_status("Usage: /AGM-SET <target> <base64>")
        return hexchat.EAT_ALL
    target = word[1]
    b64 = word_eol[2].strip()
    try:
        # Strict decode (validate=True) so a stray non-base64 character from a
        # paste (bold markers, smart quotes, an accidental space) is reported as
        # an error instead of being silently stripped into a wrong-but-valid key
        # the user only discovers later when decryption fails.
        # Padding tolerance is kept by re-padding before decoding.
        padded = b64 + "=" * ((4 - len(b64) % 4) % 4)
        raw = base64.b64decode(padded, validate=True)
    except Exception:
        _print_status("Not a valid base64 key (contains non-base64 characters).")
        return hexchat.EAT_ALL
    if len(raw) != KEY_LEN:
        _print_status(f"Expected 32 bytes after base64 decode, got {len(raw)}.")
        return hexchat.EAT_ALL
    network = _current_network()
    try:
        _set_key(network, target, raw)
    except Exception as e:
        _print_status(f"Failed to store key: {e}")
        return hexchat.EAT_ALL
    fp = _fingerprint(raw)
    _print_status(f"Installed AGM key for {target} on {network}. Safety number: {fp}")
    return hexchat.EAT_ALL


def _cmd_agm_clear(word, word_eol, userdata):
    target = word[1] if len(word) > 1 else _current_channel()
    if not target:
        _print_status("Usage: /AGM-CLEAR [target]")
        return hexchat.EAT_ALL
    network = _current_network()
    removed = _clear_key(network, target)
    if removed:
        _print_status(f"Cleared AGM key for {target} on {network}.")
    else:
        _print_status(f"No key was configured for {target} on {network}.")
    return hexchat.EAT_ALL


def _cmd_agm_info(word, word_eol, userdata):
    target = word[1] if len(word) > 1 else _current_channel()
    if not target:
        _print_status("Usage: /AGM-INFO [target]")
        return hexchat.EAT_ALL
    network = _current_network()
    raw = _get_key(network, target)
    if not raw:
        _print_status(f"No AGM key configured for {target} on {network}.")
        return hexchat.EAT_ALL
    fp = _fingerprint(raw)
    _print_status(f"{target} on {network}:  AES-256-GCM  fingerprint={fp}")
    return hexchat.EAT_ALL


def _cmd_agm_list(word, word_eol, userdata):
    if not _keys:
        _print_status("No AGM keys configured anywhere.")
        return hexchat.EAT_ALL
    for network in sorted(_keys.keys()):
        targets = _keys[network]
        for target in sorted(targets.keys()):
            raw = _get_key(network, target)
            if raw:
                _print_status(f"  {network} : {target}  ({_fingerprint(raw)})")
    return hexchat.EAT_ALL


# --------------------------------------------------------------------------- #
# Registration
#
# We keep every hook handle and unhook them explicitly in the unload callback.
# HexChat removes hooks automatically on unload, but on some Windows builds that
# automatic teardown runs each Hook object's __del__ during interpreter GC, where
# it raises "initializer for ctype 'struct _hexchat_hook *' ... different ffi
# instances" once per hook and can crash the client.

_hooks = []

# Outbound interception happens at the command layer (see _on_send_message): "" is
# the hook for plain text typed into a channel/query, "ME" is the /me action. These
# fire BEFORE the message is sent, so EAT_ALL suppresses the cleartext.
_hooks.append(hexchat.hook_command("",   _on_send_message))
_hooks.append(hexchat.hook_command("ME", _on_send_action))
# Inbound interception moved to the raw-server layer. We hook PRIVMSG (which covers
# channel messages, queries and /me actions all PRIVMSG on the wire), decrypt, and
# RECV the plaintext back through HexChat's native pipeline.
_hooks.append(hexchat.hook_server("PRIVMSG", _on_server_privmsg))

_hooks.append(hexchat.hook_command("AGM",       _cmd_agm,       help="/AGM                       [show AGM plugin usage]"))
_hooks.append(hexchat.hook_command("AGM-GEN",   _cmd_agm_gen,   help="/AGM-GEN [target]          [generate a new key]"))
_hooks.append(hexchat.hook_command("AGM-SET",   _cmd_agm_set,   help="/AGM-SET <target> <base64> [install a key]"))
_hooks.append(hexchat.hook_command("AGM-CLEAR", _cmd_agm_clear, help="/AGM-CLEAR [target]        [remove a key]"))
_hooks.append(hexchat.hook_command("AGM-INFO",  _cmd_agm_info,  help="/AGM-INFO [target]         [show key info]"))
_hooks.append(hexchat.hook_command("AGM-LIST",  _cmd_agm_list,  help="/AGM-LIST                  [list every configured key]"))


def _on_unload(userdata):
    # Unhook everything ourselves, then drop the references, so HexChat's own
    # GC-time finalizers have nothing left to clean up (see note above).
    global _hooks
    for h in _hooks:
        try:
            hexchat.unhook(h)
        except Exception:
            pass
    _hooks = []
    hexchat.prnt(f"\x0304[+AGM]\x03 {__module_name__} {__module_version__} unloaded.")


hexchat.hook_unload(_on_unload)
hexchat.prnt(
    f"\x0309[+AGM]\x03 {__module_name__} {__module_version__} loaded. "
    f"Type /AGM for usage. Keys stored in {KEYFILE}"
)
