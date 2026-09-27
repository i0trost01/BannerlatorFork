package com.winlator.star.store.steaminput

import android.content.Context
import java.io.File

/**
 * Resolves the controller layout VDF used to build a game's `steam_settings/controller/` files.
 *
 * Precedence:
 *  1. a layout the user/app dropped at `<install>/.bannerlator/steaminput_layout.vdf` (outside
 *     `steam_settings/`, which Goldberg's restore and every OFF apply delete wholesale);
 *  2. the game's OWN Steam Input action manifest (`steam_input_manifest.vdf` shipped in the install,
 *     Unity `…_Data/StreamingAssets/SteamInput/` convention) → its Xbox config VDF — used by games
 *     that hand input to Steam Input (GameNative "template index 13", e.g. Monster Train 2);
 *  3. the bundled identity layout in `assets/steaminput/gamepad.vdf`.
 *
 * Discovery is confined to [installRoot] (the game's own directory): searching ancestors or siblings
 * could match a different game's manifest and silently write the wrong mappings.
 */
object SteamInputLayouts {

    /** Relative path inside a game install where a provided layout is read from. */
    const val PROVIDED_REL = ".bannerlator/steaminput_layout.vdf"

    private const val BUNDLED_ASSET = "steaminput/gamepad.vdf"
    private const val MANIFEST_NAME = "steam_input_manifest.vdf"
    private val CONTROLLER_TYPES = listOf("controller_xboxone", "controller_xbox360", "controller_generic")

    /** The headless genuine-Steam host identifies as an Xbox 360 pad, so it wants the 360 config
     *  (GameNative HOST_CONTROLLER_TYPES). */
    private val HOST_CONTROLLER_TYPES = listOf("controller_xbox360", "controller_xboxone", "controller_generic")

    fun resolve(context: Context, installRoot: File): String? {
        val provided = File(installRoot, PROVIDED_REL)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
        val own = resolveOwnManifestConfig(installRoot)
        val bundled = runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        return chooseLayoutSource(provided, own, bundled)
    }

    /** Pure precedence: provided → the game's own manifest config → the bundled generic. */
    internal fun chooseLayoutSource(provided: String?, own: String?, bundled: String?): String? =
        provided ?: own ?: bundled

    /** True when the install ships its own Steam Input action manifest. */
    fun hasOwnManifest(installRoot: File): Boolean = findManifest(installRoot) != null

    /**
     * The controller config VDF the genuine Steam client should use for this game, read from the game's
     * OWN action manifest and preferring the Xbox 360 mapping. Null when the game ships none.
     */
    fun resolveHostConfigText(installRoot: File): String? =
        resolveManifestConfig(installRoot, HOST_CONTROLLER_TYPES)

    internal fun resolveOwnManifestConfig(installRoot: File): String? =
        resolveManifestConfig(installRoot, CONTROLLER_TYPES)

    private fun resolveManifestConfig(installRoot: File, types: List<String>): String? {
        val manifest = findManifest(installRoot) ?: return null
        val dir = manifest.parentFile ?: return null
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        val rel = pickControllerConfigPath(text, types) ?: return null
        val cfg = File(dir, rel.replace('\\', '/'))
        return runCatching { cfg.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    /**
     * Finds the game's own Steam Input manifest, confined to [installRoot]: the root itself and one
     * level of child directories, using the Unity `StreamingAssets/SteamInput` convention.
     */
    internal fun findManifest(installRoot: File): File? {
        File(installRoot, "StreamingAssets/SteamInput").firstNamed()?.let { return it }
        File(installRoot, "SteamInput").firstNamed()?.let { return it }
        val children = installRoot.listFiles() ?: return null
        for (child in children) {
            if (!child.isDirectory) continue
            File(child, "StreamingAssets/SteamInput").firstNamed()?.let { return it }
            File(child, "SteamInput").firstNamed()?.let { return it }
        }
        return null
    }

    private fun File.firstNamed(): File? =
        listFiles()?.firstOrNull { it.isFile && it.name.equals(MANIFEST_NAME, true) }

    /** Uses the game's own default controller order ([CONTROLLER_TYPES], Xbox One first); the 2-arg
     *  overload takes an explicit preference order. */
    internal fun pickControllerConfigPath(manifestText: String): String? =
        pickControllerConfigPath(manifestText, CONTROLLER_TYPES)

    internal fun pickControllerConfigPath(manifestText: String, types: List<String>): String? {
        val lines = manifestText.lines()
        val pathRe = Regex("\"path\"\\s+\"([^\"]+)\"")
        for (type in types) {
            val start = lines.indexOfFirst { it.trim().startsWith("\"$type\"") }
            if (start < 0) continue
            // Scan the type's own line too (start, not start+1) so a same-line/inline manifest block
            // resolves; the `i > start` guard stops the next type being mistaken for it.
            for (i in start until lines.size) {
                val line = lines[i]
                if (i > start && line.trim().startsWith("\"controller_")) break
                pathRe.find(line)?.let { return it.groupValues[1] }
            }
        }
        return null
    }
}
