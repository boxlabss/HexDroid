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
object EncodingHelper {
    
    /**
     * Common IRC encodings to try during auto-detection.
     * Ordered roughly by global popularity on IRC.
     */
    val COMMON_ENCODINGS = listOf(
        "UTF-8",
        "windows-1256",   // Arabic
        "ISO-8859-9",     // Turkish (Latin-5)
        "windows-1254",   // Turkish (Windows)
        "windows-1251",   // Cyrillic (Russian, Bulgarian, Serbian, etc.)
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
     * Improvements over naive letter-counting:
     * - Byte-range analysis to detect CJK multi-byte sequences
     * - Specific character class bonuses for Cyrillic/Arabic/Turkish/Greek/Hebrew ranges
     * - Penalizes lone high-byte surrogates and C1 control chars that indicate a wrong decode
     */
    private fun scoreEncoding(bytes: ByteArray, charset: Charset): Int {
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

			   /** 
				* Script-specific ranges: give heavier bonus
				* Cyrillic tiebreaker - windows-1251 vs KOI8-R:
				*
				* Both encodings map 0xC0-0xFF to standard Cyrillic А-я, so those bytes
				* score equally under either charset.  The discriminating bytes are in
				* the 0x80-0xBF range:
				*
				* windows-1251 0x80-0x9F - Cyrillic supplement letters (Ђ,Ѓ,Љ,Њ…)
				* windows-1251 0xA0-0xBF - more Cyrillic letters (Ё,Є,Ї…)
				* KOI8-R       0x80-0x9F - C1 control characters (penalised −15 below)
				* KOI8-R       0xA0-0xBF - box-drawing symbols (cat So, +2 below)
				*
				* The supplement block U+0400-U+040F gets an extra +2 bump (+6 total)
				* because windows-1251 maps 14 of its 0x80-0xBF bytes into this range
				* while KOI8-R maps only one (0xB3 > Ё, U+0401).  Any line with
				* Bulgarian/Ukrainian letters (Ё, Ї, Ђ…) therefore scores noticeably
				* higher for windows-1251 than for KOI8-R.
				*/
                ch.code in 0x0400..0x040F -> score += 6  // Cyrillic supplement (windows-1251 advantage)
                ch.code in 0x0410..0x04FF -> score += 4  // Main Cyrillic А-я block
                // Arabic (windows-1256, ISO-8859-6)
                ch.code in 0x0600..0x06FF -> score += 4
                // Arabic supplementary
                ch.code in 0x0750..0x077F -> score += 4
                // Turkish/Latin-extended specific (İ ı Ğ ğ Ş ş)
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

                // C1 control characters (0x80-0x9F) that show up as characters often
                // mean the wrong single-byte codepage was picked
                ch.code in 0x80..0x9F -> score -= 15
                // Other control characters (suspicious)
                ch.isISOControl() -> score -= 10
            }
        }
        
        // Bonus for longer text without issues — but ONLY when the decoded text actually
        // contains non-ASCII characters.  Every single-byte codepage (windows-1251, 1252,
        // ISO-8859-1 …) can decode any byte without producing a replacement character, so a
        // pure-ASCII line always has 0 replacements regardless of which codepage is tried.
        // Awarding text.length/5 in that case makes windows-1251 win the scoring race for
        // long ASCII MOTD lines simply because it is listed before 1252/ISO-8859-1 in
        // COMMON_ENCODINGS — the root cause of the spurious CP-1251 auto-detection bug.
        if (replacements == 0 && text.length > 10) {
            val nonAsciiCount = text.count { it.code > 0x7F && !it.isISOControl() }
            if (nonAsciiCount > 0) {
                // Scale bonus to non-ASCII density rather than raw length.
                score += (nonAsciiCount * 2).coerceAtMost(text.length / 5)
            }
            // Pure-ASCII clean decode: no bonus — every single-byte codec ties here
        }

        // Bonus: if the raw byte stream has common multi-byte lead-byte patterns
        // for CJK encodings, reward the encoding that claims to be CJK
        val csName = charset.name().uppercase()
        val highByteCount = bytes.count { it.toInt() and 0xFF > 0x7F }
        if (highByteCount > 0) {
            val highRatio = highByteCount.toFloat() / bytes.size
            // Dense high bytes suggest CJK or Arabic, not western
            if (highRatio > 0.3f && (csName.contains("GB") || csName.contains("BIG5") ||
                csName.contains("SHIFT") || csName.contains("EUC") || csName.contains("1256") ||
                csName.contains("8859-6") || csName.contains("1254") || csName.contains("8859-9"))) {
                score += (highByteCount * 2)
            }
        }
        
        return score
    }
    
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
                else -> buffer.write(b)
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
 * 2. On every line that contains at least one byte ≥ 0x80 (i.e. non-ASCII content),
 *    run [EncodingHelper.detectEncoding] on the raw bytes.
 * 3. If the same non-UTF-8 encoding wins on [LOCK_THRESHOLD] such lines in a row,
 *    commit to it and stop running detection (saves CPU per line).
 *
 * ## Key design invariants
 *
 * **Speculative-update prevention:** [currentEncoding] is NOT changed until the streak
 * reaches [LOCK_THRESHOLD].  Each line during the streak is decoded with a fresh
 * auto-detect pass, not with a half-committed encoding.
 *
 * **ASCII neutrality:** Pure ASCII lines (no bytes ≥ 0x80) are always valid UTF-8,
 * but they carry zero information about the server's legacy encoding.  On a Bulgarian
 * windows-1251 server, `NICK`, `PING`, `JOIN`, and many MOTD lines are pure ASCII and
 * arrive interleaved with Cyrillic chat lines.  Treating those ASCII lines as evidence
 * for UTF-8 would perpetually reset the streak and prevent the encoding from ever
 * locking.  Instead, pure-ASCII lines are skipped in streak accounting entirely.
 *
 * **Streak reset:** The streak is only reset when a line with actual non-ASCII content
 * is detected as a *different* encoding from the current candidate — i.e. genuinely
 * contradicting evidence.
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
     * How many consecutive non-ASCII lines must agree on a non-UTF-8 encoding before
     * we commit to it.  2 is enough: on a real legacy server the first two non-ASCII
     * lines almost always agree, and ASCII lines in between no longer cause false resets.
     */
    private val LOCK_THRESHOLD = 2

    /** The candidate non-UTF-8 encoding currently accumulating streak count. */
    private var streakEncoding: String? = null
    private var streakCount: Int = 0

    /** The currently committed/used encoding. */
    val encoding: String get() = currentEncoding

    /**
     * Read and decode the next line from the stream.
     * Returns null on EOF.
     */
    fun readLine(): String? {
        if (!autoDetect || encodingLocked) {
            // Fast path: locked or explicit encoding — read and decode directly.
            val (line, _) = EncodingHelper.readLine(input, currentEncoding, autoDetect = false)
            return line
        }

        // Detection path: read raw bytes first so we can inspect them before decoding.
        val buffer = ByteArrayOutputStream(512)
        var b: Int
        while (true) {
            b = input.read()
            if (b == -1) {
                // EOF — decode whatever we have and return.
                if (buffer.size() == 0) return null
                break
            }
            when (b) {
                '\n'.code -> break
                '\r'.code -> { /* skip CR */ }
                else -> buffer.write(b)
            }
        }
        if (b == -1 && buffer.size() == 0) return null

        val bytes = buffer.toByteArray()

        // Check whether the line has any non-ASCII bytes at all.
        val hasHighBytes = bytes.any { it.toInt() and 0xFF >= 0x80 }

        if (!hasHighBytes) {
            // Pure ASCII encoding-neutral. Don't touch the streak; just decode as UTF-8.
            return String(bytes, Charsets.UTF_8)
        }

        // Non-ASCII content: run detection to get evidence about the encoding.
        val detected = EncodingHelper.detectEncoding(bytes)
        val decodedLine = String(bytes, EncodingHelper.getCharset(detected))

        when {
            detected == "UTF-8" -> {
                // Genuine UTF-8 non-ASCII content — this is real contradicting evidence.
                // Reset the streak; this server is probably UTF-8.
                streakEncoding = null
                streakCount = 0
            }
            detected == streakEncoding -> {
                // Same candidate as before — advance the streak.
                streakCount++
                if (streakCount >= LOCK_THRESHOLD) {
                    currentEncoding = detected
                    encodingLocked = true
                    streakEncoding = null
                    streakCount = 0
                }
            }
            else -> {
                // Different non-UTF-8 candidate; restart streak with the new candidate.
                streakEncoding = detected
                streakCount = 1
                // (currentEncoding stays "UTF-8" - no update)
            }
        }

        return decodedLine
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
        streakEncoding = null
        streakCount = 0
    }
}
