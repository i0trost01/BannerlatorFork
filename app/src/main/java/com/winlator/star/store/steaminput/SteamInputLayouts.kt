package com.winlator.star.store.steaminput

import android.content.Context
import java.io.File

/**
 * Resolves the controller layout VDF used to build a game's `steam_settings/controller/` files.
 * Precedence: a layout the app/user dropped at `<game>/steam_settings/steaminput_layout.vdf`,
 * else the bundled identity layout in `assets/steaminput/gamepad.vdf`.
 */
object SteamInputLayouts {

    /** Relative path inside a game install where a provided layout is read from. */
    const val PROVIDED_REL = "steam_settings/steaminput_layout.vdf"

    private const val BUNDLED_ASSET = "steaminput/gamepad.vdf"

    fun resolve(context: Context, installDir: File): String? {
        val provided = File(installDir, PROVIDED_REL)
        if (provided.isFile) return runCatching { provided.readText() }.getOrNull()
        return runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}
