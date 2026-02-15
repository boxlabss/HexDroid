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
     * Check if a charset name is valid and supported.
     */
    fun isValidCharset(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.equals("auto", ignoreCase = true)) return true
        return runCatching { Charset.forName(name) }.isSuccess
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
    fun isValidUtf8(bytes: ByteArray): Boolean {
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

                // ----- Script-specific ranges: give heavier bonus -----
                // Cyrillic (windows-1251, KOI8-R)
                ch.code in 0x0400..0x04FF -> score += 4
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
        
        // Bonus for longer text without issues (suggests encoding is correct)
        if (replacements == 0 && text.length > 10) {
            score += text.length / 5
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
    fun decode(bytes: ByteArray, preferredEncoding: String): Pair<String, String> {
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
    fun readLine(
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
 * Detection strategy:
 * - Start assuming UTF-8 (overwhelmingly the most common modern encoding).
 * - On every line, run byte-level detection. If a non-UTF-8 encoding wins
 *   consistently (same encoding detected N times in a row), lock to it.
 * - Once locked, stop running detection (saves CPU).
 */
class EncodingLineReader(
    private val input: InputStream,
    initialEncoding: String = "auto"
) {
    private var currentEncoding = if (initialEncoding.equals("auto", ignoreCase = true)) {
        "UTF-8" // Start with UTF-8 assumption
    } else {
        initialEncoding
    }
    
    private val autoDetect = initialEncoding.equals("auto", ignoreCase = true)
    private var encodingLocked = !autoDetect

    /** How many consecutive lines must agree on a non-UTF-8 encoding before we lock. */
    private val lockThreshold = 2
    private var consecutiveNonUtf8Hits = 0
    private var lastDetectedNonUtf8: String? = null
    
    /**
     * The currently detected/used encoding.
     */
    val encoding: String get() = currentEncoding
    
    /**
     * Read a line from the stream.
     * 
     * @return The decoded line, or null on EOF
     */
    fun readLine(): String? {
        val doDetect = autoDetect && !encodingLocked
        val (line, detected) = EncodingHelper.readLine(input, currentEncoding, doDetect)
        
        if (doDetect && detected != currentEncoding) {
            if (detected != "UTF-8") {
                // Non-UTF-8 detected
                if (detected == lastDetectedNonUtf8) {
                    consecutiveNonUtf8Hits++
                } else {
                    lastDetectedNonUtf8 = detected
                    consecutiveNonUtf8Hits = 1
                }
                // Lock after threshold consecutive agreements
                if (consecutiveNonUtf8Hits >= lockThreshold) {
                    currentEncoding = detected
                    encodingLocked = true
                } else {
                    // Use the detected encoding for this line but don't lock yet
                    // (Re-decode is already done inside EncodingHelper.readLine)
                    currentEncoding = detected
                }
            } else {
                // Detected UTF-8 — reset streak counter
                consecutiveNonUtf8Hits = 0
                lastDetectedNonUtf8 = null
            }
        }
        
        return line
    }
    
    /**
     * Check if encoding detection has settled on a non-UTF-8 encoding.
     */
    fun hasDetectedNonUtf8(): Boolean = currentEncoding != "UTF-8" && (encodingLocked || consecutiveNonUtf8Hits > 0)
    
    /**
     * Force a specific encoding (disables auto-detection).
     */
    fun setEncoding(encoding: String) {
        currentEncoding = if (encoding.equals("auto", ignoreCase = true)) "UTF-8" else encoding
        encodingLocked = true
    }
}
