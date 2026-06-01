# -*- coding: utf-8 -*-
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

WeeChat script for HexDroid's +AGM end-to-end encryption.

Adds AES-256-GCM encrypt/decrypt to WeeChat so desktop/terminal users can
interoperate with HexDroid mobile users (and with the HexChat and irssi AGM
plugins) running the same pre-shared key. The wire format, AAD construction,
safety numbers and base64 conventions all match those clients byte for byte.

This script is single-purpose: it only handles the +AGM scheme. For +OK
(Blowfish / FiSH), use a dedicated FiSH script.

Dependencies:
  - WeeChat built with Python support.
  - The 'cryptography' Python package (provides AES-256-GCM):
        pip install cryptography
    or your distro's python3-cryptography.

Commands (case-insensitive subcommands of /agm):
  /agm                          show usage
  /agm info  [target]           show key info for target (default: current buffer)
  /agm set   <target> <base64>  install a 32-byte AES-256-GCM key for target
  /agm gen   [target]           generate a fresh random key for target
  /agm clear [target]           remove the key for target
  /agm list                     list every configured key

Storage: <weechat_data_dir>/hexdroid_agm_keys.json, file mode 0600 on Unix.

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

import base64
import collections
import hashlib
import json
import os
import secrets

SCRIPT_NAME    = "hexdroid_agm"
SCRIPT_AUTHOR  = "boxlabs"
SCRIPT_VERSION = "1.0.0"
SCRIPT_LICENSE = "GPL-3.0-or-later"
SCRIPT_DESC    = "HexDroid +AGM (AES-256-GCM) end-to-end encryption support"

try:
    import weechat  # type: ignore
except ImportError:
    # Not running inside WeeChat. Nothing useful to do in a standalone
    # interpreter; re-raise so the import error is visible.
    raise

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    _HAVE_CRYPTO = True
except ImportError:
    _HAVE_CRYPTO = False


# --------------------------------------------------------------------------- #
# Constants

WIRE_PREFIX  = "+AGM "       # any text starting with this is a +AGM payload
VERSION_BYTE = 0x01
NONCE_LEN    = 12            # 96-bit GCM nonce
TAG_LEN      = 16            # 128-bit GCM tag
KEY_LEN      = 32            # AES-256

# Conservative budget (bytes) for the content of an outgoing "PRIVMSG <t> :..."
# line, leaving headroom for the server-added ":nick!user@host " prefix when the
# message is relayed so recipients get the whole line. Messages longer than this
# (once encrypted) are split into multiple +AGM lines, each with its own nonce.
WIRE_BUDGET = 400

# mIRC-coloured marker prepended to decrypted text and printed in status lines.
# \x0303 = green, \x0f = reset. WeeChat renders IRC colour codes.
MARKER = "\x0303[+AGM]\x0f "

# Reentrancy guard for the outbound modifier when we re-send chunks ourselves.
_sending = False


# --------------------------------------------------------------------------- #
# Small helpers

def _info(name, arg=""):
    return weechat.info_get(name, arg)


def _data_dir():
    # weechat_data_dir exists on WeeChat >= 3.2; fall back to weechat_dir.
    d = _info("weechat_data_dir") or _info("weechat_dir")
    return d or os.path.expanduser("~/.weechat")


KEYFILE = os.path.join(_data_dir(), "hexdroid_agm_keys.json")


def _is_channel(name):
    return bool(name) and name[0] in "#&+!"


def _print_status(buffer, msg):
    weechat.prnt(buffer or "", MARKER + msg)


def _b64_decode_any(b64):
    """Accept both padded and unpadded standard base64."""
    b64 = "".join(b64.split())
    b64 += "=" * ((4 - len(b64) % 4) % 4)
    return base64.b64decode(b64, validate=False)


def _b64_encode_nopad(raw):
    """Standard RFC-4648 base64, no padding, no line breaks - exactly what
    HexDroid and the HexChat/irssi plugins emit."""
    return base64.b64encode(raw).decode("ascii").rstrip("=")


# --------------------------------------------------------------------------- #
# Key storage. JSON file mapping network/server -> { target_lower: base64_key }.

def _load_keys():
    """Read the on-disk keystore as { server: { target_lc: base64_key } }.
    Returns {} on any error - we never crash here, because that would render
    every encrypted conversation unreadable."""
    if not os.path.exists(KEYFILE):
        return {}
    try:
        with open(KEYFILE, "r", encoding="utf-8") as fh:
            obj = json.load(fh)
        if not isinstance(obj, dict):
            return {}
        out = {}
        for net, targets in obj.items():
            if not isinstance(targets, dict):
                continue
            clean = {}
            for tgt, b64 in targets.items():
                if not isinstance(b64, str):
                    continue
                try:
                    raw = _b64_decode_any(b64)
                except Exception:
                    continue
                if len(raw) != KEY_LEN:
                    continue
                clean[tgt.lower()] = _b64_encode_nopad(raw)
            if clean:
                out[net] = clean
        return out
    except Exception:
        return {}


def _save_keys(d):
    """Write the keystore back to disk, mode 0600 on Unix. Atomic via a
    temp file + os.replace, created O_EXCL|0600 so it is never briefly
    world-readable and a pre-planted symlink can't redirect the write."""
    tmp = KEYFILE + ".tmp"
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_EXCL
    try:
        fd = os.open(tmp, flags, 0o600)
    except FileExistsError:
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
    if not network or not target:
        return None
    b64 = _keys.get(network, {}).get(target.lower())
    if not b64:
        return None
    try:
        return _b64_decode_any(b64)
    except Exception:
        return None


def _set_key(network, target, raw):
    if len(raw) != KEY_LEN:
        raise ValueError("AES-256-GCM key must be exactly %d bytes, got %d"
                         % (KEY_LEN, len(raw)))
    if not network:
        raise ValueError("Current network/server unknown; run this in a "
                         "channel or query buffer, or specify a target")
    _keys.setdefault(network, {})[target.lower()] = _b64_encode_nopad(raw)
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
# Wire format encode / decode. The only functions that touch the cipher.

def _aad_context(my_nick, target):
    """Canonical conversation identifier used as AAD.

    Channels: the channel name (lowercased) - identical for every participant.
    Queries:  the unordered pair {my_nick, peer_nick}, lowercased and sorted,
              joined with a NUL, so both endpoints compute the same bytes
              regardless of direction (binding to the bare per-side nick would
              make the two sides' AADs differ and every query would fail).
    Returns a str; callers lowercase+encode it in _encrypt/_decrypt.
    """
    if _is_channel(target):
        return target.lower()
    a, b = sorted([my_nick.lower(), target.lower()])
    return a + "\x00" + b


def _encrypt(plaintext, key, aad_context):
    """Encrypt plaintext (str) and return the full wire line, e.g.
       "+AGM AQID...base64...". aad_context is from _aad_context()."""
    nonce = secrets.token_bytes(NONCE_LEN)
    aesgcm = AESGCM(key)
    aad = aad_context.lower().encode("utf-8")
    ct = aesgcm.encrypt(nonce, plaintext.encode("utf-8"), aad)
    payload = bytes([VERSION_BYTE]) + nonce + ct
    return WIRE_PREFIX + _b64_encode_nopad(payload)


def _decrypt(wire, key, aad_context):
    """Decrypt a "+AGM <base64>" line. Returns the plaintext, or None on any
    failure (malformed payload, wrong key, tampered tag, cross-conversation
    replay, or a replayed/duplicate nonce within a conversation)."""
    if not wire.startswith(WIRE_PREFIX):
        return None
    b64 = wire[len(WIRE_PREFIX):].strip()
    try:
        raw = _b64_decode_any(b64)
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
    # Replay guard, only after the tag verifies. A repeated
    # (key, conversation, nonce) is a re-injected ciphertext - the AAD-bound
    # tag stops cross-conversation replay but not replay back into the SAME
    # conversation. Drop the duplicate.
    if _replay_seen(key, aad_context, nonce):
        return None
    return pt.decode("utf-8", errors="replace")


# Bounded LRU of authenticated (key, conversation, nonce) triples, namespaced
# by a short key digest so distinct keys keep independent histories. In-memory,
# session-scoped; OrderedDict gives O(1) LRU.
_REPLAY_CACHE_MAX = 2048
_seen_nonces = collections.OrderedDict()


def _replay_seen(key, aad_context, nonce):
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
# Fingerprint (safety number). Matches HexDroid's E2eFingerprint: SHA-256 over
# (scheme_byte || key), truncated to 40 bits, Crockford base32 with a hyphen at
# the midpoint: "K4XR-T9BS". scheme_byte is 0x00 for AGM.

_CROCKFORD = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # 32 chars, no 0/1/I/O


def _fingerprint(key):
    digest = hashlib.sha256(bytes([0x00]) + key).digest()
    n = ((digest[0] << 32) | (digest[1] << 24) | (digest[2] << 16)
         | (digest[3] << 8) | digest[4])
    out = []
    for i in range(7, -1, -1):
        out.append(_CROCKFORD[(n >> (i * 5)) & 0x1F])
        if i == 4:
            out.append("-")
    return "".join(out)


def _sanitize_for_recv(s):
    """Strip bytes that could break line/CTCP framing when the decrypted text
    is spliced back into an IRC line. A peer holds the shared key so they are
    authenticated - but authenticated is not trusted: a malicious peer could
    embed CR/LF to inject protocol lines, NUL to truncate, or \\x01 to forge a
    CTCP. Remove exactly those; display formatting (colour, bold, etc.) is
    harmless and preserved."""
    return s.translate({0x00: None, 0x0a: None, 0x0d: None, 0x01: None})


# --------------------------------------------------------------------------- #
# Plaintext chunking. Encryption inflates size, and if a single encrypted line
# exceeds the IRC limit WeeChat would split the base64 mid-string and the
# recipient could not decrypt either half. So we split the *plaintext* up front
# into pieces small enough that each encrypted line fits, per the spec.

def _max_plaintext_bytes(target, action):
    """Largest UTF-8 plaintext (bytes) whose encrypted +AGM line fits the
    budget for this target."""
    overhead = len("PRIVMSG ") + len(target) + len(" :") + 2  # +CRLF
    if action:
        overhead += len("\x01ACTION ") + len("\x01")
    overhead += len(WIRE_PREFIX)
    avail_b64 = WIRE_BUDGET - overhead
    if avail_b64 < 8:
        avail_b64 = 8
    avail_payload = (avail_b64 // 4) * 3            # base64 -> raw bytes
    return max(1, avail_payload - (1 + NONCE_LEN + TAG_LEN))


def _chunk_plaintext(text, max_bytes):
    """Split text into chunks each <= max_bytes when UTF-8 encoded, never
    splitting a multibyte character."""
    chunks = []
    cur = []
    cur_len = 0
    for ch in text:
        clen = len(ch.encode("utf-8"))
        if cur and cur_len + clen > max_bytes:
            chunks.append("".join(cur))
            cur, cur_len = [], 0
        cur.append(ch)
        cur_len += clen
    if cur:
        chunks.append("".join(cur))
    return chunks or [""]


# --------------------------------------------------------------------------- #
# IRC parsing helpers built on WeeChat's own parser.

def _parse(server, message):
    return weechat.info_get_hashtable(
        "irc_message_parse", {"message": message, "server": server})


def _my_nick(server):
    return _info("irc_nick", server) or ""


# --------------------------------------------------------------------------- #
# Outbound: encrypt in the irc_out1_privmsg modifier (fires before WeeChat
# splits the line). WeeChat has already echoed our plaintext locally, so the
# user sees what they typed while ciphertext goes on the wire. On any failure we
# fail CLOSED: drop the message (return "") and warn, never leak plaintext.

def modifier_out_privmsg(data, modifier, server, string):
    global _sending
    if _sending:
        return string  # our own re-sent chunk; pass through untouched
    if not _HAVE_CRYPTO:
        return string

    parsed = _parse(server, string)
    if (parsed.get("command") or "").upper() != "PRIVMSG":
        return string
    target = parsed.get("channel") or ""
    text = parsed.get("text") or ""
    if not target or not text:
        return string
    # Preserve any leading IRCv3 client tags (e.g. labeled-response) so we don't
    # strip them when rebuilding the line.
    tags_prefix = ""
    if string.startswith("@"):
        sp = string.find(" ")
        if sp != -1:
            tags_prefix = string[:sp + 1]
    if text.startswith(WIRE_PREFIX):
        return string  # already encrypted (defensive / loopback guard)

    # Distinguish a /me action from other CTCP. Only ACTION carries content we
    # encrypt; VERSION/PING/DCC/etc. pass through untouched.
    action = False
    inner = text
    if text.startswith("\x01ACTION ") and text.endswith("\x01"):
        action = True
        inner = text[len("\x01ACTION "):-1]
    elif text.startswith("\x01"):
        return string

    key = _get_key(server, target)
    if not key:
        return string  # no key for this target: send as normal cleartext

    my_nick = _my_nick(server) or "me"
    aad = _aad_context(my_nick, target)

    try:
        max_bytes = _max_plaintext_bytes(target, action)
        chunks = _chunk_plaintext(inner, max_bytes)
        lines = []
        for piece in chunks:
            ct = _encrypt(piece, key, aad)
            if action:
                lines.append("PRIVMSG %s :\x01ACTION %s\x01" % (target, ct))
            else:
                lines.append("PRIVMSG %s :%s" % (target, ct))
    except Exception as e:
        # Fail CLOSED: drop the message rather than transmit plaintext.
        _print_status("", "ENCRYPTION FAILED - message NOT sent: %s" % e)
        return ""

    # Common case: one line - just return it and let WeeChat send it.
    if len(lines) == 1:
        return tags_prefix + lines[0]

    # Multi-line: send every chunk ourselves via raw /quote (no local echo,
    # WeeChat already echoed the full plaintext once) and drop the original.
    _sending = True
    try:
        for line in lines:
            weechat.command("", "/quote -server %s %s" % (server, line))
    finally:
        _sending = False
    return ""


# --------------------------------------------------------------------------- #
# Inbound: decrypt in the irc_in2_privmsg modifier and hand the plaintext back
# to WeeChat's native pipeline (highlights, logging, notifications) by
# rewriting just the text portion of the line, preserving any IRCv3 tags.

def modifier_in_privmsg(data, modifier, server, string):
    if not _HAVE_CRYPTO:
        return string

    parsed = _parse(server, string)
    if (parsed.get("command") or "").upper() != "PRIVMSG":
        return string
    target = parsed.get("channel") or ""   # channel, or our nick for a query
    sender = parsed.get("nick") or ""
    text = parsed.get("text") or ""
    try:
        pos_text = int(parsed.get("pos_text", "-1"))
    except (TypeError, ValueError):
        pos_text = -1
    if pos_text < 0 or not text:
        return string

    # CTCP ACTION (/me) carries the payload inside \x01ACTION ...\x01. Any other
    # CTCP is never ours - leave it for WeeChat.
    action = False
    inner = text
    if text.startswith("\x01ACTION ") and text.endswith("\x01"):
        action = True
        inner = text[len("\x01ACTION "):-1]
    elif text.startswith("\x01"):
        return string

    if not inner.startswith(WIRE_PREFIX):
        return string  # not an AGM line - normal message

    # Key lives under the conversation's "other side": the channel for channel
    # messages, the sender's nick for a query (target == us).
    key_target = target if _is_channel(target) else sender
    key = _get_key(server, key_target)
    if not key:
        return string  # no key: leave the +AGM line visible

    my_nick = _my_nick(server)
    pt = _decrypt(inner, key, _aad_context(my_nick, key_target))
    if pt is None:
        # Leave the raw +AGM line visible for diagnosis, plus a hint.
        weechat.prnt("", "%sdecrypt failed from %s in %s (wrong key or tampered)"
                     % (MARKER, sender, key_target))
        return string

    safe = _sanitize_for_recv(pt)
    if action:
        new_text = "\x01ACTION " + MARKER + safe + "\x01"
    else:
        new_text = MARKER + safe
    return string[:pos_text] + new_text


# --------------------------------------------------------------------------- #
# Status bar item: shows [+AGM] in the current buffer when a key is configured.

def bar_item_agm(data, item, window):
    buffer = weechat.window_get_pointer(window, "buffer") if window else \
        weechat.current_buffer()
    if not buffer:
        return ""
    server = weechat.buffer_get_string(buffer, "localvar_server")
    target = weechat.buffer_get_string(buffer, "localvar_channel")
    if server and target and _get_key(server, target):
        return weechat.color("green") + "[+AGM]" + weechat.color("reset")
    return ""


def signal_buffer_switch(data, signal, signal_data):
    weechat.bar_item_update("agm")
    return weechat.WEECHAT_RC_OK


# --------------------------------------------------------------------------- #
# Command: /agm <subcommand> ...

def _buffer_target(buffer):
    server = weechat.buffer_get_string(buffer, "localvar_server")
    target = weechat.buffer_get_string(buffer, "localvar_channel")
    return server, target


def _usage(buffer):
    _print_status(buffer,
                  "Usage: /agm gen [target] | set <target> <base64> | "
                  "clear [target] | info [target] | list")


def command_agm(data, buffer, args):
    if not _HAVE_CRYPTO:
        _print_status(buffer,
                      "The 'cryptography' Python package is required but not "
                      "installed. Install it (pip install cryptography) and "
                      "reload: /script reload " + SCRIPT_NAME)
        return weechat.WEECHAT_RC_OK

    parts = args.split(None, 2) if args else []
    sub = parts[0].lower() if parts else ""
    server, cur_target = _buffer_target(buffer)

    if sub in ("", "help"):
        _usage(buffer)
        return weechat.WEECHAT_RC_OK

    if sub == "list":
        if not _keys:
            _print_status(buffer, "No AGM keys configured anywhere.")
            return weechat.WEECHAT_RC_OK
        for net in sorted(_keys.keys()):
            for tgt in sorted(_keys[net].keys()):
                raw = _get_key(net, tgt)
                if raw:
                    _print_status(buffer, "  %s : %s  (%s)"
                                  % (net, tgt, _fingerprint(raw)))
        return weechat.WEECHAT_RC_OK

    if sub == "info":
        target = parts[1] if len(parts) > 1 else cur_target
        if not server or not target:
            _print_status(buffer, "Usage: /agm info [target] (run in a "
                          "channel/query or specify one)")
            return weechat.WEECHAT_RC_OK
        raw = _get_key(server, target)
        if not raw:
            _print_status(buffer, "No AGM key for %s on %s." % (target, server))
        else:
            _print_status(buffer, "%s on %s:  AES-256-GCM  safety number=%s"
                          % (target, server, _fingerprint(raw)))
        return weechat.WEECHAT_RC_OK

    if sub == "gen":
        target = parts[1] if len(parts) > 1 else cur_target
        if not server or not target:
            _print_status(buffer, "Usage: /agm gen [target] (run in a "
                          "channel/query or specify one)")
            return weechat.WEECHAT_RC_OK
        raw = secrets.token_bytes(KEY_LEN)
        try:
            _set_key(server, target, raw)
        except Exception as e:
            _print_status(buffer, "Failed to store key: %s" % e)
            return weechat.WEECHAT_RC_OK
        b64 = _b64_encode_nopad(raw)
        _print_status(buffer, "Generated AGM key for %s on %s." % (target, server))
        _print_status(buffer, "Safety number: %s" % _fingerprint(raw))
        _print_status(buffer, "On the other device run:  /agm set %s %s"
                      % (target, b64))
        _print_status(buffer, "(Verify the safety number matches before "
                      "sending anything sensitive.)")
        weechat.bar_item_update("agm")
        return weechat.WEECHAT_RC_OK

    if sub == "set":
        if len(parts) < 3:
            _print_status(buffer, "Usage: /agm set <target> <base64>")
            return weechat.WEECHAT_RC_OK
        target = parts[1]
        b64 = parts[2].strip()
        try:
            # Strict decode so a stray non-base64 char from a paste is reported
            # rather than silently stripped into a wrong-but-valid key.
            padded = b64 + "=" * ((4 - len(b64) % 4) % 4)
            raw = base64.b64decode(padded, validate=True)
        except Exception:
            _print_status(buffer, "Not valid base64 (contains non-base64 "
                          "characters).")
            return weechat.WEECHAT_RC_OK
        if len(raw) != KEY_LEN:
            _print_status(buffer, "Expected 32 bytes after base64 decode, got %d."
                          % len(raw))
            return weechat.WEECHAT_RC_OK
        try:
            _set_key(server, target, raw)
        except Exception as e:
            _print_status(buffer, "Failed to store key: %s" % e)
            return weechat.WEECHAT_RC_OK
        _print_status(buffer, "Installed AGM key for %s on %s. Safety number: %s"
                      % (target, server, _fingerprint(raw)))
        weechat.bar_item_update("agm")
        return weechat.WEECHAT_RC_OK

    if sub == "clear":
        target = parts[1] if len(parts) > 1 else cur_target
        if not server or not target:
            _print_status(buffer, "Usage: /agm clear [target]")
            return weechat.WEECHAT_RC_OK
        if _clear_key(server, target):
            _print_status(buffer, "Cleared AGM key for %s on %s." % (target, server))
        else:
            _print_status(buffer, "No key was configured for %s on %s."
                          % (target, server))
        weechat.bar_item_update("agm")
        return weechat.WEECHAT_RC_OK

    _print_status(buffer, "Unknown subcommand '%s'." % sub)
    _usage(buffer)
    return weechat.WEECHAT_RC_OK


# --------------------------------------------------------------------------- #
# Registration

if __name__ == "__main__":
    if weechat.register(SCRIPT_NAME, SCRIPT_AUTHOR, SCRIPT_VERSION,
                        SCRIPT_LICENSE, SCRIPT_DESC, "", ""):

        if not _HAVE_CRYPTO:
            weechat.prnt("", "%s%s: the 'cryptography' Python package is not "
                         "installed; encryption is disabled until you install "
                         "it (pip install cryptography) and reload."
                         % (MARKER, SCRIPT_NAME))

        weechat.hook_modifier("irc_out1_privmsg", "modifier_out_privmsg", "")
        weechat.hook_modifier("irc_in2_privmsg",  "modifier_in_privmsg",  "")

        weechat.hook_command(
            "agm",
            "HexDroid +AGM (AES-256-GCM) end-to-end encryption",
            "gen [<target>]"
            " || set <target> <base64>"
            " || clear [<target>]"
            " || info [<target>]"
            " || list",
            "      gen: generate a fresh random key for target (default: "
            "current buffer)\n"
            "      set: install a base64-encoded 32-byte key for target\n"
            "    clear: remove the key for target\n"
            "     info: show the safety number (fingerprint) for target\n"
            "     list: list every configured key across all networks\n\n"
            "Encryption is per (network, target). Share keys out of band and "
            "compare safety numbers before trusting a conversation.\n\n"
            "Examples:\n"
            "  /agm gen #secret\n"
            "  /agm set #secret AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n"
            "  /agm info\n"
            "  /agm list",
            "gen %(irc_channels)|%(nicks)"
            " || set %(irc_channels)|%(nicks)"
            " || clear %(irc_channels)|%(nicks)"
            " || info %(irc_channels)|%(nicks)"
            " || list",
            "command_agm", "")

        weechat.bar_item_new("agm", "bar_item_agm", "")
        weechat.hook_signal("buffer_switch", "signal_buffer_switch", "")

        weechat.prnt("", "%s%s %s loaded. Type /help agm for usage. "
                     "Keys: %s"
                     % (MARKER, SCRIPT_NAME, SCRIPT_VERSION, KEYFILE))
