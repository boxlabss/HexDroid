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
 * Helper for handling character encodings in IRC connections.
 * 
 * IRC predates Unicode standardization, and many networks still use legacy encodings.
 * This helper provides:
 * - Auto-detection of incoming text encoding
 * - Per-network encoding configuration
 * - Proper encoding/decoding for non-UTF-8 networks
 * 
 * Common problematic networks include:
 * - Bulgarian networks (windows-1251)
 * - Russian networks (KOI8-R, windows-1251)
 * - Japanese networks (ISO-2022-JP, Shift_JIS)
 * - Chinese networks (GB2312, Big5)
 */

/**
 * Maximum bytes accepted per line from the server (applies to both read paths).
 * RFC 1459 allows 512 bytes; IRCv3 with message-tags extends this to 8191.
 * We use 8192 as a safe ceiling — anything larger is a protocol violation or
 * a runaway server bug that would cause unbounded heap growth.
 */
private const val MAX_LINE_BYTES = 8192

object EncodingHelper {
    
    /**
     * Common IRC encodings to try during auto-detection.
     * Order matters for tie-breaking: earlier = higher priority when scores are equal.
     * windows-1251 is listed before windows-1256 because Cyrillic IRC networks are far
     * more common globally, and the byte ranges overlap significantly (both map 0xC0-0xFF
     * to standard Cyrillic А-я under windows-1251, which windows-1256 also uses for Arabic
     * letters in the same byte range — so the per-character script bonus does the
     * discrimination, but order provides a safe tie-break).
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
     * Try to detect the encoding of a byte array.
     * 
     * Detection strategy:
     * 1. Check if valid UTF-8 (most common modern encoding)
     * 2. Score other encodings based on:
     *    - Presence of replacement characters (bad)
     *    - Presence of valid letters/words (good)
     *    - Control characters (bad, except CR/LF)
     * 3. Return the best-scoring encoding
     * 
     * @param bytes Raw bytes to analyze
     * @return Best-guess encoding name
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
     * Score an encoding based on how well it decodes the bytes.
     * Higher score = better match.
     *
     * Key design for Cyrillic vs Arabic discrimination:
     *
     * windows-1251 and windows-1256 both map bytes 0xC0–0xFF to standard Cyrillic А–я,
     * so text with only those bytes scores identically under both codepages.
     * The discriminating range is 0x80–0xBF:
     *
     *   windows-1251  0x80–0xBF → Cyrillic supplement letters (Ђ, Ѓ, Ё, Є, Ї…) → U+0400–U+040F (extra +2 bonus)
     *   windows-1256  0x80–0xBF → Arabic letters / presentation forms → U+0600–U+06FF (Arabic bonus)
     *   KOI8-R        0x80–0x9F → C1 control chars → penalised −15
     *
     * So on a line with only 0xC0–0xFF (common Cyrillic words), the scores tie and
     * list order (windows-1251 first) breaks the tie correctly.
     * On a line with 0x80–0xBF Cyrillic supplement bytes, windows-1251 pulls ahead.
     * On a line with Arabic letters, windows-1256 pulls ahead.
     *
     * Key design for Cyrillic vs Latin-family (ISO-8859-9, ISO-8859-1, windows-1252):
     *
     * The bytes 0xC0–0xFF decoded as ISO-8859-9 / ISO-8859-1 produce Latin Extended
     * characters (À–ÿ, with a few Turkish substitutions).  These land in U+00C0–U+00FF,
     * which is penalised below as "dense Latin Extended" — plausible in a French/German
     * MOTD line but statistically rare in IRC traffic compared to Cyrillic.  The penalty
     * widens the scoring gap so that corpus-level scoring cleanly picks windows-1251
     * over ISO-8859-9 once a few Cyrillic lines have been accumulated.
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

        // CJK/multibyte-specific density bonus: high-byte-density text is much more
        // likely CJK than Latin or Cyrillic, so reward CJK codepages when > 30% of bytes
        // are ≥ 0x80. Restricted to true multibyte / CJK codepages only.
        // Single-byte Latin-family encodings (ISO-8859-9, windows-1254, windows-1256, etc.)
        // are intentionally excluded: giving them a density bonus caused false-positive wins
        // over windows-1251 on Cyrillic text, where 0xC0–0xFF bytes are common (high density)
        // but should score as Cyrillic (+4/+6) not as Latin (+2 + density bonus).
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
    
    /**
     * Encode a string to bytes using the specified encoding.
     * 
     * For "auto" mode, uses UTF-8 for outbound messages (modern default).
     * 
     * @param text Text to encode
     * @param encoding Target encoding, or "auto" for UTF-8
     * @return Encoded bytes
     */
    fun encode(text: String, encoding: String): ByteArray {
        val actualEncoding = if (encoding.equals("auto", ignoreCase = true)) "UTF-8" else encoding
        val charset = getCharset(actualEncoding)
        return text.toByteArray(charset)
    }
    
    /**
     * Read a line from an InputStream with proper encoding handling.
     * IRC uses CRLF (\r\n) as line terminators.
     * 
     * @param input The input stream to read from
     * @param encoding The encoding to use for decoding, or "auto" for detection
     * @param autoDetect If true, run detection on each line
     * @return Pair of (decoded line or null if EOF, actual encoding used)
     */
    internal fun readLine(
        input: InputStream,
        encoding: String,
        autoDetect: Boolean = true
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
                    // 512 bytes (RFC 1459) or 8191 with message-tags (IRCv3).
                    if (buffer.size() >= MAX_LINE_BYTES) {
                        // Drain the rest of this line before throwing so the stream
                        // stays in a consistent state for subsequent reads.
                        while (true) {
                            val skip = input.read()
                            if (skip == -1 || skip == '\n'.code) break
                        }
                        throw java.io.IOException(
                            "Server sent a line exceeding $MAX_LINE_BYTES bytes — possible protocol violation"
                        )
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
 * A line reader that wraps an InputStream and handles encoding detection.
 * Maintains state for detected encoding across multiple reads.
 *
 * ## Detection strategy
 *
 * 1. Start assuming UTF-8 (overwhelmingly the most common modern encoding).
 * 2. On every line that contains at least one byte ≥ 0x80 (non-ASCII content):
 *    a. Append the raw bytes to a growing corpus buffer.
 *    b. Score all candidate encodings against the *entire corpus so far* (not just the
 *       current line). More data = better discrimination between ambiguous encodings.
 *    c. Cast a "vote" for the best-scoring encoding on this line and increment its
 *       per-encoding vote counter.
 * 3. Lock once one encoding leads by [LEAD_THRESHOLD] votes over the second-place
 *    encoding, with at least [MIN_EVIDENCE_LINES] non-ASCII lines seen.
 *
 * ## Why corpus scoring beats per-line scoring
 *
 * A single IRC line (e.g. "*** Welcome to the network") may have only 2–3 non-ASCII
 * bytes — too few to distinguish windows-1251 from ISO-8859-9 reliably.  Combining
 * bytes across lines builds up a byte-frequency distribution that is statistically
 * distinct per script family, allowing the scorer to tell Cyrillic from Turkish from
 * Latin with high confidence after just a handful of lines.
 *
 * ## Vote tolerance
 *
 * A single "wrong" per-line winner (e.g. a server NOTICE whose few non-ASCII bytes
 * happen to score slightly better under the wrong encoding) no longer blows away all
 * accumulated evidence.  Only when UTF-8 wins on a line does it reset everything,
 * since that is genuine contradicting evidence (valid UTF-8 multi-byte sequences cannot
 * be misidentified as a legacy 8-bit encoding by the scorer).
 *
 * ## Pre-lock decoding
 *
 * Lines arriving during the detection window are decoded with the *current leading
 * candidate* (highest-vote encoding), not with whatever each individual line votes for.
 * This means even the first few lines of a legacy-encoding channel render correctly
 * rather than showing garbled characters until the lock commits.
 *
 * ## ASCII neutrality
 *
 * Pure ASCII lines (no bytes ≥ 0x80) carry zero information about the server's legacy
 * encoding and are skipped in vote accounting.  This prevents MOTD/command lines from
 * diluting the evidence from actual non-ASCII content.
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
     * Minimum number of non-ASCII lines that must be seen before we consider locking.
     * Prevents locking on a single ambiguous registration line.
     */
    private val MIN_EVIDENCE_LINES = 2

    /**
     * The leading encoding's vote count must exceed second-place by at least this margin
     * before we commit.  A lead of 2 means one disagreeing line is tolerated as noise
     * but two in a row would delay the lock until more evidence accumulates.
     */
    private val LEAD_THRESHOLD = 2

    /**
     * Hard cap on the corpus size (bytes).  We stop appending once this is reached to
     * bound memory use on chatty servers; the corpus is usually sufficient well before
     * this limit is hit.
     */
    private val MAX_CORPUS_BYTES = 8192

    /**
     * Maximum number of bytes accepted per line from the server.
     * RFC 1459 allows 512 bytes; IRCv3 with message-tags extends this to 8191.
     * We use 8192 as a safe ceiling — anything larger is a protocol violation or
     * a runaway server bug that would cause unbounded memory growth.
     */
    private val MAX_LINE_BYTES = 8192

    /** Accumulated raw bytes from all non-ASCII lines seen so far during detection. */
    private val corpus = ByteArrayOutputStream(512)

    /** Per-encoding vote counts (number of non-ASCII lines on which each encoding won). */
    private val votes = mutableMapOf<String, Int>()

    /** Total non-ASCII lines seen (used for MIN_EVIDENCE_LINES guard). */
    private var evidenceLines = 0

    /** The currently committed/used encoding. */
    val encoding: String get() = currentEncoding

    /**
     * Read and decode the next line from the stream.
     * Returns null on EOF.
     */
    fun readLine(): String? {
        if (!autoDetect || encodingLocked) {
            // Fast path: locked or explicit encoding - read and decode directly.
            val (line, _) = EncodingHelper.readLine(input, currentEncoding, autoDetect = false)
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
                    // OOM guard as the internal readLine; drain and throw.
                    if (buffer.size() >= MAX_LINE_BYTES) {
                        while (true) {
                            val skip = input.read()
                            if (skip == -1 || skip == '\n'.code) break
                        }
                        throw java.io.IOException(
                            "Server sent a line exceeding $MAX_LINE_BYTES bytes — possible protocol violation"
                        )
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

        for (enc in EncodingHelper.COMMON_ENCODINGS.drop(1)) {   // skip UTF-8
            val charset = runCatching { java.nio.charset.Charset.forName(enc) }.getOrNull() ?: continue
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
