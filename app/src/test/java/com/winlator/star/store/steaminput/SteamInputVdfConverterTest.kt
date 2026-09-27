package com.winlator.star.store.steaminput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamInputVdfConverterTest {

    @Test
    fun missingControllerMappings_returnsEmpty() {
        assertTrue(SteamInputVdfConverter.convert("\"foo\"\n{\n}\n").isEmpty())
    }

    @Test
    fun buttonDiamondGameAction_emitsActionButtonLine() {
        val vdf = """
            "controller_mappings"
            {
                "actions"
                {
                    "InGame"
                    {
                    }
                }
                "group"
                {
                    "id" "1"
                    "mode" "button_diamond"
                    "inputs"
                    {
                        "button_a"
                        {
                            "activators"
                            {
                                "Full_Press"
                                {
                                    "bindings"
                                    {
                                        "binding" "game_action InGame Jump"
                                    }
                                }
                            }
                        }
                    }
                }
                "preset"
                {
                    "name" "InGame"
                    "group_source_bindings"
                    {
                        "1" "button_diamond active"
                    }
                }
            }
        """.trimIndent()

        assertEquals(mapOf("InGame" to "Jump=A\n"), SteamInputVdfConverter.convert(vdf))
    }

    @Test
    fun unpresettableName_isSkipped() {
        val vdf = """
            "controller_mappings"
            {
                "actions" { "InGame" { } }
                "preset"
                {
                    "name" "NotAnActionSet"
                    "group_source_bindings" { "1" "button_diamond active" }
                }
            }
        """.trimIndent()
        assertTrue(SteamInputVdfConverter.convert(vdf).isEmpty())
    }
}
