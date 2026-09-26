package com.winlator.star.ui

import androidx.compose.runtime.mutableStateOf

/**
 * TEMPORARY diagnostic: the last few "menu-ish" gamepad buttons (key code + scan code), recorded by
 * XServerDisplayActivity and shown in the drawer's Advanced tab so the codes can be read in-app
 * (a toast is dark-on-dark over some game surfaces). Remove once the Back/B mapping is settled.
 */
object GamepadKeyDiag {
    val entries = mutableStateOf(listOf<String>())

    fun record(s: String) {
        entries.value = (listOf(s) + entries.value).take(8)
    }
}
