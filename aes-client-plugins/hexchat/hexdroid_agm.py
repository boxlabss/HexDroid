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
__module_version__     = "1.0.0"
__module_description__ = "HexDroid +AGM (AES-256-GCM) encryption support"

import base64
import hashlib
import json
import os
import secrets
import sys

try:
    import hexchat  # type: ignore
except ImportError:
    sys.stderr.write("hexdroid_agm: this script must be loaded by HexChat.\n")
    sys.exit(1)

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    hexchat.prnt(
        "\x0304[hexdroid_agm]\x03 The 'cryptography' Python package is required. "
        "Install with:  pip install cryptography"
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
    only the current user can read the key bytes; on Windows the chmod is a
    no-op and the file inherits the user-profile ACL which is also restrictive
    enough for the use case.
    """
    tmp = KEYFILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(d, fh, indent=2, sort_keys=True)
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        # Windows doesn't honour POSIX modes; ignore.
        pass
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
    conversations). `aad_context` is the canonical conversation id from
    _aad_context().
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
        return pt.decode("utf-8", errors="replace")
    except Exception:
        return None


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


# Outbound: channel message intercept.
#
# We use a "Your Message" print hook AND a raw outgoing hook in different code
# paths because HexChat's plugin API has separate paths for "channel message"
# vs "private message". For simplicity we intercept the high-level command
# instead of the wire-level PRIVMSG (which would also catch ourselves re-
# sending, creating an infinite loop).

def _on_send_message(word, word_eol, userdata):
    """Triggered when the user types a message in a channel or query. We replace
    the outgoing message with an encrypted version when a key is configured for
    the conversation.
    """
    # word[0] = the cleartext message the user typed.
    cleartext = word[0] if word else ""
    # Don't re-encrypt our own re-sent ciphertext (the hexchat.command("msg ...")
    # below can re-fire this event); without this guard we'd double-encrypt.
    if cleartext.startswith(WIRE_PREFIX):
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
        _print_status(f"Failed to encrypt: {e}")
        return hexchat.EAT_NONE
    # Send the encrypted line; HexChat will display the cleartext locally via
    # the manual emit_print below so the user sees their own message readable.
    hexchat.command(f"msg {target} {ct}")
    # Display the cleartext locally with a lock prefix so the user sees what
    # they sent, the same way HexDroid renders local echo with a padlock.
    hexchat.emit_print("Your Message", my_nick, "\u00033[+AGM]\u0003 " + cleartext)
    return hexchat.EAT_ALL


def _on_send_action(word, word_eol, userdata):
    """Triggered by /me. Same logic as _on_send_message but with CTCP ACTION
    framing - the CTCP envelope (\x01ACTION ...\x01) stays cleartext, only
    the inner text is encrypted, matching what HexDroid does.
    """
    text = word_eol[1] if len(word_eol) > 1 else ""
    if text.startswith(WIRE_PREFIX):
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
        _print_status(f"Failed to encrypt /me: {e}")
        return hexchat.EAT_NONE
    # The "me" command in HexChat sends \x01ACTION ...\x01 which the encrypt
    # would wrap whole. Instead send a raw CTCP that keeps the framing clear
    # and only encrypts the inner text. Receivers (HexDroid and this plugin)
    # do the same unwrap.
    hexchat.command(f"raw PRIVMSG {target} :\x01ACTION {ct}\x01")
    hexchat.emit_print("Your Action", my_nick, "\u00033[+AGM]\u0003 " + text)
    return hexchat.EAT_ALL


def _decrypt_inbound(word, evt_name):
    """Common path for inbound message hooks. Returns either:
      (decrypted_text, original_nick) if decryption succeeded, OR
      None to indicate "let HexChat handle this normally" (no +AGM prefix
      seen, or no key configured for this channel, or decrypt failed
      in the last case we leave the +AGM line visible so the user can
      copy/paste it for diagnosis).
    """
    nick = word[0] if len(word) > 0 else ""
    text = word[1] if len(word) > 1 else ""
    if not text.startswith(WIRE_PREFIX):
        return None
    network = _current_network()
    channel = _current_channel()
    # The key is stored under the conversation's "other side": the channel name
    # for channel messages, the sender's nick for queries.
    key_target = channel if _is_channel(channel) else nick
    key = _get_key(network, key_target)
    if not key:
        return None
    # AAD is the canonical conversation id, which for a query is the sorted pair
    # {my nick, sender nick} - NOT the bare sender nick (that asymmetry was the
    # bug that made every query fail to decrypt).
    my_nick = hexchat.get_info("nick") or ""
    pt = _decrypt(text, key, _aad_context(my_nick, key_target))
    if pt is None:
        # Decrypt failed despite the prefix and a configured key. Surface a
        # warning but leave the original line visible.
        _print_status(f"Decrypt failed for message from {nick} in {key_target}")
        return None
    return pt, nick


def _on_recv_message(word, word_eol, userdata):
    r = _decrypt_inbound(word, "Channel Message")
    if r is None:
        return hexchat.EAT_NONE
    pt, nick = r
    hexchat.emit_print("Channel Message", nick, "\u00033[+AGM]\u0003 " + pt, *word[2:])
    return hexchat.EAT_ALL


def _on_recv_action(word, word_eol, userdata):
    r = _decrypt_inbound(word, "Channel Action")
    if r is None:
        return hexchat.EAT_NONE
    pt, nick = r
    hexchat.emit_print("Channel Action", nick, "\u00033[+AGM]\u0003 " + pt, *word[2:])
    return hexchat.EAT_ALL


def _on_recv_priv_message(word, word_eol, userdata):
    r = _decrypt_inbound(word, "Private Message")
    if r is None:
        return hexchat.EAT_NONE
    pt, nick = r
    hexchat.emit_print("Private Message", nick, "\u00033[+AGM]\u0003 " + pt)
    return hexchat.EAT_ALL


def _on_recv_priv_message_dialog(word, word_eol, userdata):
    r = _decrypt_inbound(word, "Private Message to Dialog")
    if r is None:
        return hexchat.EAT_NONE
    pt, nick = r
    hexchat.emit_print("Private Message to Dialog", nick, "\u00033[+AGM]\u0003 " + pt)
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
        # Add padding tolerance.
        padded = b64 + "=" * ((4 - len(b64) % 4) % 4)
        raw = base64.b64decode(padded, validate=False)
    except Exception:
        _print_status("Not a valid base64 key.")
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

hexchat.hook_print("Your Message",   _on_send_message)
hexchat.hook_print("Your Action",    _on_send_action)
hexchat.hook_print("Channel Message", _on_recv_message)
hexchat.hook_print("Channel Action",  _on_recv_action)
hexchat.hook_print("Channel Msg Hilight", _on_recv_message)
hexchat.hook_print("Channel Action Hilight", _on_recv_action)
hexchat.hook_print("Private Message", _on_recv_priv_message)
hexchat.hook_print("Private Message to Dialog", _on_recv_priv_message_dialog)

hexchat.hook_command("AGM",       _cmd_agm,       help="/AGM                       [show AGM plugin usage]")
hexchat.hook_command("AGM-GEN",   _cmd_agm_gen,   help="/AGM-GEN [target]          [generate a new key]")
hexchat.hook_command("AGM-SET",   _cmd_agm_set,   help="/AGM-SET <target> <base64> [install a key]")
hexchat.hook_command("AGM-CLEAR", _cmd_agm_clear, help="/AGM-CLEAR [target]        [remove a key]")
hexchat.hook_command("AGM-INFO",  _cmd_agm_info,  help="/AGM-INFO [target]         [show key info]")
hexchat.hook_command("AGM-LIST",  _cmd_agm_list,  help="/AGM-LIST                  [list every configured key]")


def _on_unload(userdata):
    hexchat.prnt(f"\x0304[+AGM]\x03 {__module_name__} {__module_version__} unloaded.")


hexchat.hook_unload(_on_unload)
hexchat.prnt(
    f"\x0309[+AGM]\x03 {__module_name__} {__module_version__} loaded. "
    f"Type /AGM for usage. Keys stored in {KEYFILE}"
)
