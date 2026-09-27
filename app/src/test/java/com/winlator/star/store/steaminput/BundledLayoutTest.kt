package com.winlator.star.store.steaminput

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BundledLayoutTest {

    private val expectedDefault = """
        A=A
        B=B
        X=X
        Y=Y
        LBUMPER=LBUMPER
        RBUMPER=RBUMPER
        BACK=BACK
        START=START
        DUP=DUP
        DDOWN=DDOWN
        DLEFT=DLEFT
        DRIGHT=DRIGHT
        LJOY=LJOY=joystick_move
        LSTICK=LSTICK
        RJOY=RJOY=joystick_move
        RSTICK=RSTICK
    """.trimIndent() + "\n"

    @Test
    fun bundledGamepadLayout_convertsToExactDefaultActionSet() {
        val asset = listOf(
            File("src/main/assets/steaminput/gamepad.vdf"),
            File("app/src/main/assets/steaminput/gamepad.vdf"),
        ).firstOrNull { it.isFile }
            ?: error(
                "bundled asset steaminput/gamepad.vdf not found (cwd=${File(".").absolutePath}); " +
                    "expected src/main/assets/steaminput/gamepad.vdf or app/src/main/assets/steaminput/gamepad.vdf"
            )

        val converted = SteamInputVdfConverter.convert(asset.readText())
        assertEquals(setOf("Default"), converted.keys)
        assertEquals(expectedDefault, converted["Default"])
    }
}
