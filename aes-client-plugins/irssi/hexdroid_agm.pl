# hexdroid_agm.pl
#
# End-to-end "+AGM" (AES-256-GCM) encryption for irssi.
#
# see the spec at https://github.com/boxlabss/HexDroid/tree/main/aes-client-plugins/docs/agm-wire-format.md
#
# Single-purpose: it only speaks the +AGM scheme. For Blowfish/FiSH (+OK) use a
# dedicated FiSH script.
#
# WIRE FORMAT (must match HexDroid byte-for-byte):
#   line       = "+AGM " . base64_nopad(payload)
#   payload    = chr(0x01) . nonce(12) . ciphertext . tag(16)        # AES-256-GCM
#   AAD        = canonical conversation id (lowercased, UTF-8):
#                  channel -> the channel name
#                  query   -> the two nicks, lowercased + sorted, joined with NUL
#   key        = 32 random bytes, shared out of band, base64 for transport
#
# DEPENDENCIES:
#   cpan Crypt::AuthEnc::GCM Crypt::PRNG     # both ship in the "CryptX" distribution
#   (Digest::SHA, MIME::Base64 and JSON::PP are core Perl modules.)
#
# COMMANDS:
#   /agm                        show usage
#   /agm-gen   [target]         generate a new key for target (default: active window)
#   /agm-set   <target> <b64>   install a key you received out of band
#   /agm-clear [target]         remove the key for target
#   /agm-info  [target]         show the safety number (fingerprint) for target
#   /agm-list                   list every configured key

use strict;
use warnings;

use Irssi;
use Fcntl    qw(O_WRONLY O_CREAT O_EXCL O_TRUNC);
use MIME::Base64 qw(encode_base64 decode_base64);
use Digest::SHA  qw(sha256);
use JSON::PP     ();
use Crypt::AuthEnc::GCM qw(gcm_encrypt_authenticate gcm_decrypt_verify);
use Crypt::PRNG         qw(random_bytes);

our $VERSION = '1.0.2';
our %IRSSI = (
    authors     => 'boxlabs',
    name        => 'hexdroid_agm',
    description => 'End-to-end AES-256-GCM (+AGM) encryption.',
    license     => 'GPL-3.0-or-later',
    url         => 'https://hexdroid.boxlabs.uk/',
);

# --------------------------------------------------------------------------- #
# Constants

use constant {
    WIRE_PREFIX  => '+AGM ',   # any text starting with this is a +AGM payload
    VERSION_BYTE => "\x01",    # single byte 0x01
    NONCE_LEN    => 12,        # 96-bit GCM nonce
    TAG_LEN      => 16,        # 128-bit GCM tag
    KEY_LEN      => 32,        # AES-256
};

my $KEYFILE = Irssi::get_irssi_dir() . '/agm_keys.json';

# In-memory key store: { network_tag => { target_lc => base64_key } }
my %keys;

# --------------------------------------------------------------------------- #
# Small helpers

sub _status {
    my ($msg) = @_;
    my $win = Irssi::active_win();
    $win->print("\00309[+AGM]\003 $msg", MSGLEVEL_CLIENTCRAP) if $win;
}

sub _is_channel {
    my ($t) = @_;
    return 0 unless defined $t && length $t;
    my $c = substr($t, 0, 1);
    return ($c eq '#' || $c eq '&' || $c eq '+' || $c eq '!') ? 1 : 0;
}

# Standard (RFC 4648) base64 without trailing '=' padding, no line breaks - this
# is exactly what HexDroid emits and what the HexChat plugin emits.
sub _b64_encode_nopad {
    my ($bytes) = @_;
    my $b64 = encode_base64($bytes, '');   # '' = no line ending
    $b64 =~ s/=+\z//;                       # strip padding
    return $b64;
}

# Accept both padded and unpadded base64 (re-add padding before decoding).
sub _b64_decode_any {
    my ($b64) = @_;
    $b64 =~ s/\s+//g;
    $b64 .= '=' x ((4 - length($b64) % 4) % 4);
    return decode_base64($b64);
}

# --------------------------------------------------------------------------- #
# Key store (JSON on disk)

sub _load_keys {
    %keys = ();
    open(my $fh, '<', $KEYFILE) or return;   # no file yet is fine
    local $/;
    my $raw = <$fh>;
    close($fh);
    return unless defined $raw && length $raw;
    my $obj = eval { JSON::PP->new->utf8->decode($raw) };
    return unless ref $obj eq 'HASH';
    # Sanitise: keep only entries that decode to exactly 32 bytes.
    for my $net (keys %$obj) {
        next unless ref $obj->{$net} eq 'HASH';
        for my $tgt (keys %{ $obj->{$net} }) {
            my $b64 = $obj->{$net}{$tgt};
            next unless defined $b64 && !ref $b64;
            my $raw_key = eval { _b64_decode_any($b64) };
            next unless defined $raw_key && length($raw_key) == KEY_LEN;
            $keys{$net}{ lc $tgt } = _b64_encode_nopad($raw_key);
        }
    }
}

sub _save_keys {
    my $json = JSON::PP->new->utf8->canonical->pretty->encode(\%keys);
    my $tmp  = "$KEYFILE.tmp";
    # Create with mode 0600 from the start via O_CREAT|O_EXCL, so the key file is never
    # briefly group/other-readable between open and a later chmod, and a pre-planted
    # symlink at the tmp path can't redirect the write.
    unlink($tmp);
    if (sysopen(my $fh, $tmp, O_WRONLY | O_CREAT | O_EXCL | O_TRUNC, 0600)) {
        print {$fh} $json;
        close($fh);
        rename($tmp, $KEYFILE) or _status("Could not replace key file: $!");
    } else {
        _status("Could not write key file: $!");
    }
}

sub _get_key_raw {
    my ($net, $target) = @_;
    return undef unless defined $net && defined $target;
    my $b64 = $keys{$net} ? $keys{$net}{ lc $target } : undef;
    return undef unless $b64;
    my $raw = eval { _b64_decode_any($b64) };
    return (defined $raw && length($raw) == KEY_LEN) ? $raw : undef;
}

sub _set_key_raw {
    my ($net, $target, $raw) = @_;
    die "key must be " . KEY_LEN . " bytes\n" unless length($raw) == KEY_LEN;
    die "no active network\n" unless defined $net && length $net;
    $keys{$net}{ lc $target } = _b64_encode_nopad($raw);
    _save_keys();
}

sub _clear_key {
    my ($net, $target) = @_;
    return 0 unless $net && $keys{$net} && exists $keys{$net}{ lc $target };
    delete $keys{$net}{ lc $target };
    delete $keys{$net} unless %{ $keys{$net} };
    _save_keys();
    return 1;
}

# --------------------------------------------------------------------------- #
# Crypto

# Canonical conversation id used as AAD. $target is the conversation's "other
# side": the channel name for channels, the peer's nick for queries. $self_nick
# is our own current nick (only used for queries).
sub _aad_context {
    my ($target, $self_nick) = @_;
    return lc($target) if _is_channel($target);
    my @pair = sort(lc($self_nick), lc($target));   # unordered {me, peer}
    return join("\x00", @pair);
}

sub _encrypt {
    my ($plaintext, $key, $aad) = @_;
    my $nonce = random_bytes(NONCE_LEN);
    # gcm_encrypt_authenticate returns (ciphertext, tag); tag is 16 bytes.
    my ($ct, $tag) = gcm_encrypt_authenticate('AES', $key, $nonce, $aad, $plaintext);
    my $payload = VERSION_BYTE . $nonce . $ct . $tag;
    return WIRE_PREFIX . _b64_encode_nopad($payload);
}

# Returns plaintext, or undef on any failure (bad b64, short, wrong version,
# bad tag i.e. wrong key / tamper / replay into another conversation).
sub _decrypt {
    my ($wire, $key, $aad) = @_;
    return undef unless index($wire, WIRE_PREFIX) == 0;
    my $b64 = substr($wire, length(WIRE_PREFIX));
    $b64 =~ s/^\s+//; $b64 =~ s/\s+$//;
    my $raw = eval { _b64_decode_any($b64) };
    return undef unless defined $raw;
    return undef if length($raw) < 1 + NONCE_LEN + TAG_LEN;
    return undef unless substr($raw, 0, 1) eq VERSION_BYTE;

    my $nonce   = substr($raw, 1, NONCE_LEN);
    my $tag     = substr($raw, -TAG_LEN);
    my $ct      = substr($raw, 1 + NONCE_LEN, length($raw) - 1 - NONCE_LEN - TAG_LEN);

    my $pt = eval { gcm_decrypt_verify('AES', $key, $nonce, $aad, $ct, $tag) };
    return undef unless defined $pt;   # auth failure returns undef
    # Replay guard. Checked only after the tag verifies. A
    # repeated (key, conversation, nonce) means the ciphertext was re-injected into the
    # same conversation. Drop the duplicate.
    return undef if _replay_seen($key, $aad, $nonce);
    return $pt;
}

# Bounded FIFO window of authenticated (key, conversation, nonce) triples, namespaced by
# a short key digest so distinct keys keep independent histories. In-memory and
# session-scoped. Every AGM nonce is a fresh random 96-bit value, so a repeat is a
# re-injected ciphertext, never a coincidence.
my $REPLAY_CACHE_MAX = 2048;
my %SEEN_NONCE;     # replay-key string -> 1
my @SEEN_ORDER;     # FIFO of replay-key strings, for bounded eviction
sub _replay_seen {
    my ($key, $aad, $nonce) = @_;
    my $rk = substr(sha256($key), 0, 8) . "\x00" . $aad . "\x00" . $nonce;
    return 1 if exists $SEEN_NONCE{$rk};
    $SEEN_NONCE{$rk} = 1;
    push @SEEN_ORDER, $rk;
    if (@SEEN_ORDER > $REPLAY_CACHE_MAX) {
        my $old = shift @SEEN_ORDER;
        delete $SEEN_NONCE{$old};
    }
    return 0;
}

# Safety number: SHA-256(0x00 || key), first 40 bits, Crockford base32 (no
# 0/1/I/O) as 8 chars with a hyphen: "K4XR-T9BS".
my @CROCKFORD = split //, 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
sub _fingerprint {
    my ($key) = @_;
    my $d = sha256("\x00" . $key);
    my @b = unpack('C5', $d);          # first 5 bytes = 40 bits
    my $n = ($b[0] << 32) | ($b[1] << 24) | ($b[2] << 16) | ($b[3] << 8) | $b[4];
    my $out = '';
    for (my $i = 7; $i >= 0; $i--) {
        $out .= $CROCKFORD[ ($n >> ($i * 5)) & 0x1f ];
        $out .= '-' if $i == 4;
    }
    return $out;
}

# --------------------------------------------------------------------------- #
# Context helpers

sub _net_of  { my ($s) = @_; return ($s && $s->{tag}) ? $s->{tag} : undef; }
sub _nick_of { my ($s) = @_; return ($s && $s->{nick}) ? $s->{nick} : ''; }

# --------------------------------------------------------------------------- #
# Outbound: plain text typed into a channel/query window.
#
# 'send text' fires before the line is sent, so signal_stop() suppresses the
# cleartext send. We send the ciphertext with send_raw (no local echo) and print
# the plaintext ourselves. send_raw does NOT re-emit 'send text', so there is no
# loop.
sub sig_send_text {
    my ($line, $server, $witem) = @_;
    return unless $server && $witem;
    return unless $witem->{type} eq 'CHANNEL' || $witem->{type} eq 'QUERY';
    return if $line eq '' || index($line, WIRE_PREFIX) == 0;

    my $target = $witem->{name};
    my $key = _get_key_raw(_net_of($server), $target);
    return unless $key;   # no key -> let irssi send it as normal cleartext

    my $ct = eval { _encrypt($line, $key, _aad_context($target, _nick_of($server))) };
    if (!defined $ct) {
        # Encryption should never fail, but if it does, drop the line rather than
        # letting irssi fall through and send it in cleartext.
        _status("Encrypt failed, message NOT sent: $@");
        Irssi::signal_stop();
        return;
    }

    $server->send_raw("PRIVMSG $target :$ct");
    my $me = _nick_of($server);
    $witem->print("\00309[+AGM]\003 <$me> $line", MSGLEVEL_PUBLIC | MSGLEVEL_NOHILIGHT);
    Irssi::signal_stop();
}

# Outbound /me (and /action <target> ...). Only the inner text is encrypted; the
# CTCP ACTION envelope stays clear, matching HexDroid.
# Return contract: 1 = handled, caller must suppress the original /me (we either sent
# the encrypted action OR a key was configured but encryption failed. in both cases
# the cleartext must NOT go out); 0 = no key configured, caller lets irssi send the
# action in cleartext as normal.
sub _send_action {
    my ($server, $witem, $target, $text) = @_;
    my $key = _get_key_raw(_net_of($server), $target);
    return 0 unless $key;   # no key -> 0 tells caller to let irssi send cleartext

    my $ct = eval { _encrypt($text, $key, _aad_context($target, _nick_of($server))) };
    if (!defined $ct) {
        # A key IS configured but encryption failed: fail CLOSED. Return 1 so the caller
        # suppresses the original /me, dropping it rather than leaking the plaintext
        # action.
        _status("Encrypt /me failed, action NOT sent: $@");
        return 1;
    }

    $server->send_raw("PRIVMSG $target :\x01ACTION $ct\x01");
    my $me = _nick_of($server);
    my $disp = ($witem && ($witem->{type} eq 'CHANNEL' || $witem->{type} eq 'QUERY'))
        ? $witem : Irssi::active_win();
    $disp->print("\00309[+AGM]\003 * $me $text", MSGLEVEL_ACTIONS | MSGLEVEL_NOHILIGHT);
    return 1;
}

sub cmd_me {
    my ($data, $server, $witem) = @_;
    return unless $server && $witem;
    return unless $witem->{type} eq 'CHANNEL' || $witem->{type} eq 'QUERY';
    return if $data eq '' || index($data, WIRE_PREFIX) == 0;
    Irssi::signal_stop() if _send_action($server, $witem, $witem->{name}, $data);
}

sub cmd_action {
    my ($data, $server, $witem) = @_;
    return unless $server;
    my ($target, $text) = split(/\s+/, $data, 2);
    return unless defined $target && defined $text && length $text;
    return if index($text, WIRE_PREFIX) == 0;
    Irssi::signal_stop() if _send_action($server, $witem, $target, $text);
}

# --------------------------------------------------------------------------- #
# Inbound: replace a +AGM line with its plaintext, then let irssi display it
# normally via signal_continue. (signal_continue resumes the *other* handlers
# with the new args; it does not re-enter this one, so there is no loop.)

sub _inbound {
    my ($server, $msg, $key_target, $self_nick) = @_;
    return undef unless index($msg, WIRE_PREFIX) == 0;
    my $key = _get_key_raw(_net_of($server), $key_target);
    return undef unless $key;
    my $pt = _decrypt($msg, $key, _aad_context($key_target, $self_nick));
    return undef unless defined $pt;
    return "\00309[+AGM]\003 $pt";
}

sub sig_msg_public {
    my ($server, $msg, $nick, $address, $target) = @_;   # $target = channel
    my $pt = _inbound($server, $msg, $target, _nick_of($server));
    Irssi::signal_continue($server, $pt, $nick, $address, $target) if defined $pt;
}

sub sig_msg_private {
    my ($server, $msg, $nick, $address) = @_;            # conversation = sender nick
    my $pt = _inbound($server, $msg, $nick, _nick_of($server));
    Irssi::signal_continue($server, $pt, $nick, $address) if defined $pt;
}

sub sig_msg_action {
    my ($server, $msg, $nick, $address, $target) = @_;
    # For a channel action $target is the channel; for a private action it is our
    # own nick, so the conversation key is keyed under the sender.
    my $key_target = _is_channel($target) ? $target : $nick;
    my $pt = _inbound($server, $msg, $key_target, _nick_of($server));
    Irssi::signal_continue($server, $pt, $nick, $address, $target) if defined $pt;
}

# --------------------------------------------------------------------------- #
# Commands

sub _target_or_active {
    my ($arg, $witem) = @_;
    $arg =~ s/^\s+//; $arg =~ s/\s+$//;
    return $arg if length $arg;
    return ($witem && ($witem->{type} eq 'CHANNEL' || $witem->{type} eq 'QUERY'))
        ? $witem->{name} : undef;
}

sub cmd_agm {
    _status("Commands: /agm-gen [target], /agm-set <target> <base64>, "
          . "/agm-clear [target], /agm-info [target], /agm-list");
}

sub cmd_agm_gen {
    my ($data, $server, $witem) = @_;
    my $net = _net_of($server);
    unless ($net) { _status("Switch to a connected network window first."); return; }
    my $target = _target_or_active($data, $witem);
    unless ($target) { _status("Usage: /agm-gen <target>"); return; }
    my $raw = random_bytes(KEY_LEN);
    eval { _set_key_raw($net, $target, $raw); 1 } or do { _status("Failed: $@"); return; };
    my $share = _b64_encode_nopad($raw);
    _status("Generated AGM key for $target on $net.");
    _status("Share this with the other side out of band: $share");
    _status("Safety number: " . _fingerprint($raw));
}

sub cmd_agm_set {
    my ($data, $server, $witem) = @_;
    my $net = _net_of($server);
    unless ($net) { _status("Switch to a connected network window first."); return; }
    my ($target, $b64) = split(/\s+/, $data, 2);
    unless (defined $target && defined $b64 && length $b64) {
        _status("Usage: /agm-set <target> <base64-key>"); return;
    }
    # Strict charset check so a stray non-base64 character from a paste (a smart quote,
    # an accidental symbol) is reported instead of being silently dropped by the lenient
    # decoder into a wrong-but-plausible key. Whitespace is
    # tolerated and stripped, matching _b64_decode_any.
    (my $check = $b64) =~ s/\s+//g;
    unless ($check =~ m{\A[A-Za-z0-9+/]*={0,2}\z}) {
        _status("Key contains non-base64 characters - check for stray symbols or smart quotes."); return;
    }
    my $raw = eval { _b64_decode_any($b64) };
    unless (defined $raw) { _status("Not valid base64."); return; }
    unless (length($raw) == KEY_LEN) {
        _status("Expected " . KEY_LEN . " bytes after decode, got " . length($raw) . "."); return;
    }
    eval { _set_key_raw($net, $target, $raw); 1 } or do { _status("Failed: $@"); return; };
    _status("Installed AGM key for $target on $net. Safety number: " . _fingerprint($raw));
}

sub cmd_agm_clear {
    my ($data, $server, $witem) = @_;
    my $net = _net_of($server);
    my $target = _target_or_active($data, $witem);
    unless ($net && $target) { _status("Usage: /agm-clear <target>"); return; }
    _status(_clear_key($net, $target)
        ? "Cleared AGM key for $target on $net."
        : "No key was configured for $target on $net.");
}

sub cmd_agm_info {
    my ($data, $server, $witem) = @_;
    my $net = _net_of($server);
    my $target = _target_or_active($data, $witem);
    unless ($net && $target) { _status("Usage: /agm-info <target>"); return; }
    my $raw = _get_key_raw($net, $target);
    unless ($raw) { _status("No key for $target on $net."); return; }
    _status("AGM key for $target on $net. Safety number: " . _fingerprint($raw)
          . " (compare this with the other side over a trusted channel).");
}

sub cmd_agm_list {
    my $total = 0;
    for my $net (sort keys %keys) {
        for my $tgt (sort keys %{ $keys{$net} }) {
            my $raw = _get_key_raw($net, $tgt);
            next unless $raw;
            _status("$net / $tgt  ->  " . _fingerprint($raw));
            $total++;
        }
    }
    _status("No keys configured.") unless $total;
}

# --------------------------------------------------------------------------- #
# Registration

_load_keys();

Irssi::signal_add_first('send text',          'sig_send_text');
Irssi::signal_add_first('message public',     'sig_msg_public');
Irssi::signal_add_first('message private',    'sig_msg_private');
Irssi::signal_add_first('message irc action', 'sig_msg_action');

Irssi::command_bind('me',     'cmd_me');
Irssi::command_bind('action', 'cmd_action');

Irssi::command_bind('agm',       'cmd_agm');
Irssi::command_bind('agm-gen',   'cmd_agm_gen');
Irssi::command_bind('agm-set',   'cmd_agm_set');
Irssi::command_bind('agm-clear', 'cmd_agm_clear');
Irssi::command_bind('agm-info',  'cmd_agm_info');
Irssi::command_bind('agm-list',  'cmd_agm_list');

Irssi::print("\00309[+AGM]\003 hexdroid_agm $VERSION loaded. "
           . "Type /agm for usage. Keys: $KEYFILE", MSGLEVEL_CLIENTCRAP);
