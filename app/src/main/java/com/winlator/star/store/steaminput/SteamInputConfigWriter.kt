package com.winlator.star.store.steaminput

import java.io.File

/**
 * Drops the gbe_fork action-set files (`steam_settings/controller/<ActionSet>.txt`) beside a game's
 * steam_api dll. gbe_fork auto-enables its Steam Input emulation when this folder has action sets.
 * Pure JVM (no Android APIs) so it is unit-testable.
 */
object SteamInputConfigWriter {

    internal const val CONTROLLER_DIR = "steam_settings/controller"

    /** Writes one file per action set. Returns the number of files written; 0 = nothing to do. */
    fun write(installDir: File, vdfText: String): Int {
        val sets = SteamInputVdfConverter.convert(vdfText)
        if (sets.isEmpty()) return 0
        val dir = File(installDir, CONTROLLER_DIR).apply { mkdirs() }
        var written = 0
        for ((setName, content) in sets) {
            File(dir, "$setName.txt").writeText(content)
            written++
        }
        return written
    }

    /** Removes the controller folder. Safe when absent. */
    fun clear(installDir: File) {
        File(installDir, CONTROLLER_DIR).takeIf { it.isDirectory }?.deleteRecursively()
    }
}
