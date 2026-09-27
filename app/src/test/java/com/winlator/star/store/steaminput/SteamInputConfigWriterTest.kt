package com.winlator.star.store.steaminput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamInputConfigWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val oneActionSetVdf = """
        "controller_mappings"
        {
            "actions" { "InGame" { } }
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
                "group_source_bindings" { "1" "button_diamond active" }
            }
        }
    """.trimIndent()

    @Test
    fun write_createsControllerFileWithExactContent() {
        val install = tmp.newFolder("Game")
        val n = SteamInputConfigWriter.write(install, oneActionSetVdf)
        assertEquals(1, n)
        val f = File(install, "steam_settings/controller/InGame.txt")
        assertTrue(f.isFile)
        assertEquals("Jump=A\n", f.readText())
    }

    @Test
    fun write_withNoActionSets_writesNothing_andDoesNotCreateDir() {
        val install = tmp.newFolder("Game")
        val n = SteamInputConfigWriter.write(install, "\"controller_mappings\"\n{\n}\n")
        assertEquals(0, n)
        assertFalse(File(install, "steam_settings/controller").exists())
    }

    @Test
    fun clear_removesControllerDirIdempotently() {
        val install = tmp.newFolder("Game")
        SteamInputConfigWriter.write(install, oneActionSetVdf)
        SteamInputConfigWriter.clear(install)
        assertFalse(File(install, "steam_settings/controller").exists())
        // second clear is a no-op, must not throw
        SteamInputConfigWriter.clear(install)
    }

    @Test
    fun sanitizeActionSetName_stripsTraversalAndUnsafeChars() {
        assertEquals("_", SteamInputConfigWriter.sanitizeActionSetName("."))
        assertEquals("_", SteamInputConfigWriter.sanitizeActionSetName(".."))
        assertEquals(".._evil", SteamInputConfigWriter.sanitizeActionSetName("../evil"))
        assertEquals("a_b", SteamInputConfigWriter.sanitizeActionSetName("a/b"))
        assertEquals("Default", SteamInputConfigWriter.sanitizeActionSetName("Default"))
        assertEquals("a_b-c.d_e", SteamInputConfigWriter.sanitizeActionSetName("a b-c.d/e"))
    }

    @Test
    fun write_clearsStaleActionSetsFromPreviousLayout() {
        val install = tmp.newFolder("Game")
        SteamInputConfigWriter.write(install, oneActionSetVdf)
        assertTrue(File(install, "steam_settings/controller/InGame.txt").isFile)

        val otherLayout = """
            "controller_mappings"
            {
                "actions" { "Menu" { } }
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
                                        "binding" "game_action Menu Jump"
                                    }
                                }
                            }
                        }
                    }
                }
                "preset"
                {
                    "name" "Menu"
                    "group_source_bindings" { "1" "button_diamond active" }
                }
            }
        """.trimIndent()

        SteamInputConfigWriter.write(install, otherLayout)
        assertFalse(File(install, "steam_settings/controller/InGame.txt").exists())
        assertTrue(File(install, "steam_settings/controller/Menu.txt").isFile)
    }
}
