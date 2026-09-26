package com.winlator.star.ui.screens

/**
 * File naming for the GameNative/Daijisho frontend companions Bannerlator writes next to its
 * exported .desktop shortcut. GameNative exports one file per game whose extension identifies the
 * store and whose entire content is the numeric app id; Daijisho's bundled Steam platform scans
 * `.steamappid`. Kept pure so it is unit-testable.
 */
object SteamFrontendExport {
    fun desktopBaseName(desktopFileName: String): String = desktopFileName.removeSuffix(".desktop")

    fun companionNames(base: String): List<String> = listOf("$base.steam", "$base.steamappid")

    fun boxArtName(base: String): String = "$base.png"

    /**
     * Body of the `.steamappid` file. Daijisho's bundled Steam platform reads it as a TAG FILE, not a
     * bare id: the value must be `[steamappid] <id>`. (A bare id in `.steam` is ES-DE's convention.)
     */
    fun steamAppIdFileContent(appId: Int): String = "[steamappid] $appId"
}
