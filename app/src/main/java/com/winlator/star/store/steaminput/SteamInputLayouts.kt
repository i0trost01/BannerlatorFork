package com.winlator.star.store.steaminput

import android.content.Context
import java.io.File

/**
 * Resolves the controller layout VDF used to build a game's `steam_settings/controller/` files.
 *
 * Precedence:
 *  1. a layout the user/app dropped at `<game>/.bannerlator/steaminput_layout.vdf` (outside
 *     `steam_settings/`, which Goldberg's restore and every OFF apply delete wholesale);
 *  2. the game's OWN Steam Input action manifest (`steam_input_manifest.vdf` shipped in the install,
 *     Unity `…_Data/StreamingAssets/SteamInput/` convention) → its Xbox config VDF — used by games
 *     that hand input to Steam Input (GameNative "template index 13", e.g. Monster Train 2);
 *  3. the bundled identity layout in `assets/steaminput/gamepad.vdf`.
 *
 * `resolve` is called with the steam_api dll's parent directory, which may be a subtree of the
 * install (e.g. `…/x86_64`); [findManifest] therefore searches the directory AND its ancestors.
 */
object SteamInputLayouts {

    /** Relative path inside a game install where a provided layout is read from. */
    const val PROVIDED_REL = ".bannerlator/steaminput_layout.vdf"

    private const val BUNDLED_ASSET = "steaminput/gamepad.vdf"
    private const val MANIFEST_NAME = "steam_input_manifest.vdf"
    private val CONTROLLER_TYPES = listOf("controller_xboxone", "controller_xbox360", "controller_generic")

    fun resolve(context: Context, installDir: File): String? =
        resolve(context, installDir, 0)

    fun resolve(context: Context, installDir: File, appId: Int): String? {
        val provided = File(installDir, PROVIDED_REL)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
        val own = resolveOwnManifestConfig(installDir)
        val bundled = runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        return provided ?: own ?: bundled
    }

    /** True when the install ships its own Steam Input action manifest (index-13-style game). */
    fun hasOwnManifest(installDir: File): Boolean = findManifest(installDir) != null

    private fun resolveOwnManifestConfig(installDir: File): String? {
        val manifest = findManifest(installDir) ?: return null
        val dir = manifest.parentFile ?: return null
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        val rel = pickControllerConfigPath(text) ?: return null
        val cfg = File(dir, rel.replace('\\', '/'))
        return runCatching { cfg.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    /**
     * Finds the game's own Steam Input manifest. Starts at [installDir] (the dll's parent) and walks
     * up to a few ancestors, checking each for the Unity `StreamingAssets/SteamInput` convention.
     */
    internal fun findManifest(installDir: File): File? {
        var root: File? = installDir
        var up = 0
        while (root != null && up < 4) {
            findIn(root)?.let { return it }
            root = root.parentFile
            up++
        }
        return null
    }

    private fun findIn(root: File): File? {
        File(root, "StreamingAssets/SteamInput").firstNamed()?.let { return it }
        File(root, "SteamInput").firstNamed()?.let { return it }
        val children = root.listFiles() ?: return null
        for (child in children) {
            if (!child.isDirectory) continue
            File(child, "StreamingAssets/SteamInput").firstNamed()?.let { return it }
            File(child, "SteamInput").firstNamed()?.let { return it }
        }
        return null
    }

    private fun File.firstNamed(): File? =
        listFiles()?.firstOrNull { it.isFile && it.name.equals(MANIFEST_NAME, true) }

    /**
     * Pure: reads an "Action Manifest" text and returns the config `.vdf` path for the best available
     * controller type (Xbox One → Xbox 360 → generic), or null.
     */
    internal fun pickControllerConfigPath(manifestText: String): String? {
        val lines = manifestText.lines()
        for (type in CONTROLLER_TYPES) {
            val start = lines.indexOfFirst { it.contains("\"$type\"") }
            if (start < 0) continue
            for (i in start + 1 until lines.size) {
                val line = lines[i]
                if (line.contains("\"controller_") && !line.contains("\"path\"")) break
                val m = Regex("\"path\"\\s+\"([^\"]+)\"").find(line)
                if (m != null) return m.groupValues[1]
            }
        }
        return null
    }
}
