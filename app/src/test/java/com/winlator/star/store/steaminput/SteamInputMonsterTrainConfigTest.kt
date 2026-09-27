package com.winlator.star.store.steaminput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Monster Train 2 mapping: its own `controller_xboxone` config declares an action set
 * `GameControls` with `game_action` bindings, which the converter must turn into the action-set file
 * gbe_fork reads. Mirrors the real `config_2742830_controller_xboxone.vdf` (condensed).
 */
class SteamInputMonsterTrainConfigTest {

    private val config = """
        "controller_mappings"
        {
            "controller_type" "controller_xboxone"
            "actions"
            {
                "GameControls"
                {
                    "Button" { "Action1" "#a" "Action2" "#b" "Action3" "#c" "Action4" "#d" "START" "#s" "Back" "#v" "LeftBumper" "#lb" "RightBumper" "#rb" }
                }
            }
            "group"
            {
                "id" "10"
                "mode" "switches"
                "inputs"
                {
                    "button_escape" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls START, , " } } } }
                    "button_menu" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls Back, , " } } } }
                    "left_bumper" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls LeftBumper, , " } } } }
                    "right_bumper" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls RightBumper, , " } } } }
                }
            }
            "group"
            {
                "id" "11"
                "mode" "four_buttons"
                "inputs"
                {
                    "button_a" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls Action1, , " } } } }
                    "button_b" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls Action2, , " } } } }
                    "button_x" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls Action3, , " } } } }
                    "button_y" { "activators" { "Full_Press" { "bindings" { "binding" "game_action GameControls Action4, , " } } } }
                }
            }
            "group"
            {
                "id" "15"
                "mode" "joystick_move"
                "inputs" { }
                "gameactions" { "GameControls" "LeftStick" }
            }
            "group"
            {
                "id" "14"
                "mode" "joystick_move"
                "inputs" { }
                "gameactions" { "GameControls" "LeftStick" }
            }
            "preset"
            {
                "id" "0"
                "name" "GameControls"
                "group_source_bindings"
                {
                    "10" "switch active"
                    "11" "button_diamond active"
                    "15" "joystick active"
                    "14" "dpad active"
                }
            }
        }
    """.trimIndent()

    @Test
    fun monsterTrainMapping_emitsGameControlsActionSet() {
        val out = SteamInputVdfConverter.convert(config)
        val content = out["GameControls"]
        assertTrue("expected a 'GameControls' action set, got keys=${out.keys}", content != null)

        val lines = content!!.trim().split('\n')
        assertEquals("START=START", lines[0])
        assertEquals("Back=BACK", lines[1])
        assertEquals("LeftBumper=LBUMPER", lines[2])
        assertEquals("RightBumper=RBUMPER", lines[3])
        assertEquals("Action1=A", lines[4])
        assertEquals("Action2=B", lines[5])
        assertEquals("Action3=X", lines[6])
        assertEquals("Action4=Y", lines[7])
        // The dpad group also mapped to LeftStick must NOT append a second analog binding.
        assertEquals("LeftStick=LJOY=joystick_move", lines[8])
        assertEquals(9, lines.size)
    }
}
