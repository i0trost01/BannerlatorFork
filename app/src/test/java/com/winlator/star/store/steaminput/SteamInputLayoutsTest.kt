package com.winlator.star.store.steaminput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamInputLayoutsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── pickControllerConfigPath ──────────────────────────────────────────────────────────────────

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

    // ── chooseLayoutSource precedence ─────────────────────────────────────────────────────────────

    @Test
    fun providedWins() =
        assertEquals("P", SteamInputLayouts.chooseLayoutSource("P", "O", "B"))

    @Test
    fun ownBeatsBundled() =
        assertEquals("O", SteamInputLayouts.chooseLayoutSource(null, "O", "B"))

    @Test
    fun bundledLast() =
        assertEquals("B", SteamInputLayouts.chooseLayoutSource(null, null, "B"))

    @Test
    fun allNull_returnsNull() =
        assertNull(SteamInputLayouts.chooseLayoutSource(null, null, null))

    // ── discovery ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun findManifest_locatesUnityStreamingAssets_andResolvesConfig() {
        val root = tmp.newFolder("Monster Train 2")
        val si = File(root, "MonsterTrain2_Data/StreamingAssets/SteamInput").apply { mkdirs() }
        File(si, "steam_input_manifest.vdf").writeText(
            "\"Action Manifest\"\n{\n\"configurations\"\n{\n\"controller_xboxone\"\n{\n\"0\" { \"path\" \"cfg.vdf\" }\n}\n}\n}\n",
        )
        File(si, "cfg.vdf").writeText("\"controller_mappings\"\n{\n\"actions\" { \"GameControls\" { } }\n}\n")

        assertNotNull(SteamInputLayouts.findManifest(root))
        assertTrue(SteamInputLayouts.hasOwnManifest(root))
        val cfg = SteamInputLayouts.resolveOwnManifestConfig(root)
        assertTrue("expected the game's config VDF text", cfg != null && cfg.contains("controller_mappings"))
    }

    @Test
    fun findManifest_absent_returnsNull_andDoesNotSearchSiblings() {
        val parent = tmp.newFolder("steam_games")
        val mine = File(parent, "MyGame").apply { mkdirs() }
        File(mine, "game.exe").writeText("MZ")
        // A neighbouring game's manifest must NOT be picked up.
        val sibling = File(parent, "OtherGame/MonsterTrain2_Data/StreamingAssets/SteamInput").apply { mkdirs() }
        File(sibling, "steam_input_manifest.vdf").writeText("\"Action Manifest\"\n{\n}\n")

        assertNull(SteamInputLayouts.findManifest(mine))
        assertTrue(!SteamInputLayouts.hasOwnManifest(mine))
    }
}
