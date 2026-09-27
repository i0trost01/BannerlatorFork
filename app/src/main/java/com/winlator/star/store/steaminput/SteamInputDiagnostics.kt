package com.winlator.star.store.steaminput

import android.content.Context
import android.util.Log
import com.winlator.star.store.GoldbergMode
import com.winlator.star.store.SteamPrefs
import java.io.File

/**
 * Temporary diagnostics for the Steam Input (gbe_fork) path, surfaced in the in-game Debug panel
 * and logcat under tag [TAG]. Reports the toggle/tier, the steam_api dll locations, whether the
 * gbe_fork `steam_settings/controller/` files exist and their exact contents, and every candidate
 * Steam Input action-manifest file found in the install (path + bounded text) — the data needed to
 * decide whether a title is failing because gbe_fork's action-set names don't match the game's.
 *
 * Never throws: the caller appends every returned line to the log panel.
 */
object SteamInputDiagnostics {

    private const val TAG = "BH_STEAM_INPUT"
    private const val MAX_CHARS_PER_FILE = 6000
    private const val MAX_FILES = 6

    fun report(context: Context, installDir: File?, appId: Int): List<String> {
        val out = ArrayList<String>()
        try {
            SteamPrefs.init(context.applicationContext)
            val toggle = SteamPrefs.getUseSteamInput(appId)
            val mode: GoldbergMode = SteamPrefs.getGoldbergMode(appId)
            out += "===== STEAM INPUT DIAG (appId=$appId) ====="
            out += "toggle=$toggle  goldbergMode=$mode"

            if (installDir == null || !installDir.isDirectory) {
                out += "installDir: UNRESOLVED"
                out += "===== END STEAM INPUT DIAG ====="
                return log(out)
            }
            out += "installDir=${installDir.absolutePath}"

            val dlls = installDir.walkTopDown().maxDepth(8)
                .filter {
                    it.isFile &&
                        (it.name.equals("steam_api64.dll", true) || it.name.equals("steam_api.dll", true))
                }
                .take(8)
                .toList()
            out += "steam_api dlls=${dlls.map { (it.parentFile?.name ?: "?") + "/" + it.name }}"

            for (dll in dlls) {
                val parent = dll.parentFile ?: continue
                val ctrl = File(parent, SteamInputConfigWriter.CONTROLLER_DIR)
                if (ctrl.isDirectory) {
                    val files = ctrl.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
                    out += "controller files beside ${parent.name}/${dll.name}: ${files.map { it.name }}"
                    for (f in files) {
                        out += "----- ${f.name} -----"
                        out += readBounded(f).split('\n')
                    }
                } else {
                    out += "NO steam_settings/controller beside ${parent.name}/${dll.name}"
                }
            }

            val manifests = findSteamInputManifests(installDir)
            out += "candidate SteamInput manifests (${manifests.size}):"
            for (m in manifests.take(MAX_FILES)) {
                out += "  ${m.absolutePath}"
            }
            for (m in manifests.take(MAX_FILES)) {
                out += "----- ${m.absolutePath} -----"
                out += readBounded(m).split('\n')
            }
            out += "===== END STEAM INPUT DIAG ====="
        } catch (t: Throwable) {
            out += "steam-input diag failed: $t"
        }
        return log(out)
    }

    private fun log(lines: List<String>): List<String> {
        for (line in lines) Log.i(TAG, line)
        return lines
    }

    private fun readBounded(f: File): String =
        runCatching { f.readText().take(MAX_CHARS_PER_FILE) }.getOrDefault("(unreadable)")

    private fun findSteamInputManifests(root: File): List<File> {
        val hits = ArrayList<File>()
        runCatching {
            root.walkTopDown().maxDepth(9).forEach { f ->
                if (!f.isFile || !f.name.endsWith(".vdf", true)) return@forEach
                val p = f.absolutePath.replace('\\', '/').lowercase()
                if (p.contains("/steaminput/") ||
                    f.name.equals("steam_input_manifest.vdf", true) ||
                    p.contains("/controller_config/") ||
                    f.name.startsWith("game_actions_", true)
                ) {
                    hits += f
                }
            }
        }
        return hits
    }
}
