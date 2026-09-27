package com.winlator.star.store.steaminput

import org.junit.Assert.assertTrue
import org.junit.Test

class SteamInputRealSteamDiagTest {

    private val vdf = """
        "UserLocalConfigStore"
        {
            "system"
            {
                "SteamController_XBoxSupport"       "1"
                "SteamController_GenericGamepadSupport"     "1"
                "EnableGameOverlay"     "0"
            }
            "apps"
            {
                "2742830"
                {
                    "UseSteamControllerConfig"      "2"
                }
            }
        }
    """.trimIndent()

    @Test
    fun extractsSteamInputKeysAndPerAppFlag() {
        val joined = SteamInputDiagnostics.localConfigSteamInputLines(vdf, 2742830).joinToString("\n")
        assertTrue(joined.contains("SteamController_XBoxSupport"))
        assertTrue(joined.contains("SteamController_GenericGamepadSupport"))
        assertTrue(joined.contains("UseSteamControllerConfig"))
        assertTrue(joined.contains("has apps/2742830 block=true"))
        assertTrue("unrelated keys must not be reported", !joined.contains("EnableGameOverlay"))
    }

    @Test
    fun emptyContent_reportsNoneAndFalseFlag() {
        val lines = SteamInputDiagnostics.localConfigSteamInputLines("", 123)
        assertTrue(lines.any { it.contains("(no Steam Input keys found)") })
        assertTrue(lines.any { it.contains("has apps/123 block=false") })
    }
}
