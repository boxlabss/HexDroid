/*
* HexDroidIRC - An IRC Client for Android
* Copyright (C) 2026 boxlabs
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.boxlabs.hexdroid

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Character encoding handling for IRC connections: auto-detection of incoming text, per-network
 * encoding, and encoding and decoding for legacy (non-UTF-8) networks.
 */

/**
 * Maximum bytes accepted per line from the server. Generous (32 KiB) because bouncer replays with
 * stacked tags exceed the protocol limits; a longer line is discarded without closing the socket.
 */
private const val MAX_LINE_BYTES = 32768

object EncodingHelper {
    
    /**
     * Encodings tried during auto-detection. Earlier entries win ties; windows-1251 comes before
     * windows-1256 because their Cyrillic and Arabic ranges overlap.
     */
    val COMMON_ENCODINGS = listOf(
        "UTF-8",
        "windows-1251",   // Cyrillic (Russian, Bulgarian, Serbian, Ukrainian, etc.) - most common legacy IRC encoding
        "windows-1256",   // Arabic
        "ISO-8859-9",     // Turkish (Latin-5)
        "windows-1254",   // Turkish (Windows)
        "windows-1252",   // Western European (Latin)
        "ISO-8859-1",     // Latin-1
        "ISO-8859-15",    // Latin-9 (Latin-1 with Euro sign)
        "ISO-8859-2",     // Central European (Polish, Czech, etc.)
        "ISO-8859-6",     // Arabic (ISO)
        "KOI8-R",         // Russian (older encoding)
        "KOI8-U",         // Ukrainian
        "GB2312",         // Simplified Chinese
        "GBK",            // Simplified Chinese (extended)
        "GB18030",        // Simplified Chinese (full)
        "Big5",           // Traditional Chinese
        "Shift_JIS",      // Japanese
        "EUC-JP",         // Japanese (Unix)
        "ISO-2022-JP",    // Japanese (email/IRC)
        "EUC-KR",         // Korean
        "windows-874",    // Thai
        "TIS-620",        // Thai (ISO)
        "ISO-8859-7",     // Greek
        "windows-1253",   // Greek (Windows)
        "windows-1255",   // Hebrew
        "ISO-8859-8",     // Hebrew (ISO)
    )
    
    /**
     * User-friendly display names for encoding selection UI.
     */
    val ENCODING_DISPLAY_NAMES: Map<String, String> = linkedMapOf(
        "auto" to "Auto-detect (recommended)",
        "UTF-8" to "UTF-8 (Unicode)",
        "windows-1256" to "Windows-1256 (Arabic)",
        "ISO-8859-6" to "ISO-8859-6 (Arabic)",
        "ISO-8859-9" to "ISO-8859-9 (Turkish)",
        "windows-1254" to "Windows-1254 (Turkish)",
        "windows-1251" to "Windows-1251 (Cyrillic)",
        "windows-1252" to "Windows-1252 (Western European)",
        "ISO-8859-1" to "ISO-8859-1 (Latin-1)",
        "ISO-8859-15" to "ISO-8859-15 (Latin-9 / Euro)",
        "ISO-8859-2" to "ISO-8859-2 (Central European)",
        "KOI8-R" to "KOI8-R (Russian)",
        "KOI8-U" to "KOI8-U (Ukrainian)",
        "GB2312" to "GB2312 (Simplified Chinese)",
        "GBK" to "GBK (Chinese Extended)",
        "GB18030" to "GB18030 (Chinese Full)",
        "Big5" to "Big5 (Traditional Chinese)",
        "Shift_JIS" to "Shift_JIS (Japanese)",
        "EUC-JP" to "EUC-JP (Japanese Unix)",
        "ISO-2022-JP" to "ISO-2022-JP (Japanese)",
        "EUC-KR" to "EUC-KR (Korean)",
        "windows-874" to "Windows-874 (Thai)",
        "TIS-620" to "TIS-620 (Thai)",
        "ISO-8859-7" to "ISO-8859-7 (Greek)",
        "windows-1253" to "Windows-1253 (Greek)",
        "windows-1255" to "Windows-1255 (Hebrew)",
        "ISO-8859-8" to "ISO-8859-8 (Hebrew)",
    )
    
    /**
     * Get a Charset from a string name, with fallback to UTF-8.
     * Returns UTF-8 for "auto" mode as the initial encoding.
     */
    fun getCharset(name: String): Charset {
        if (name.isBlank() || name.equals("auto", ignoreCase = true)) {
            return Charsets.UTF_8
        }
        return runCatching { 
            Charset.forName(name) 
        }.getOrDefault(Charsets.UTF_8)
    }
    
    
    /**
     * Detect the encoding of [bytes]: valid UTF-8 first, otherwise the best-scoring candidate by
     * replacement characters, recognisable letters and control characters.
     */
    fun detectEncoding(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "UTF-8"

        // Short-circuit: if there are no bytes ≥ 0x80 the content is pure ASCII, which is
        // valid UTF-8 by definition.  Skipping the scoring loop for these lines avoids the
        // length-bonus false-positive that would otherwise cause windows-1251 to "win" on
        // long ASCII MOTD lines purely because it maps every byte without replacement chars.
        val hasHighBytes = bytes.any { it.toInt() and 0xFF >= 0x80 }
        if (!hasHighBytes) return "UTF-8"

        // First, check if it's valid UTF-8 (most IRC networks today use UTF-8)
        if (isValidUtf8(bytes)) return "UTF-8"
        
        // Try other common encodings and score them
        var bestEncoding = "UTF-8"
        var bestScore = Int.MIN_VALUE
        
        for (encoding in COMMON_ENCODINGS.drop(1)) { // Skip UTF-8, already checked
            val charset = runCatching { Charset.forName(encoding) }.getOrNull() ?: continue
            val score = scoreEncoding(bytes, charset)
            if (score > bestScore) {
                bestScore = score
                bestEncoding = encoding
            }
        }
        
        return bestEncoding
    }
    
    /**
     * Check if bytes are valid UTF-8 without replacement characters.
     */
    internal fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        
        return runCatching {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)
    }
    
    /**
     * Score how well [charset] decodes [bytes]; higher is better. windows-1251 and windows-1256 are
     * separated by the 0x80-0xBF range (Cyrillic supplement versus Arabic), and dense Latin
     * Extended is penalised so Cyrillic isn't read as ISO-8859-x.
     */
    internal fun scoreEncoding(bytes: ByteArray, charset: Charset): Int {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        
        val result = runCatching { 
            decoder.decode(ByteBuffer.wrap(bytes)) 
        }.getOrNull() ?: return Int.MIN_VALUE
        
        val text = result.toString()
        var score = 0
        
        // Heavily penalize replacement characters (U+FFFD)
        val replacements = text.count { it == '\uFFFD' }
        score -= replacements * 100
        
        // Bonus for recognized text patterns
        for (ch in text) {
            when {
                // ASCII letters/digits – expected in any IRC line (nicks, commands)
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' -> score += 1
                // Common IRC punctuation
                ch in " .,!?:;-_'\"()[]{}@#\$%&*+=/<>\\" -> score += 1
                // IRC formatting codes (expected in IRC text)
                ch.code in 0x02..0x1F -> score += 1
                // Standard line endings
                ch == '\n' || ch == '\r' -> { /* neutral */ }

                // Cyrillic supplement block U+0400–U+040F (Ё, Ђ, Ѓ, Є, Ї, Ј, Љ, Њ...).
                // windows-1251 maps 14 of its 0x80–0xBF bytes here; KOI8-R maps only one;
                // windows-1256 maps none (maps 0x80–0xBF to Arabic). So this range is the
                // primary discriminator between windows-1251 and windows-1256 on Cyrillic text.
                ch.code in 0x0400..0x040F -> score += 6  // Strong Cyrillic supplement bonus

                // Main Cyrillic block А–я U+0410–U+04FF.
                // Both windows-1251 (0xC0–0xFF) and windows-1256 (0xC0–0xD0) can produce
                // letters in this range, so the bonus is equal. Tie broken by list order
                // (windows-1251 listed first in COMMON_ENCODINGS).
                ch.code in 0x0410..0x04FF -> score += 4

                // Arabic (windows-1256, ISO-8859-6)
                ch.code in 0x0600..0x06FF -> score += 4
                ch.code in 0x0750..0x077F -> score += 4  // Arabic supplementary

                // Turkish-specific letters (İ ı Ğ ğ Ş ş) - unambiguous
                ch == '\u0130' || ch == '\u0131' || ch == '\u011E' || ch == '\u011F'
                    || ch == '\u015E' || ch == '\u015F' -> score += 6

                // Greek
                ch.code in 0x0370..0x03FF -> score += 4
                // Hebrew
                ch.code in 0x0590..0x05FF -> score += 4
                // Thai
                ch.code in 0x0E00..0x0E7F -> score += 4
                // CJK Unified Ideographs
                ch.code in 0x4E00..0x9FFF -> score += 4
                // Hiragana / Katakana
                ch.code in 0x3040..0x30FF -> score += 4
                // Hangul
                ch.code in 0xAC00..0xD7AF -> score += 4
                // Other printable non-ASCII that decoded cleanly
                ch.code > 0x7F && !ch.isISOControl() -> score += 2

                // C1 control characters (0x80–0x9F as bare code points) are almost always
                // the result of decoding with the wrong single-byte codepage.
                ch.code in 0x80..0x9F -> score -= 15
                // Other control characters (suspicious)
                ch.isISOControl() -> score -= 10
            }
        }

        // Latin Extended penalty: U+00C0–U+00FF (À–ÿ) decoded densely is a strong signal
        // that the encoding is Latin-based (ISO-8859-1/9/15, windows-1252/1254).  On a
        // Cyrillic server those same bytes (0xC0–0xFF) decode to Cyrillic А–я under
        // windows-1251 and score +4 each.  Penalising dense Latin Extended output widens
        // the gap so windows-1251 beats ISO-8859-9 on any corpus with Cyrillic content.
        // We only apply this when Latin Extended is the *dominant* non-ASCII script
        // (≥ 60% of non-ASCII chars), to avoid penalising legitimate Western European text.
        val nonAsciiChars = text.filter { it.code > 0x7F && !it.isISOControl() }
        if (nonAsciiChars.isNotEmpty()) {
            val latinExtCount = nonAsciiChars.count { it.code in 0x00C0..0x00FF }
            if (latinExtCount.toFloat() / nonAsciiChars.length >= 0.60f) {
                score -= latinExtCount * 2
            }
        }
        
        // Length bonus: only applies when there are actual non-ASCII printable chars,
        // to avoid spuriously rewarding any codepage for pure-ASCII lines.
        if (replacements == 0 && text.length > 10) {
            val nonAsciiCount = text.count { it.code > 0x7F && !it.isISOControl() }
            if (nonAsciiCount > 0) {
                score += (nonAsciiCount * 2).coerceAtMost(text.length / 5)
            }
        }

        // Density bonus for CJK and other multibyte codepages when over 30% of bytes are >= 0x80.
        // Single-byte Latin encodings are excluded: Cyrillic in windows-1251 is also high-density
        // and must score as Cyrillic, not Latin.
        val csName = charset.name().uppercase()
        val highByteCount = bytes.count { it.toInt() and 0xFF > 0x7F }
        if (highByteCount > 0) {
            val highRatio = highByteCount.toFloat() / bytes.size
            val isCjkDense = highRatio > 0.3f && (
                csName.contains("GB") || csName.contains("BIG5") ||
                csName.contains("SHIFT") || csName.contains("EUC")
            )
            if (isCjkDense) {
                score += (highByteCount * 2)
            }
        }
        
        return score
    }

    /**
     * Public alias for [scoreEncoding] so [EncodingLineReader] (same package) can call it
     * for corpus-level scoring without duplicating the logic.
     */
    internal fun scoreEncodingPublic(bytes: ByteArray, charset: java.nio.charset.Charset): Int =
        scoreEncoding(bytes, charset)

    /**
     * Decode bytes with auto-detection or specified encoding.
     * 
     * @param bytes Raw bytes to decode
     * @param preferredEncoding Encoding to use, or "auto" for detection
     * @return Pair of (decoded text, actual encoding used)
     */
    internal fun decode(bytes: ByteArray, preferredEncoding: String): Pair<String, String> {
        if (bytes.isEmpty()) return "" to "UTF-8"
        
        val actualEncoding = if (preferredEncoding.equals("auto", ignoreCase = true)) {
            detectEncoding(bytes)
        } else {
            preferredEncoding
        }
        
        val charset = getCharset(actualEncoding)
        val text = String(bytes, charset)
        return text to actualEncoding
    }
    
    /** Encode [text] in [encoding]; "auto" encodes as UTF-8. */
    fun encode(text: String, encoding: String): ByteArray {
        val actualEncoding = if (encoding.equals("auto", ignoreCase = true)) "UTF-8" else encoding
        val charset = getCharset(actualEncoding)
        return text.toByteArray(charset)
    }
    
    /**
     * Read one CRLF-terminated line from [input] and decode it.
     *
     * @param encoding Encoding to use, or "auto" to detect.
     * @param onTruncated Called once when a line over [MAX_LINE_BYTES] is dropped.
     * @return The line (null at EOF, "" for a dropped line) and the encoding used.
     */
    internal fun readLine(
        input: InputStream,
        encoding: String,
        autoDetect: Boolean = true,
        onTruncated: (() -> Unit)? = null
    ): Pair<String?, String> {
        val buffer = ByteArrayOutputStream(512)
        var b: Int
        
        while (true) {
            b = input.read()
            if (b == -1) {
                // EOF
                return if (buffer.size() > 0) {
                    val bytes = buffer.toByteArray()
                    if (autoDetect) {
                        decode(bytes, "auto")
                    } else {
                        String(bytes, getCharset(encoding)) to encoding
                    }
                } else {
                    null to encoding
                }
            }
            
            when (b) {
                '\n'.code -> break // End of line
                '\r'.code -> { /* Skip CR, wait for LF */ }
                else -> {
                    // cap line length to prevent OOM from a malicious/buggy server
                    // that sends an enormous line with no newline. IRC protocol allows
                    // 512 bytes (RFC 1459) or 8191 with message-tags (IRCv3); bouncers
                    // can legitimately exceed even that when replaying long-tagged lines.
                    // If the ceiling is hit we drain to the next LF and return an empty
                    // string so the caller skips this line but keeps the connection up.
                    if (buffer.size() >= MAX_LINE_BYTES) {
                        while (true) {
                            val skip = input.read()
                            if (skip == -1 || skip == '\n'.code) break
                        }
                        onTruncated?.invoke()
                        return "" to encoding
                    }
                    buffer.write(b)
                }
            }
        }
        
        val bytes = buffer.toByteArray()
        return if (bytes.isEmpty()) {
            "" to encoding
        } else if (autoDetect) {
            decode(bytes, "auto")
        } else {
            String(bytes, getCharset(encoding)) to encoding
        }
    }
    
}

/**
 * A line reader over an InputStream that detects and keeps the encoding. Lines with high bytes are
 * scored as a growing corpus; each line's winner gets a vote, and the encoding locks once it leads
 * by [LEAD_THRESHOLD] after [MIN_EVIDENCE_LINES]. Only a UTF-8 win resets the votes. ASCII lines
 * carry no evidence.
 */
class EncodingLineReader(
    private val input: InputStream,
    initialEncoding: String = "auto"
) {
    /** The committed encoding used to decode lines. Only changes on lock-commit or [setEncoding]. */
    private var currentEncoding: String = if (initialEncoding.equals("auto", ignoreCase = true)) {
        "UTF-8"
    } else {
        initialEncoding
    }

    private val autoDetect: Boolean = initialEncoding.equals("auto", ignoreCase = true)
    private var encodingLocked: Boolean = !autoDetect

    /**
     * When this reader was created. Detection stops after [DETECTION_TIMEOUT_MS], so a stray
     * non-ASCII line late in a session can't switch the decoding mid-stream.
     */
    private val createdAtMs: Long = System.currentTimeMillis()

    /** Maximum elapsed time (ms) during which auto-detection is active. After this, the
     *  reader locks to the current encoding (or UTF-8 if no lock has been committed yet). */
    private val DETECTION_TIMEOUT_MS = 5L * 60_000L  // 5 minutes

    /**
     * Minimum number of non-ASCII lines that must be seen before we consider locking.
     * Prevents locking on a single ambiguous registration line.
     */
    private val MIN_EVIDENCE_LINES = 4

    /**
     * The leading encoding's vote count must exceed second-place by at least this margin
     * before we commit.  A larger threshold tolerates more noise: one or two stray non-UTF-8
     * lines on a primarily-UTF-8 channel (a foreign-script nick joining, a single smart-quote
     * pasted from a Mac client) shouldn't be enough to flip the whole connection's decoding.
     */
    private val LEAD_THRESHOLD = 4

    /**
     * Hard cap on the corpus size (bytes).  We stop appending once this is reached to
     * bound memory use on chatty servers; the corpus is usually sufficient well before
     * this limit is hit.
     */
    private val MAX_CORPUS_BYTES = 8192

    /**
     * Maximum number of bytes accepted per line from the server.
     * RFC 1459 allows 512 bytes; IRCv3 with message-tags extends this to 8191.
     * Bouncers replaying buffered lines with stacked tags can legitimately push
     * past that, so we use a generous 32 KiB ceiling; anything over this is
     * treated as garbage and silently dropped (the socket is NOT closed).
     */
    private val MAX_LINE_BYTES = 32768

    /** Accumulated raw bytes from all non-ASCII lines seen so far during detection. */
    private val corpus = ByteArrayOutputStream(512)

    /** Per-encoding vote counts (number of non-ASCII lines on which each encoding won). */
    private val votes = mutableMapOf<String, Int>()

    /** Total non-ASCII lines seen (used for MIN_EVIDENCE_LINES guard). */
    private var evidenceLines = 0

    /**
     * Pre-resolved candidate encodings (excluding UTF-8) used by the scoring loop.
     * Cached here so the per-line readLine() doesn't allocate a new list and re-resolve
     * Charset.forName() on every line during the detection window.
     */
    private val candidateEncodings: List<Pair<String, Charset>> by lazy {
        EncodingHelper.COMMON_ENCODINGS.drop(1).mapNotNull { name ->
            runCatching { name to Charset.forName(name) }.getOrNull()
        }
    }

    /** The currently committed/used encoding. */
    val encoding: String get() = currentEncoding

    /**
     * Number of oversized lines discarded since this reader was constructed. The main
     * read loop can poll this after each [readLine] call to emit a one-shot user-visible
     * notice without spamming the status buffer. Counts drops from both the fast and
     * slow paths. Purely informational — does NOT affect connection state.
     */
    var truncatedLineCount: Int = 0
        private set

    /**
     * Read and decode the next line from the stream.
     * Returns null on EOF. Returns "" when either (a) the server sent an empty line,
     * or (b) a line was dropped for exceeding [MAX_LINE_BYTES]. Callers can distinguish
     * (b) by observing that [truncatedLineCount] increased across the call.
     */
    fun readLine(): String? {
        if (!autoDetect || encodingLocked) {
            // Fast path: locked or explicit encoding - read and decode directly.
            val (line, _) = EncodingHelper.readLine(
                input, currentEncoding, autoDetect = false,
                onTruncated = { truncatedLineCount++ }
            )
            return line
        }

        // Detection-window expiry: once DETECTION_TIMEOUT_MS has elapsed since this
        // reader was created we stop voting and commit to whatever the leader is. This
        // bounds the window in which a single foreign-script line can flip the
        // decoding for the whole session. If no encoding has accumulated a majority,
        // we fall back to UTF-8 (the safest default for any modern IRC network).
        if (System.currentTimeMillis() - createdAtMs > DETECTION_TIMEOUT_MS) {
            val leader = votes.maxByOrNull { it.value }?.key
            currentEncoding = if (leader != null && evidenceLines >= MIN_EVIDENCE_LINES) leader else "UTF-8"
            encodingLocked = true
            corpus.reset()
            votes.clear()
            // Fall through to the locked fast path on this call.
            val (line, _) = EncodingHelper.readLine(
                input, currentEncoding, autoDetect = false,
                onTruncated = { truncatedLineCount++ }
            )
            return line
        }

        // Detection path: read raw bytes first so we can inspect them before decoding.
        val buffer = ByteArrayOutputStream(512)
        var b: Int
        while (true) {
            b = input.read()
            if (b == -1) {
                // EOF - decode whatever we have and return.
                if (buffer.size() == 0) return null
                break
            }
            when (b) {
                '\n'.code -> break
                '\r'.code -> { /* skip CR */ }
                else -> {
                    // Mirror the static readLine's overflow policy: drain and drop
                    // rather than throw so oversized bouncer lines never kill the
                    // socket. Returning "" lets the caller skip this line cleanly.
                    if (buffer.size() >= MAX_LINE_BYTES) {
                        while (true) {
                            val skip = input.read()
                            if (skip == -1 || skip == '\n'.code) break
                        }
                        truncatedLineCount++
                        return ""
                    }
                    buffer.write(b)
                }
            }
        }
        if (b == -1 && buffer.size() == 0) return null

        val bytes = buffer.toByteArray()

        // Pure ASCII: encoding-neutral, skip vote accounting, decode as UTF-8.
        val hasHighBytes = bytes.any { it.toInt() and 0xFF >= 0x80 }
        if (!hasHighBytes) {
            return String(bytes, Charsets.UTF_8)
        }

        // Valid UTF-8 non-ASCII: genuine contradicting evidence - reset everything.
        if (EncodingHelper.isValidUtf8(bytes)) {
            corpus.reset()
            votes.clear()
            evidenceLines = 0
            return String(bytes, Charsets.UTF_8)
        }

        // Non-ASCII, non-UTF-8: add to corpus and score.
        if (corpus.size() < MAX_CORPUS_BYTES) {
            corpus.write(bytes)
        }
        evidenceLines++

        // Score the full corpus against all candidate encodings and find the winner.
        val corpusBytes = corpus.toByteArray()
        var bestEncoding = "windows-1251"   // safe non-UTF-8 default if all scores tie at 0
        var bestScore = Int.MIN_VALUE
        var secondScore = Int.MIN_VALUE

        for ((enc, charset) in candidateEncodings) {
            val score = EncodingHelper.scoreEncodingPublic(corpusBytes, charset)
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                bestEncoding = enc
            } else if (score > secondScore) {
                secondScore = score
            }
        }

        // Cast the vote for the corpus winner on this line.
        votes[bestEncoding] = (votes[bestEncoding] ?: 0) + 1

        // Determine the leading encoding by total votes.
        val leader = votes.maxByOrNull { it.value }?.key ?: bestEncoding
        val leaderVotes = votes[leader] ?: 0
        val secondVotes = votes.entries
            .filter { it.key != leader }
            .maxOfOrNull { it.value } ?: 0

        // Lock if we have enough evidence and a clear leader.
        if (evidenceLines >= MIN_EVIDENCE_LINES && leaderVotes - secondVotes >= LEAD_THRESHOLD) {
            currentEncoding = leader
            encodingLocked = true
            corpus.reset()
            votes.clear()
        }

        // Decode the current line with the leading candidate so it renders correctly
        // even before the lock commits.
        val decodeWith = if (votes.isNotEmpty()) leader else bestEncoding
        return String(bytes, EncodingHelper.getCharset(decodeWith))
    }

    /**
     * Returns true once auto-detection has locked onto a non-UTF-8 encoding,
     * or if a non-UTF-8 encoding was configured explicitly.
     */
    fun hasDetectedNonUtf8(): Boolean = currentEncoding != "UTF-8" && encodingLocked

    /**
     * Override the encoding, disabling auto-detection.
     * Passing "auto" leaves UTF-8 as the fixed encoding (re-enabling auto-detect
     * requires constructing a new [EncodingLineReader]).
     */
    fun setEncoding(encoding: String) {
        currentEncoding = if (encoding.equals("auto", ignoreCase = true)) "UTF-8" else encoding
        encodingLocked = true
        corpus.reset()
        votes.clear()
        evidenceLines = 0
    }
}