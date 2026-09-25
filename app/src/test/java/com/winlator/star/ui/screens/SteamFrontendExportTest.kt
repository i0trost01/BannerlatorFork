package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamFrontendExportTest {
    @Test fun baseNameStripsDesktopSuffix() {
        assertEquals("Hades", SteamFrontendExport.desktopBaseName("Hades.desktop"))
    }

    @Test fun baseNameKeepsInnerDots() {
        assertEquals("Nioh 2.0", SteamFrontendExport.desktopBaseName("Nioh 2.0.desktop"))
    }

    @Test fun companionNamesAreSteamAndSteamAppId() {
        assertEquals(
            listOf("Hades.steam", "Hades.steamappid"),
            SteamFrontendExport.companionNames("Hades"),
        )
    }
}
