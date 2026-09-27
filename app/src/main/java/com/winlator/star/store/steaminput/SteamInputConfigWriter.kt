package com.winlator.star.store.steaminput

import java.io.File

/**
 * Drops the gbe_fork action-set files (`steam_settings/controller/<ActionSet>.txt`) beside a game's
 * steam_api dll. gbe_fork auto-enables its Steam Input emulation when this folder has action sets.
 * Pure JVM (no Android APIs) so it is unit-testable.
 */
object SteamInputConfigWriter {

    internal const val CONTROLLER_DIR = "steam_settings/controller"

    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Maps an action-set name to a safe filename stem inside the controller dir (no traversal). */
    internal fun sanitizeActionSetName(name: String): String {
        val cleaned = name.replace(UNSAFE_NAME_CHARS, "_")
        return if (cleaned == "." || cleaned == "..") "_" else cleaned
    }

    /** Writes one file per action set. Returns the number of files written; 0 = nothing to do. */
    fun write(installDir: File, vdfText: String): Int {
        val sets = SteamInputVdfConverter.convert(vdfText)
        val dir = File(installDir, CONTROLLER_DIR)
        // Reconcile: always drop stale action-set files first, so a layout that yields NO sets cannot
        // leave a previous set behind, and a new layout never mixes with the old one.
        dir.takeIf { it.isDirectory }?.deleteRecursively()
        if (sets.isEmpty()) return 0
        dir.mkdirs()
        var written = 0
        for ((setName, content) in sets) {
            File(dir, "${sanitizeActionSetName(setName)}.txt").writeText(content)
            written++
        }
        return written
    }

    /** Removes the controller folder. Safe when absent. */
    fun clear(installDir: File) {
        File(installDir, CONTROLLER_DIR).takeIf { it.isDirectory }?.deleteRecursively()
    }
}
