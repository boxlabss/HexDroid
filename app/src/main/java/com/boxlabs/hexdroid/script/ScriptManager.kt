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

package com.boxlabs.hexdroid.script

import android.content.Context
import android.content.SharedPreferences
import java.io.File

data class ScriptInfo(
    val name: String,            // filename, e.g. "poker.hex"
    val ext: String,             // "hex"
    val enabled: Boolean,
    val error: String?,          // null = loaded OK; else the load/parse error
    val bundled: Boolean = false,// true = ships in assets (can be reverted to its default)
) {
    val ok: Boolean get() = error == null
}

/** Data-down state for ScriptsScreen. */
data class ScriptsUiState(
    val scripts: List<ScriptInfo>,
    val backendName: String,     // the active engine's extension, for the "add" hint
)

/**
 * on-disk store for user scripts: one file per script under `<filesDir>/scripts`.
 *
 * loaded alphabetically on start.
 */
class ScriptStore(context: Context, private val ext: String = "js") {

    private val dir: File = File(context.filesDir, "scripts").apply { mkdirs() }
    private val dotExt: String = ".$ext"

    /** All scripts, name -> source, sorted by filename for deterministic load order. */
    fun loadAll(): List<Pair<String, String>> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(dotExt) } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .map { it.name to it.readText() }

    fun read(name: String): String? {
        val f = File(dir, sanitise(name))
        return if (f.isFile) f.readText() else null
    }

    fun write(name: String, source: String) {
        File(dir, sanitise(name)).writeText(source)
    }

    fun delete(name: String): Boolean = File(dir, sanitise(name)).delete()

    fun list(): List<String> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(dotExt) } ?: emptyArray())
            .map { it.name }
            .sortedBy { it.lowercase() }

    /**
     * Keep filenames inside [dir] — strip path separators and parent refs so a
     * crafted name can't escape the scripts directory.
     */
    private fun sanitise(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace("..", "").ifBlank { "script" }
        return if (cleaned.endsWith(dotExt)) cleaned else "$cleaned$dotExt"
    }
}

/**
 * Installs/enables/reloads user scripts and surfaces load status to the Scripts UI.
 */
class ScriptManager(
    context: Context,
    private val engine: ScriptEngine,
    private val prefs: SharedPreferences,
) {
    private val store = ScriptStore(context, engine.scriptExtension)
    private val assetCtx: Context = context
    private val errors = HashMap<String, String?>()

    private fun disabledSet(): MutableSet<String> =
        prefs.getStringSet(KEY_DISABLED, emptySet())!!.toMutableSet()

    private fun editedSet(): MutableSet<String> =
        prefs.getStringSet(KEY_EDITED, emptySet())!!.toMutableSet()

    private fun suppressedSet(): MutableSet<String> =
        prefs.getStringSet(KEY_SUPPRESSED, emptySet())!!.toMutableSet()

    /**
     * Copy bundled scripts shipped under assets/hex; into the store on first sight,
     * and refresh already-installed bundled scripts to a newer shipped version, so that built-in
     * features  both appear without an import AND actually update when a new APK
     * ships a newer copy. A script the user has explicitly removed is not re-seeded (KEY_SUPPRESSED).
     *
     * How "did the user edit this?" is decided:
     * we remember the SHA-256 of the last shipped source for each bundled script
     * (KEY_SHIPPED). On a later launch, if the on-disk copy still matches that baseline the user
     * hasn't touched it, so we overwrite it with the new shipped source and move the baseline
     * forward.
     */
    fun seedBundled(subdir: String = engine.scriptExtension) {
        val dot = ".${engine.scriptExtension}"
        val have = store.list().toSet()
        val gone = suppressedSet()
        val legacyEdited = editedSet()
        val names = runCatching { assetCtx.assets.list(subdir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val seeded = mutableListOf<String>()   // freshly installed this pass (ship disabled)
        val ed = prefs.edit()
        for (a in names) {
            if (!a.endsWith(dot) || a in gone) continue
            val src = runCatching {
                assetCtx.assets.open("$subdir/$a").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val srcHash = sha256(src)
            val baseline = prefs.getString(shippedKey(a), null)

            if (a !in have) {
                // First sight: install it (ships disabled; the user opts in) and record the baseline.
                store.write(a, src)
                ed.putString(shippedKey(a), srcHash)
                seeded += a
                continue
            }

            val onDisk = store.read(a) ?: continue
            val userEdited = if (baseline != null) {
                // Primary signal: has the on-disk copy diverged from the source we last shipped?
                sha256(onDisk) != baseline
            } else {
                // No baseline yet (pre-baseline install): trust the legacy flag only. A content
                // difference here is expected (old shipped version) and must NOT be read as an edit.
                a in legacyEdited
            }

            if (!userEdited) {
                // Unedited: pull the newer shipped source through and advance the baseline. The
                // write is skipped when content already matches, so steady state does no disk I/O.
                if (onDisk != src) store.write(a, src)
                ed.putString(shippedKey(a), srcHash)
            }
            // else: user diverged -> keep their copy untouched. Baseline is left as-is so a later
            // "Revert to default" is what re-opens the update path (see revertToBundled).
        }
        // Bundled scripts ship DISABLED by default, the user opts in from the Scripts screen.
        if (seeded.isNotEmpty()) {
            ed.putStringSet(KEY_DISABLED, disabledSet().apply { addAll(seeded) })
        }
        ed.apply()
    }

    /** SHA-256 of a script source, hex-encoded. Used as the shipped-version baseline fingerprint. */
    private fun sha256(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) { val v = b.toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0xf]) }
        return sb.toString()
    }

    private fun shippedKey(name: String): String = "$KEY_SHIPPED_PREFIX$name"

    /** Current installed scripts with enabled + last-load status. */
    fun list(): List<ScriptInfo> {
        val disabled = disabledSet()
        val bundled = bundledNames()
        return store.list().map { name ->
            ScriptInfo(name, engine.scriptExtension, enabled = name !in disabled, error = errors[name], bundled = name in bundled)
        }
    }

    /** Names of scripts that ship in assets (so the UI can offer "revert to default"). */
    private fun bundledNames(): Set<String> =
        runCatching { assetCtx.assets.list(engine.scriptExtension)?.toSet() ?: emptySet() }.getOrDefault(emptySet())

    fun state(): ScriptsUiState = ScriptsUiState(list(), engine.scriptExtension)

    /** Drop everything and re-load all enabled scripts; refresh per-script status; fire LOAD. */
    fun reloadAll() {
        engine.resetHandlers()
        errors.clear()
        val disabled = disabledSet()
        for ((name, source) in store.loadAll()) {
            if (name in disabled) continue
            errors[name] = engine.loadScript(name, source)   // null = ok
        }
        // `on LOAD {}` runs once after (re)load — this is where scripts register sidebar
        // launchers, set up channels, etc. Without it none of that startup code ever runs.
        engine.dispatch("LOAD", EventData(network = "", buffer = ""))
    }

    /**
     * Reset a bundled script to its shipped source (discarding any user edits), then reload.
     * Returns the restored source, or null if there is no bundled asset of that name.
     */
    fun revertToBundled(name: String): String? {
        val src = runCatching {
            assetCtx.assets.open("${engine.scriptExtension}/$name").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        store.write(name, src)
        // Back to the shipped default -> clear the user-edited flag AND reset the shipped-source
        // baseline to this source, so a later app update is recognised as unedited and flows again.
        val real = ensureExt(name)
        prefs.edit()
            .putString(shippedKey(real), sha256(src))
            .apply()
        if (real in editedSet()) {
            prefs.edit().putStringSet(KEY_EDITED, editedSet().apply { remove(real) }).apply()
        }
        reloadAll()
        return src
    }

    fun setEnabled(name: String, enabled: Boolean) {
        val d = disabledSet()
        if (enabled) d.remove(name) else d.add(name)
        prefs.edit().putStringSet(KEY_DISABLED, d).apply()
        reloadAll()
    }

    /** Add or overwrite a script from local text, then reload. Returns its status row. */
    fun install(name: String, source: String): ScriptInfo {
        store.write(name, source)
        val real = ensureExt(name)
        if (real in suppressedSet()) {
            prefs.edit().putStringSet(KEY_SUPPRESSED, suppressedSet().apply { remove(real) }).apply()
        }
        // Editing a BUNDLED script marks it user-edited so a later app update won't overwrite the
        // edit with the shipped default.
        if (real in bundledNames()) {
            prefs.edit().putStringSet(KEY_EDITED, editedSet().apply { add(real) }).apply()
        }
        reloadAll()
        val disabled = disabledSet()
        return ScriptInfo(real, engine.scriptExtension, real !in disabled, errors[real])
    }

    fun remove(name: String) {
        store.delete(name)
        val real = ensureExt(name)
        prefs.edit()
            .putStringSet(KEY_DISABLED, disabledSet().apply { remove(name); remove(real) })
            .putStringSet(KEY_SUPPRESSED, suppressedSet().apply { add(real) })
            .apply()
        reloadAll()
    }

    /** Source for an editor/preview. */
    fun read(name: String): String? = store.read(name)

    private fun ensureExt(name: String): String {
        val dot = ".${engine.scriptExtension}"
        return if (name.endsWith(dot)) name else "$name$dot"
    }

    companion object {
        const val PREFS = "hexdroid_scripts"
        private const val KEY_DISABLED = "disabled"
        private const val KEY_SUPPRESSED = "suppressed_bundled"
        private const val KEY_EDITED = "user_edited_bundled"
        // Per-script "shipped_hash:<name>" -> SHA-256 of the last shipped source we wrote. Lets
        // seedBundled tell an unedited bundled script from an edited one.
        private const val KEY_SHIPPED_PREFIX = "shipped_hash:"
    }
}
