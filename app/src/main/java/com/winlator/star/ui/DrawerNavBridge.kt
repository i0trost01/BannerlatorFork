package com.winlator.star.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.inputcontrols.DrawerNavModel

/**
 * Observable state shared between the Activity's controller input (writer) and the drawer
 * Composable (reader). The Activity owns the rules (DrawerNavModel) and only pushes positions and
 * an activation counter here; the Composable renders the highlight and performs the selection.
 * Deliberately dumb: everything worth testing lives in DrawerNavModel.
 */
object DrawerNavBridge {

    var level by mutableIntStateOf(DrawerNavModel.LEVEL_RAIL)
    var railIndex by mutableIntStateOf(0)
    var panelRow by mutableIntStateOf(0)
    var panelCol by mutableIntStateOf(0)

    // Reported by the Composable so the Activity can clamp movement without knowing the layout.
    var railCount by mutableIntStateOf(0)
    var panelRowCount by mutableIntStateOf(0)
    var panelColCount by mutableIntStateOf(0)

    // Bumped to ask the Composable to activate the currently highlighted item.
    var activateSignal by mutableIntStateOf(0)

    // The level that was active when activate() was last called. The Composable's LaunchedEffect
    // runs after the Activity has already descended rail -> panel, so it cannot read `level` to
    // decide what A was pressed on; it reads this captured value instead.
    var lastActivatedLevel by mutableIntStateOf(DrawerNavModel.LEVEL_RAIL)

    fun resetOnOpen() {
        level = DrawerNavModel.LEVEL_RAIL
        railIndex = 0
        panelRow = 0
        panelCol = 0
        lastActivatedLevel = DrawerNavModel.LEVEL_RAIL
    }

    fun moveRail(delta: Int) {
        railIndex = DrawerNavModel.clampRailIndex(railIndex + delta, railCount)
    }

    fun railToPanel() {
        level = DrawerNavModel.LEVEL_PANEL
        panelRow = 0
        panelCol = 0
    }

    fun panelToRail() {
        level = DrawerNavModel.LEVEL_RAIL
    }

    fun movePanel(move: DrawerNavModel.PanelMove) {
        when (move) {
            DrawerNavModel.PanelMove.UP_FROM_FIRST_ROW -> panelToRail()
            DrawerNavModel.PanelMove.UP -> panelRow--
            DrawerNavModel.PanelMove.DOWN -> {
                panelRow++
                panelCol = 0
            }
            DrawerNavModel.PanelMove.LEFT -> panelCol--
            DrawerNavModel.PanelMove.RIGHT -> panelCol++
            DrawerNavModel.PanelMove.NONE -> Unit
        }
    }

    fun activate() {
        lastActivatedLevel = level
        activateSignal++
    }
}
