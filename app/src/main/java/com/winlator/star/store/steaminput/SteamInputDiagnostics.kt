package com.winlator.star.store.steaminput

import android.content.Context
import android.util.Log
import com.winlator.star.store.GoldbergMode
import com.winlator.star.store.SteamPrefs
import java.io.File

/**
 * Temporary diagnostics for the Steam Input (gbe_fork) path, surfaced in the in-game Debug panel
 * and logcat under tag [TAG]. Decisive info is emitted FIRST (the action sets our converter would
 * produce from the game's own config, and its game_action/preset lines) so the panel's line cap
 * cannot hide it, followed by the raw manifest/config text.
 *
 * Never throws: the caller appends every returned line to the log panel.
 */
object SteamInputDiagnostics {

    private const val TAG = "BH_STEAM_INPUT"
    private const val GREP_LINES = 200
    private const val RAW_LINES = 1200

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
                        out += runCatching { f.readText() }.getOrDefault("(unreadable)").split('\n')
                    }
                } else {
                    out += "NO steam_settings/controller beside ${parent.name}/${dll.name}"
                }
            }

            val found = findSteamInputManifests(installDir)
            out += "candidate SteamInput vdf files (${found.size}):"
            for (m in found) out += "  ${m.absolutePath}"

            val manifest = found.firstOrNull { it.name.equals("steam_input_manifest.vdf", true) }
            val gameConfig = found.firstOrNull { it.name.contains("controller_xboxone", true) }
                ?: found.firstOrNull { it.name.contains("controller_xbox360", true) }
                ?: found.firstOrNull { it.name.contains("controller_generic", true) }

            // DECISIVE: what our converter would emit for the game's own config.
            if (gameConfig != null) {
                out += "----- converter output for ${gameConfig.name} -----"
                val text = runCatching { gameConfig.readText() }.getOrNull()
                val conv = text?.let { runCatching { SteamInputVdfConverter.convert(it) }.getOrNull() }
                if (conv.isNullOrEmpty()) {
                    out += "converter: NO action sets produced"
                } else {
                    out += "converter: action sets=${conv.keys}"
                    for ((name, content) in conv) {
                        out += "- - - ${name}.txt - - -"
                        out += content.split('\n')
                    }
                }
                out += "----- game_action / preset / group_source_bindings lines in ${gameConfig.name} -----"
                val grep = text.orEmpty().lineSequence()
                    .filter {
                        it.contains("game_action") || it.contains("preset") ||
                            it.contains("group_source_bindings") || it.contains("switch_bindings") ||
                            it.contains("actions")
                    }
                    .take(GREP_LINES)
                    .toList()
                out += if (grep.isEmpty()) listOf("  (none)") else grep
            }

            // Raw text last (largest), so the decisive lines above survive the panel cap.
            if (manifest != null) {
                out += "----- MANIFEST: ${manifest.name} -----"
                out += readLines(manifest, RAW_LINES)
            }
            if (gameConfig != null) {
                out += "----- CONFIG: ${gameConfig.name} -----"
                out += readLines(gameConfig, RAW_LINES)
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

    private fun readLines(f: File, max: Int): List<String> =
        runCatching { f.readLines().take(max) }.getOrDefault(listOf("(unreadable)"))

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

    // ── RealSteam (genuine-Steam) Steam Input ─────────────────────────────────────────────────────

    /**
     * RealSteam Steam Input diagnostics, surfaced in the in-game Debug panel and logcat under [TAG].
     * Reads the ACTUAL staged state, so call it AFTER [com.winlator.star.store.RealSteamLauncher.prepare]
     * (the launch-time hook runs before prepare writes anything): whether Steam Input is on for this app
     * (toggle or own manifest), the game's own manifest, the staged host layout, the genuine client's
     * localconfig Steam Input keys, and the agent env flag. Never throws.
     */
    fun reportRealSteam(
        context: Context,
        driveC: File?,
        appId: Int,
        installDir: File?,
        agentEnv: Map<String, String>?,
    ): List<String> {
        val out = ArrayList<String>()
        try {
            SteamPrefs.init(context.applicationContext)
            val toggle = SteamPrefs.getUseSteamInput(appId)
            val declared = installDir?.let { SteamInputLayouts.hasOwnManifest(it) } ?: false
            out += "===== STEAM INPUT DIAG / RealSteam (appId=$appId) ====="
            out += "steamInputEnabled=${toggle || declared}  (toggle=$toggle, ownManifest=$declared)"
            out += "agent BL_AGENT_STEAMINPUT=${agentEnv?.get("BL_AGENT_STEAMINPUT") ?: "(absent)"}"
            out += "installDir=${installDir?.absolutePath ?: "UNRESOLVED"}"
            val manifest = installDir?.let { SteamInputLayouts.findManifest(it) }
            out += "own manifest=${manifest?.absolutePath ?: "(none)"}"

            val steamDir = driveC?.let { File(it, "Program Files (x86)/Steam") }
            if (steamDir == null || !steamDir.isDirectory) {
                out += "prefix Steam dir: MISSING (${steamDir?.absolutePath ?: "driveC null"})"
                out += "===== END STEAM INPUT DIAG ====="
                return log(out)
            }
            val layout = File(steamDir, "steamhost_controller_$appId.vdf")
            out += "staged ${layout.name}=" +
                (if (layout.isFile) "OK (${layout.length()} bytes)" else "MISSING")

            val localConfigs = findLocalConfigs(steamDir)
            if (localConfigs.isEmpty()) {
                out += "localconfig.vdf: NONE under ${steamDir.absolutePath}/userdata"
            } else {
                for (lc in localConfigs) {
                    out += "----- ${lc.absolutePath} -----"
                    out += localConfigSteamInputLines(runCatching { lc.readText() }.getOrDefault(""), appId)
                }
            }
            out += "===== END STEAM INPUT DIAG ====="
        } catch (t: Throwable) {
            out += "steam-input RealSteam diag failed: $t"
        }
        return log(out)
    }

    private fun findLocalConfigs(steamDir: File): List<File> {
        val users = File(steamDir, "userdata").listFiles() ?: return emptyList()
        return users.mapNotNull { File(it, "config/localconfig.vdf").takeIf { f -> f.isFile } }
    }

    /** Just the Steam Input lines: the support keys and the per-app UseSteamControllerConfig. */
    internal fun localConfigSteamInputLines(content: String, appId: Int): List<String> {
        val keys = listOf(
            "SteamController_XBoxSupport", "SteamController_GenericGamepadSupport",
            "SteamController_PSSupport", "SteamController_SwitchSupport",
            "UseSteamControllerConfig",
        )
        val out = ArrayList<String>()
        for (raw in content.lines()) {
            val t = raw.trim()
            if (keys.any { t.startsWith("\"$it\"") }) out += "  $t"
        }
        if (out.isEmpty()) out += "  (no Steam Input keys found)"
        out += "  has apps/$appId block=${content.contains("\"$appId\"")}"
        return out
    }
}
