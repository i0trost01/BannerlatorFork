package com.winlator.star.store.steaminput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamInputLayoutsTest {

    private val manifest = """
        "Action Manifest"
        {
            "configurations"
            {
                "controller_xboxone"
                {
                    "0"
                    {
                        "path" "config_2742830_controller_xboxone.vdf"
                    }
                }
                "controller_ps5"
                {
                    "0"
                    {
                        "path" "config_2742830_controller_ps5.vdf"
                    }
                }
            }
        }
    """.trimIndent()

    @Test
    fun picksXboxOneConfig() =
        assertEquals(
            "config_2742830_controller_xboxone.vdf",
            SteamInputLayouts.pickControllerConfigPath(manifest),
        )

    @Test
    fun fallsBackToXbox360WhenNoXboxOne() {
        val m = """
            "Action Manifest"
            {
                "configurations"
                {
                    "controller_xbox360"
                    {
                        "0" { "path" "c360.vdf" }
                    }
                }
            }
        """.trimIndent()
        assertEquals("c360.vdf", SteamInputLayouts.pickControllerConfigPath(m))
    }

    @Test
    fun noControllerBlock_returnsNull() =
        assertNull(SteamInputLayouts.pickControllerConfigPath("\"Action Manifest\"\n{\n}\n"))
}
