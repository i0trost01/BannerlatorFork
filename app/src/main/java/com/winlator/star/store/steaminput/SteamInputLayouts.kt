package com.winlator.star.store.steaminput

import android.content.Context
import java.io.File

/**
 * Resolves the controller layout VDF used to build a game's `steam_settings/controller/` files.
 * Precedence: a layout the app/user dropped at `<game>/.bannerlator/steaminput_layout.vdf`,
 * else the bundled identity layout in `assets/steaminput/gamepad.vdf`.
 *
 * The provided layout deliberately lives OUTSIDE `steam_settings/`: Goldberg's restore and every
 * OFF apply delete that directory wholesale, which would destroy a user-provided layout.
 */
object SteamInputLayouts {

    /** Relative path inside a game install where a provided layout is read from. */
    const val PROVIDED_REL = ".bannerlator/steaminput_layout.vdf"

    private const val BUNDLED_ASSET = "steaminput/gamepad.vdf"

    fun resolve(context: Context, installDir: File): String? {
        val provided = File(installDir, PROVIDED_REL)
        if (provided.isFile) {
            val text = runCatching { provided.readText() }.getOrNull()
            if (text != null) return text
        }
        return runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}
