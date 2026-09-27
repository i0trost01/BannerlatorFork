# Steam Input (Goldberg Path) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Bannerlator a per-game "Use Steam Input" toggle that makes gbe_fork's Steam Input emulation answer a game's `ISteamInput` action queries, so games that hand input to Steam Input get a working pad on the Goldberg (offline emulated) launch path.

**Architecture:** Port GameNative's Steam-controller-layout converter (`SteamControllerVdfUtils`) into Bannerlator as a pure, testable object; add a writer that emits the gbe_fork `steam_settings/controller/<ActionSet>.txt` files; add a per-game preference (SteamPrefs, keyed by appId, mirroring `goldbergMode`); wire the write/clear into `GoldbergPatcher`'s apply/restore; and expose a toggle in the launch-method sheet on the Goldberg branch. gbe_fork auto-enables Steam Input when action sets are present, so no `configs.app.ini` change is required. The layout VDF is supplied by a resolver: a user-provided/externally-provided VDF first, else a bundled fallback identity layout.

**Tech Stack:** Android (Kotlin + Java), Compose, JUnit4 (JVM unit tests), gbe_fork (`steam_settings`), Steam controller `controller_mappings` VDF.

**Spec:** No separate spec file — the design (scope decisions: Goldberg path only; auto-on deferred to Phase 2; layout source precedence template-first) is captured in this plan's Architecture and Global Constraints.

---

## Scope And Phasing (read first)

This plan is **Phase 1 of 2** of the GameNative "Use Steam Input" feature.

GameNative's version (verified from `utkarshdalal/GameNative` `master`) does five things:
1. resolves a controller layout VDF (the user's Steam config → the game's own Steam Input manifest → a built-in template);
2. converts it to `steam_settings/controller/<preset>.txt` (`SteamControllerVdfUtils.generateControllerConfig`);
3. writes a headless-host layout `Steam\steamhost_controller_<appid>.vdf`;
4. flips `localconfig.vdf` keys (`SteamController_XBoxSupport=1`, `SteamController_GenericGamepadSupport=1`, `apps/<appid>/UseSteamControllerConfig=2`);
5. auto-enables when the game ships its own Steam Input manifest.

gbe_fork's controller feature needs the game's **actual** action-set and action names (`ACTION_NAME=BUTTON_NAME`) — a generic template only works if a game's actions happen to be named after XInput buttons. So the *truly useful* layout source is the game's own layout (`steamcontrollerconfigdetails` from PICS) or the user's config; those require PICS/engine work and are **Phase 2** (see "Follow-on plans").

**Phase 1 (this plan)** builds and device-verifies the whole mechanism: converter, writer, per-game toggle, gbe_fork integration, and a layout resolver whose first source is a VDF the app/user provides (path `steam_settings/steaminput_layout.vdf` inside the game install) with a bundled fallback. Phase 1 is independently shippable and testable; Phase 2 only swaps in richer layout sources.

## Global Constraints

- **Launch path:** Goldberg (gbe_fork) only. Do NOT touch `RealSteamLauncher` / SteamLite.
- **gbe_fork feature gate:** controller files are only honoured by gbe_fork's **Windows experimental** build (Bannerlator's `GoldbergMode.EXPERIMENTAL`) and Linux builds. The toggle is meaningful only with `EXPERIMENTAL`; the UI copy must say so.
- **File format (verbatim from gbe_fork README):** one `steam_settings/controller/<ACTION_SET_NAME>.txt` per action set. Digital: `ACTION_NAME=BUTTON_NAME`. Analog: `ACTION_NAME=ANALOG_NAME=input source mode`. Multiple buttons comma-separated: `ACTION_NAME=A,B`. Valid digital names: `DUP, DDOWN, DLEFT, DRIGHT, START, BACK, LSTICK, RSTICK, LBUMPER, RBUMPER, A, B, X, Y, DLTRIGGER, DRTRIGGER, DLJOYUP, DLJOYDOWN, DLJOYLEFT, DRJOYRIGHT`. Valid analog names: `LTRIGGER, RTRIGGER, LJOY, RJOY, DPAD`.
- **No auto-enable in Phase 1.** It is a manual per-game toggle. (Auto-on for own-manifest games is Phase 2.)
- **No Android APIs in the converter or writer.** They must be pure JVM so JUnit unit tests run without Robolectric.
- **Preference storage:** `SteamPrefs` (keyed by appId), NOT `SteamDatabase` — avoids a Room migration + versionCode bump, exactly like `goldbergMode`.
- **Style:** match the surrounding file's naming/indentation. Kotlin files use 4-space indent. No comments except where the surrounding code already uses explanatory comments.
- **Test command:** `.\gradlew :app:testStandardDebugUnitTest` (flavors are `standard`/`ludashi`/`pubg`).
- **Commits:** one per task, message style `feat(steam-input): <what>`.

---

## File Structure

| File | Create/Modify | Responsibility |
|------|---------------|----------------|
| `app/src/main/java/com/winlator/star/store/steaminput/SteamInputVdfConverter.kt` | Create | Pure: `controller_mappings` VDF text → `Map<actionSetName, fileText>`. Port of GameNative's logic. |
| `app/src/main/java/com/winlator/star/store/steaminput/SteamInputConfigWriter.kt` | Create | Pure: write/clear `steam_settings/controller/` from a VDF text via the converter. |
| `app/src/main/java/com/winlator/star/store/steaminput/SteamInputLayouts.kt` | Create | Resolve a layout VDF for an install dir asset-first-then-bundled; owns the bundled asset name. |
| `app/src/main/assets/steaminput/gamepad.vdf` | Create | Bundled fallback layout (`controller_mappings`, XInput identity). |
| `app/src/test/java/com/winlator/star/store/steaminput/SteamInputVdfConverterTest.kt` | Create | JVM tests for the converter. |
| `app/src/test/java/com/winlator/star/store/steaminput/SteamInputConfigWriterTest.kt` | Create | JVM tests for the writer. |
| `app/src/main/java/com/winlator/star/store/SteamPrefs.kt` | Modify | Add `getUseSteamInput(appId)` / `setUseSteamInput(appId, v)`. |
| `app/src/main/java/com/winlator/star/store/GoldbergPatcher.kt` | Modify | On apply: write controller files when the pref is on; on restore/off: clear them. |
| `app/src/main/java/com/winlator/star/ui/screens/LaunchMethodSheet.kt` | Modify | Add the Goldberg-only "Use Steam Input" toggle row; persist via SteamPrefs. |

---

## Task 1: Port the VDF → action-set converter

**Files:**
- Create: `app/src/main/java/com/winlator/star/store/steaminput/SteamInputVdfConverter.kt`
- Test: `app/src/test/java/com/winlator/star/store/steaminput/SteamInputVdfConverterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object SteamInputVdfConverter { fun convert(vdfText: String): Map<String, String> }` — keys are action-set file names, values are the exact file contents to drop into `steam_settings/controller/`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/steaminput/SteamInputVdfConverterTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.store.steaminput.SteamInputVdfConverterTest"`
Expected: FAIL — `SteamInputVdfConverter` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/winlator/star/store/steaminput/SteamInputVdfConverter.kt`:

```kotlin
package com.winlator.star.store.steaminput

/**
 * Converts a Steam controller layout (`controller_mappings` VDF) into the gbe_fork action-set
 * files consumed from `steam_settings/controller/`. Port of GameNative's SteamControllerVdfUtils
 * (GPL-3.0), minus its Timber logging so it stays pure JVM.
 */
object SteamInputVdfConverter {

    private val keymapDigital = mapOf(
        "button_a" to "A",
        "button_b" to "B",
        "button_x" to "X",
        "button_y" to "Y",
        "dpad_north" to "DUP",
        "dpad_south" to "DDOWN",
        "dpad_east" to "DRIGHT",
        "dpad_west" to "DLEFT",
        "button_escape" to "START",
        "button_menu" to "BACK",
        "left_bumper" to "LBUMPER",
        "right_bumper" to "RBUMPER",
        "button_back_left" to "A",
        "button_back_right" to "X",
        "button_back_left_upper" to "B",
        "button_back_right_upper" to "Y",
    )

    fun convert(vdfText: String): Map<String, String> {
        val root = VdfParser(vdfText).parse()
        val controllerMappings = root.getObject("controller_mappings") ?: return emptyMap()

        val groupsById = LinkedHashMap<String, VdfObject>()
        controllerMappings.getObjects("group").forEach { group ->
            group.getString("id")?.let { groupsById[it] = group }
        }

        val actionList = mutableListOf<String>()
        controllerMappings.getObjects("actions").forEach { actions ->
            actionList.addAll(actions.keys())
        }

        val presets = controllerMappings.getObjects("preset")
        val presetsByName = presets.mapNotNull { preset ->
            preset.getString("name")?.let { name -> name to preset }
        }.toMap()

        val allBindings = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()

        for (preset in presets) {
            val name = preset.getString("name") ?: continue
            if (!actionList.contains(name) && name.lowercase() != "default") continue
            allBindings[name] = buildPresetBindings(name, preset, groupsById)
        }

        controllerMappings.getObject("action_layers")?.keys()?.forEach { layerName ->
            val preset = presetsByName[layerName] ?: return@forEach
            allBindings[layerName] = buildPresetBindings(layerName, preset, groupsById)
        }

        val out = LinkedHashMap<String, String>()
        for ((presetName, bindings) in allBindings) {
            if (bindings.isEmpty()) continue
            val content = buildString {
                for ((actionName, actionBindings) in bindings) {
                    append(actionName)
                    append("=")
                    appendLine(actionBindings.joinToString(","))
                }
            }
            out[presetName] = content
        }
        return out
    }

    private fun addInputBindings(
        group: VdfObject,
        bindings: MutableMap<String, MutableList<String>>,
        forceBinding: String? = null,
        keymap: Map<String, String> = keymapDigital,
    ) {
        val inputs = group.getObject("inputs") ?: return
        for ((inputName, inputValue) in inputs.objectEntries()) {
            for (activator in inputValue.objectValues()) {
                for (fullPress in activator.objectValues()) {
                    for (bindingGroup in fullPress.objectValues()) {
                        for ((bindingKey, bindingValue) in bindingGroup.stringEntries()) {
                            if (!bindingKey.equals("binding", ignoreCase = true)) continue
                            val tokens = bindingValue.split(Regex("\\s+"))
                            if (tokens.isEmpty()) continue

                            val actionName = when (tokens[0].lowercase()) {
                                "game_action" -> tokens.getOrNull(2)?.trimEnd(',')
                                "xinput_button" -> tokens.getOrNull(1)?.trimEnd(',')
                                else -> null
                            }
                            if (actionName.isNullOrEmpty()) continue

                            val binding = forceBinding ?: keymap[inputName.lowercase()]
                            if (binding.isNullOrEmpty()) continue

                            val list = bindings.getOrPut(actionName) { mutableListOf() }
                            if (!list.contains(binding)) list.add(binding)
                        }
                    }
                }
            }
        }
    }

    private fun addActionBinding(
        bindings: MutableMap<String, MutableList<String>>,
        actionName: String,
        binding: String,
        bindingSuffix: String,
    ) {
        val list = bindings.getOrPut(actionName) { mutableListOf() }
        val bindingWithSuffix = "$binding=$bindingSuffix"
        if (!list.contains(binding) && !list.contains(bindingWithSuffix)) {
            if (list.isEmpty()) list.add(bindingWithSuffix) else list.add(0, binding)
        }
    }

    private fun buildPresetBindings(
        presetName: String,
        preset: VdfObject,
        groupsById: Map<String, VdfObject>,
    ): LinkedHashMap<String, MutableList<String>> {
        val groupBindings = preset.getObject("group_source_bindings") ?: return LinkedHashMap()
        val bindings = LinkedHashMap<String, MutableList<String>>()

        for ((groupId, groupBinding) in groupBindings.stringEntries()) {
            val tokens = groupBinding.split(Regex("\\s+"))
            if (tokens.size < 2 || tokens[1].lowercase() != "active") continue

            val group = groupsById[groupId] ?: continue
            val groupMode = group.getString("mode")?.lowercase().orEmpty()
            val bindingType = tokens[0].lowercase()

            if (bindingType in listOf("switch", "button_diamond", "dpad")) {
                addInputBindings(group, bindings)
            }

            if (bindingType in listOf("left_trigger", "right_trigger")) {
                if (groupMode == "trigger") {
                    val actionName = group.getObject("gameactions")?.getString(presetName)
                    if (!actionName.isNullOrEmpty()) {
                        val binding = if (bindingType == "left_trigger") "LTRIGGER" else "RTRIGGER"
                        addActionBinding(bindings, actionName, binding, bindingSuffix = "trigger")
                    }
                    val forceBinding = if (bindingType == "left_trigger") "DLTRIGGER" else "DRTRIGGER"
                    addInputBindings(group, bindings, forceBinding = forceBinding)
                }
            }

            if (bindingType in listOf("joystick", "right_joystick", "dpad")) {
                if (groupMode == "joystick_move") {
                    val actionName = group.getObject("gameactions")?.getString(presetName)
                    if (!actionName.isNullOrEmpty()) {
                        val binding = when (bindingType) {
                            "joystick" -> "LJOY"
                            "right_joystick" -> "RJOY"
                            "dpad" -> "DPAD"
                            else -> ""
                        }
                        if (binding.isNotEmpty()) {
                            addActionBinding(bindings, actionName, binding, bindingSuffix = "joystick_move")
                        }
                    }
                    val forceBinding = if (bindingType == "joystick") "LSTICK" else "RSTICK"
                    addInputBindings(group, bindings, forceBinding = forceBinding)
                } else if (groupMode == "dpad") {
                    if (bindingType == "joystick") {
                        addInputBindings(group, bindings, keymap = mapOf(
                            "dpad_north" to "DLJOYUP",
                            "dpad_south" to "DLJOYDOWN",
                            "dpad_west" to "DLJOYLEFT",
                            "dpad_east" to "DLJOYRIGHT",
                            "click" to "LSTICK",
                        ))
                    } else if (bindingType == "right_joystick") {
                        addInputBindings(group, bindings, keymap = mapOf(
                            "dpad_north" to "DRJOYUP",
                            "dpad_south" to "DRJOYDOWN",
                            "dpad_west" to "DRJOYLEFT",
                            "dpad_east" to "DRJOYRIGHT",
                            "click" to "RSTICK",
                        ))
                    }
                }
            }
        }
        return bindings
    }
}

private sealed interface VdfValue

private data class VdfEntry(val key: String, val value: VdfValue)

private data class VdfString(val value: String) : VdfValue

private class VdfObject : VdfValue {
    private val entries = mutableListOf<VdfEntry>()

    fun add(key: String, value: VdfValue) {
        entries.add(VdfEntry(key, value))
    }

    fun getObject(key: String): VdfObject? = getObjects(key).firstOrNull()

    fun getObjects(key: String): List<VdfObject> = entries.mapNotNull {
        if (it.key == key && it.value is VdfObject) it.value else null
    }

    fun getString(key: String): String? = getStrings(key).firstOrNull()

    fun getStrings(key: String): List<String> = entries.mapNotNull {
        if (it.key == key && it.value is VdfString) it.value.value else null
    }

    fun objectEntries(): List<Pair<String, VdfObject>> = entries.mapNotNull {
        if (it.value is VdfObject) it.key to it.value else null
    }

    fun objectValues(): List<VdfObject> = entries.mapNotNull { it.value as? VdfObject }

    fun stringEntries(): List<Pair<String, String>> = entries.mapNotNull {
        if (it.value is VdfString) it.key to it.value.value else null
    }

    fun keys(): List<String> = entries.map { it.key }
}

private class VdfParser(text: String) {
    private val source = if (text.startsWith("\uFEFF")) text.substring(1) else text
    private var index = 0

    fun parse(): VdfObject = parseObject()

    private fun parseObject(): VdfObject {
        val obj = VdfObject()
        while (true) {
            val token = nextToken() ?: break
            if (token == "}") break
            val key = token
            val valueToken = nextToken() ?: break
            if (valueToken == "{") {
                obj.add(key, parseObject())
            } else if (valueToken == "}") {
                break
            } else {
                obj.add(key, VdfString(valueToken))
            }
        }
        return obj
    }

    private fun nextToken(): String? {
        skipWhitespaceAndComments()
        if (index >= source.length) return null
        return when (val ch = source[index]) {
            '{', '}' -> {
                index++
                ch.toString()
            }
            '"' -> parseQuoted()
            else -> parseUnquoted()
        }
    }

    private fun parseQuoted(): String {
        index++
        val sb = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            if (ch == '"') break
            if (ch == '\\' && index < source.length) {
                val escaped = source[index++]
                sb.append(unescapeChar(escaped))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseUnquoted(): String {
        val start = index
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace() || ch == '{' || ch == '}') break
            index++
        }
        return unescape(source.substring(start, index))
    }

    private fun skipWhitespaceAndComments() {
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace()) {
                index++
                continue
            }
            if (ch == '/' && index + 1 < source.length && source[index + 1] == '/') {
                index += 2
                while (index < source.length && source[index] != '\n') index++
                continue
            }
            break
        }
    }

    private fun unescapeChar(ch: Char): Char = when (ch) {
        'n' -> '\n'
        't' -> '\t'
        'v' -> '\u000B'
        'b' -> '\b'
        'r' -> '\r'
        'f' -> '\u000C'
        'a' -> '\u0007'
        '\\' -> '\\'
        '?' -> '?'
        '"' -> '"'
        '\'' -> '\''
        else -> ch
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                sb.append(unescapeChar(value[i + 1]))
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.store.steaminput.SteamInputVdfConverterTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/steaminput/SteamInputVdfConverter.kt app/src/test/java/com/winlator/star/store/steaminput/SteamInputVdfConverterTest.kt
git commit -m "feat(steam-input): port controller-mappings VDF to gbe_fork action-set converter"
```

---

## Task 2: Write/clear `steam_settings/controller/`

**Files:**
- Create: `app/src/main/java/com/winlator/star/store/steaminput/SteamInputConfigWriter.kt`
- Test: `app/src/test/java/com/winlator/star/store/steaminput/SteamInputConfigWriterTest.kt`

**Interfaces:**
- Consumes: `SteamInputVdfConverter.convert(vdfText): Map<String, String>`.
- Produces:
  - `object SteamInputConfigWriter { fun write(installDir: File, vdfText: String): Int; fun clear(installDir: File) }`
  - `write` returns the number of action-set files written, and returns `0` (writing nothing) when `vdfText` yields no action sets.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/steaminput/SteamInputConfigWriterTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.store.steaminput.SteamInputConfigWriterTest"`
Expected: FAIL — `SteamInputConfigWriter` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/winlator/star/store/steaminput/SteamInputConfigWriter.kt`:

```kotlin
package com.winlator.star.store.steaminput

import java.io.File

/**
 * Drops the gbe_fork action-set files (`steam_settings/controller/<ActionSet>.txt`) beside a game's
 * steam_api dll. gbe_fork auto-enables its Steam Input emulation when this folder has action sets.
 * Pure JVM (no Android APIs) so it is unit-testable.
 */
object SteamInputConfigWriter {

    internal const val CONTROLLER_DIR = "steam_settings/controller"

    /** Writes one file per action set. Returns the number of files written; 0 = nothing to do. */
    fun write(installDir: File, vdfText: String): Int {
        val sets = SteamInputVdfConverter.convert(vdfText)
        if (sets.isEmpty()) return 0
        val dir = File(installDir, CONTROLLER_DIR).apply { mkdirs() }
        var written = 0
        for ((setName, content) in sets) {
            File(dir, "$setName.txt").writeText(content)
            written++
        }
        return written
    }

    /** Removes the controller folder. Safe when absent. */
    fun clear(installDir: File) {
        File(installDir, CONTROLLER_DIR).takeIf { it.isDirectory }?.deleteRecursively()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.store.steaminput.SteamInputConfigWriterTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/steaminput/SteamInputConfigWriter.kt app/src/test/java/com/winlator/star/store/steaminput/SteamInputConfigWriterTest.kt
git commit -m "feat(steam-input): write and clear gbe_fork steam_settings/controller files"
```

---

## Task 3: Bundled fallback layout + resolver

**Files:**
- Create: `app/src/main/assets/steaminput/gamepad.vdf`
- Create: `app/src/main/java/com/winlator/star/store/steaminput/SteamInputLayouts.kt`

**Interfaces:**
- Consumes: an install dir `File`, a `Context` (to read the bundled asset).
- Produces:
  - `object SteamInputLayouts { fun resolve(context: Context, installDir: File): String? }` — returns layout VDF text: the install's `steam_settings/steaminput_layout.vdf` when present, else the bundled asset; `null` only if the asset is missing.

**Note:** the bundled layout is an XInput **identity** layout (actions named after XInput buttons). It only produces matching action names for games whose Steam Input actions are named that way. It exists so the mechanism is exercised and so a user/config-provided `steaminput_layout.vdf` has a fallback. Do NOT claim it fixes arbitrary games — the UI copy must say "experimental".

- [ ] **Step 1: Author the bundled layout asset**

Create `app/src/main/assets/steaminput/gamepad.vdf`. Keep it a valid `controller_mappings` VDF; the preset is named `Default` (which the converter always accepts) so it emits `Default.txt`. Values must be only the valid gbe_fork button/analog names.

```vdf
"controller_mappings"
{
	"actions"
	{
		"Default"
		{
		}
	}
	"group"
	{
		"id" "diamond"
		"mode" "button_diamond"
		"inputs"
		{
			"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button A" } } } }
			"button_b" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button B" } } } }
			"button_x" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button X" } } } }
			"button_y" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button Y" } } } }
			"left_bumper" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button LBUMPER" } } } }
			"right_bumper" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button RBUMPER" } } } }
			"button_menu" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button START" } } } }
			"button_escape" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button BACK" } } } }
			"dpad_north" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button DUP" } } } }
			"dpad_south" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button DDOWN" } } } }
			"dpad_west" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button DLEFT" } } } }
			"dpad_east" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button DRIGHT" } } } }
		}
	}
	"group"
	{
		"id" "lstick"
		"mode" "joystick_move"
		"gameactions" { "Default" "LJOY" }
		"inputs"
		{
			"joystick" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button LSTICK" } } } }
		}
	}
	"group"
	{
		"id" "rstick"
		"mode" "joystick_move"
		"gameactions" { "Default" "RJOY" }
		"inputs"
		{
			"right_joystick" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button RSTICK" } } } }
		}
	}
	"preset"
	{
		"name" "Default"
		"group_source_bindings"
		{
			"diamond" "button_diamond active"
			"lstick" "joystick active"
			"rstick" "right_joystick active"
		}
	}
}
```

- [ ] **Step 2: Write the implementation**

Create `app/src/main/java/com/winlator/star/store/steaminput/SteamInputLayouts.kt`:

```kotlin
package com.winlator.star.store.steaminput

import android.content.Context
import java.io.File

/**
 * Resolves the controller layout VDF used to build a game's `steam_settings/controller/` files.
 * Precedence: a layout the app/user dropped at `<game>/steam_settings/steaminput_layout.vdf`,
 * else the bundled identity layout in `assets/steaminput/gamepad.vdf`.
 */
object SteamInputLayouts {

    /** Relative path inside a game install where a provided layout is read from. */
    const val PROVIDED_REL = "steam_settings/steaminput_layout.vdf"

    private const val BUNDLED_ASSET = "steaminput/gamepad.vdf"

    fun resolve(context: Context, installDir: File): String? {
        val provided = File(installDir, PROVIDED_REL)
        if (provided.isFile) return runCatching { provided.readText() }.getOrNull()
        return runCatching {
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}
```

- [ ] **Step 3: Verify it compiles and the asset is packaged**

Run: `.\gradlew :app:assembleStandardDebug`
Expected: BUILD SUCCESSFUL. Then:

```powershell
Select-String -Path (Get-ChildItem -Recurse -Filter "*.apk" "app\build\outputs\apk\standard\debug" | Select-Object -First 1).FullName -Pattern "gamepad.vdf" -Encoding Byte -ErrorAction SilentlyContinue
```

Expected: (best-effort) the string appears; a stricter check is to unzip the APK and confirm `assets/steaminput/gamepad.vdf`:

```powershell
$apk = Get-ChildItem -Recurse -Filter "*.apk" "app\build\outputs\apk\standard\debug" | Select-Object -First 1
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::OpenRead($apk.FullName).Entries | Where-Object FullName -eq "assets/steaminput/gamepad.vdf"
```

Expected: one entry printed.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/steaminput/gamepad.vdf app/src/main/java/com/winlator/star/store/steaminput/SteamInputLayouts.kt
git commit -m "feat(steam-input): bundled fallback layout and resolver"
```

---

## Task 4: Per-game preference in SteamPrefs

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamPrefs.kt`

**Interfaces:**
- Produces: `SteamPrefs.getUseSteamInput(appId: Int): Boolean`, `SteamPrefs.setUseSteamInput(appId: Int, v: Boolean)`.
- Note: `SteamPrefs` is a singleton with a private `prefs`; every method relies on `init()` having been called. Mirror the existing `getGoldbergMode`/`setGoldbergMode` placement (they do NOT self-init). Callers already call `SteamPrefs.init(context)` before use (e.g. `LaunchMethodSheet`, `GoldbergPatcher`).

- [ ] **Step 1: Add the preference accessors**

In `app/src/main/java/com/winlator/star/store/SteamPrefs.kt`, immediately after `setGoldbergMode` (ends at line 91), insert:

```kotlin
    // ── Steam Input (gbe_fork SteamController/SteamInput emulation) ───────────
    // Per-game opt-in for the Goldberg (gbe_fork) path: when on, the launcher writes the
    // game's action-set files into steam_settings/controller/ so games that query ISteamInput
    // get a pad. Keyed by appId like goldbergMode, so it survives session changes.

    private const val K_STEAM_INPUT_PREFIX = "steam_input_"

    /** True if the user opted this game into gbe_fork Steam Input emulation. Default false. */
    fun getUseSteamInput(appId: Int): Boolean =
        prefs.getBoolean(K_STEAM_INPUT_PREFIX + appId, false)

    /** Persist the per-game Steam Input opt-in. */
    fun setUseSteamInput(appId: Int, v: Boolean) {
        prefs.edit().putBoolean(K_STEAM_INPUT_PREFIX + appId, v).apply()
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew :app:compileStandardDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamPrefs.kt
git commit -m "feat(steam-input): per-game useSteamInput preference"
```

---

## Task 5: Wire write/clear into GoldbergPatcher

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/GoldbergPatcher.kt`

**Interfaces:**
- Consumes: `SteamPrefs.getUseSteamInput(appId)`, `SteamInputLayouts.resolve(context, installDir)`, `SteamInputConfigWriter.write/clear`.
- Produces: nothing new to callers; behaviour only. When an apply runs with the pref ON, the controller files are written; with it OFF (or on OFF/restore) they are cleared.

**Context:** `applyModeBlocking(context, appId, installDir: String, gameName, mode)` runs `sharedPrep` and then applies the tier. `restore(installDir, gameName)` (line 407) already deletes `steam_settings` recursively via `removeAddedFiles`, so a full restore clears controller files implicitly. The missing case is toggling the pref while a Goldberg mode stays applied — that is handled here.

- [ ] **Step 1: Add the apply step after sharedPrep**

In `GoldbergPatcher.kt`, inside `applyModeBlocking` immediately AFTER the `sharedPrep(...)` call (find it in the method body around line 140–200; it is the line `sharedPrep(context, targets, appId)`), add:

```kotlin
            applySteamInput(context, appId, installDir)
```

- [ ] **Step 2: Add the helper method**

Add this private method inside `object GoldbergPatcher` (e.g. directly above `restoreDlls`, around line 395):

```kotlin
    /**
     * Writes or clears the gbe_fork Steam Input action-set files for this game based on the
     * per-game preference. Best-effort: never throws into the launch path.
     */
    private fun applySteamInput(context: Context, appId: Int, installDir: String) {
        try {
            val dir = File(installDir)
            SteamPrefs.init(context.applicationContext)
            if (SteamPrefs.getUseSteamInput(appId)) {
                val vdf = SteamInputLayouts.resolve(context.applicationContext, dir)
                if (vdf.isNullOrEmpty()) {
                    Log.w(TAG, "steam input: no layout for appId $appId")
                } else {
                    val n = com.winlator.star.store.steaminput.SteamInputConfigWriter.write(dir, vdf)
                    Log.i(TAG, "steam input: wrote $n action-set file(s) for appId $appId")
                }
            } else {
                com.winlator.star.store.steaminput.SteamInputConfigWriter.clear(dir)
            }
        } catch (e: Exception) {
            Log.w(TAG, "steam input apply failed for appId $appId", e)
        }
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew :app:compileStandardDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification on a device (documents the device protocol)**

On a device with the Goldberg `EXPERIMENTAL` component installed for a Steam game:
1. Set the pref on via `adb shell run-as com.winlator.banner` is NOT available; instead build the toggle in Task 6 first.
2. Apply Goldberg `EXPERIMENTAL`, then inspect the game install on the device:
   ```powershell
   adb shell find /sdcard -path "*steam_settings/controller/*.txt" 2>$null
   ```
   Expected: at least `.../steam_settings/controller/Default.txt` after applying with the pref ON.
3. Turn the pref OFF and re-apply: the `controller/` folder must be gone.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/GoldbergPatcher.kt
git commit -m "feat(steam-input): apply/clear gbe_fork controller files on the Goldberg path"
```

---

## Task 6: "Use Steam Input" toggle in the launch sheet

**Files:**
- Modify: `app/src/main/java/com/winlator/star/ui/screens/LaunchMethodSheet.kt`

**Interfaces:**
- Consumes: `SteamPrefs.getUseSteamInput(appId)` / `setUseSteamInput(appId, v)`.
- Produces: a new `OptionRow` shown only when the selected method is `GOLDBERG`, thread added to `OptionsBlock`.

**Context:** `OptionsBlock` (line 634) is shared by portrait and landscape cards; it currently receives `isSteam` but not the selected `method`. Thread a `showSteamInput: Boolean` through it. Persist on toggle (no `onLaunch` signature change), mirroring how `vag`/goldberg state is handled nearby.

- [ ] **Step 1: Add state + persistence in the top-level composable**

In `LaunchMethodSheet` (around line 221, after `controllerPassthrough`), add:

```kotlin
    var steamInput by remember(shortcut) {
        mutableStateOf(if (appId > 0) SteamPrefs.getUseSteamInput(appId) else false)
    }
```

Change `doLaunch` (line 243–251) so the choice is persisted when the popup is confirmed (the toggle already persists on change, so this is only a safety net for the case where state changed):

Actually keep persistence in the toggle callback — no `doLaunch` change is needed. Replace that reasoning by adding an `onSteamInput` lambda next to `toggleHelp`:

```kotlin
    val onSteamInput: (Boolean) -> Unit = { v ->
        steamInput = v
        if (appId > 0) SteamPrefs.setUseSteamInput(appId, v)
    }
```

- [ ] **Step 2: Pass the flag + lambda into both cards**

In the `landscape` `LandscapeCard(...)` call (line 310) and the `PortraitCard(...)` call (line 319), add after the `controllerPassthrough` argument pair:

```kotlin
                steamInput, onSteamInput,
```

- [ ] **Step 3: Add parameters to both card composables**

In the `PortraitCard` signature (after `onPassthrough: (Boolean) -> Unit,` at line 349) add:

```kotlin
    steamInput: Boolean,
    onSteamInput: (Boolean) -> Unit,
```

Do the same in `LandscapeCard` (after `onPassthrough` at line 461).

- [ ] **Step 4: Forward into OptionsBlock from both cards**

In `PortraitCard`'s `OptionsBlock(...)` call (line 421) and `LandscapeCard`'s (line 525), add after `passthrough, onPassthrough,`:

```kotlin
                        showSteamInput = method == LaunchMethod.GOLDBERG,
                        steamInput, onSteamInput,
```

- [ ] **Step 5: Add the parameters + row to OptionsBlock**

Add to the `OptionsBlock` signature (after `onPassthrough: (Boolean) -> Unit,` at line 639):

```kotlin
    showSteamInput: Boolean,
    steamInput: Boolean,
    onSteamInput: (Boolean) -> Unit,
```

And inside `OptionsBlock`, immediately after the `if (isSteam) { ... }` block that renders "Controller passthrough" and "Requires secure (VAC) launch" (ends at line 687), add:

```kotlin
    if (showSteamInput) {
        OptionRow(
            title = "Use Steam Input",
            badge = "NEW",
            subtitle = if (compact) null else "gbe_fork answers the game's Steam Input actions. Experimental; needs the Experimental Goldberg mode.",
            accent = accent,
            compact = compact,
            onHelp = { toggleHelp(HELP_STEAM_INPUT) },
            trailing = { PillSwitch(steamInput, accent, onSteamInput) },
        )
    }
```

- [ ] **Step 6: Add the help copy**

Next to the other `HELP_*` constants (after `HELP_GOLDBERG_MODE`, line 160), add:

```kotlin
private const val HELP_STEAM_INPUT =
    "Goldberg-only, experimental. Makes the emulated Steam answer games that use the Steam Input API, " +
        "so their actions get a pad. Requires the Experimental Goldberg mode. Ignored by SteamLite/Raw."
```

- [ ] **Step 7: Verify it compiles**

Run: `.\gradlew :app:compileStandardDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Manual UI verification**

Launch the app, open a Steam game's launch popup, pick the **Goldberg** chip → the "Use Steam Input" row appears; pick SteamLite/Raw → it disappears. Toggle it, close and reopen the popup → the state persists.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/winlator/star/ui/screens/LaunchMethodSheet.kt
git commit -m "feat(steam-input): Goldberg-path Use Steam Input toggle in the launch sheet"
```

---

## Task 7: Wire the toggle into the non-sheet launch paths

**Files:**
- Modify: `app/src/main/java/com/winlator/star/ui/screens/ShortcutsScreen.kt`
- Modify: `app/src/main/java/com/winlator/star/ui/screens/BigPictureScreen.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: the remembered-choice launch path also honours the toggle by re-applying the Goldberg controller files.

**Context:** `launchWithGoldberg(s, gm)` in `ShortcutsScreen.kt` (line 442) always calls `GoldbergPatcher.applyModeAsync(...)` before `launchShortcutNow`, so Task 5's apply step covers it — **no change needed there**. The gap is that `BigPictureScreen` and `SteamGameDetailActivity` call `GoldbergPatcher.applyModeAsync` too (they are covered), but a **remembered** launch that skips the popup may call `launchShortcutNow` directly. Verify by reading `ShortcutsScreen.requestLaunch` (line 507) and its remembered-Goldberg branch.

- [ ] **Step 1: Read the remembered-launch branch**

Read `ShortcutsScreen.kt` around `requestLaunch` (line 507) and locate the branch where `launchModeRemembered == "1"` and `launchMode == "Goldberg"`.

- [ ] **Step 2: Ensure apply runs on that branch**

If the remembered-Goldberg branch calls `launchShortcutNow` without `GoldbergPatcher.applyModeAsync`, call `launchWithGoldberg(s, SteamPrefs.getGoldbergMode(steamAppIdOf(s)))` instead, so the controller files are refreshed. If it already routes through `launchWithGoldberg`, make no change and note it in the commit message. Do the same check in `BigPictureScreen.kt`'s remembered path.

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew :app:compileStandardDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/winlator/star/ui/screens/ShortcutsScreen.kt app/src/main/java/com/winlator/star/ui/screens/BigPictureScreen.kt
git commit -m "feat(steam-input): refresh controller files on remembered Goldberg launches"
```

---

## Task 8: Full test + build gate

- [ ] **Step 1: Run the new unit tests**

Run: `.\gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.store.steaminput.*"`
Expected: PASS (6 tests).

- [ ] **Step 2: Run the whole standard debug build**

Run: `.\gradlew :app:assembleStandardDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the fast CI workflow**

Push the branch; `.github/workflows/pr-ci.yml` builds `assemble*Debug`. Expected: green. (Local builds are not the supported path per `README.md`; the CI run is the authoritative build.)

- [ ] **Step 4: Commit (only if any fixups were needed)**

```bash
git add -A
git commit -m "chore(steam-input): fixups from full build gate"
```

---

## Known Limitations (must be in the release note)

- The bundled layout is an **identity (XInput-named actions)** layout, so it only produces matching action sets for games whose Steam Input actions are named after XInput buttons. Games with their own action names need their real layout — Phase 2.
- gbe_fork's controller feature only exists in its **Windows experimental** build; the toggle is inert with `REGULAR`/`COLDCLIENT`. The UI help copy says "Experimental".
- SteamLite (genuine client) and Raw launches ignore this toggle entirely.

## Follow-on plans (NOT part of this plan)

1. **Steam layout retrieval (makes the feature broadly useful).** Source the real layout VDF:
   - the game's official layout id from PICS: `steamcontrollerconfigdetails` (`<fileid>/controller_type: controller_xbox360`), then download the shared file (`https://steamcommunity.com/sharedfiles/filedetails/?id=<fileid>` — gbe_fork's documented route), then convert;
   - else the user's own saved config.
   Requires: (a) verify Bannerlator's PICS parser can expose `steamcontrollerconfigdetails` (add the key if not); (b) add a downloader for the shared controller VDF. Write this plan only after verifying (a) against Bannerlator's `SteamRepository` PICS handling and (b) against the Rust engine / JavaSteam UGC capabilities.
2. **Auto-enable for games that ship their own Steam Input manifest** (`hasOwnSteamInputManifest` parity): detect `controller_config/game_actions_<appid>.vdf` (or the PICS manifest path) in the install and flip the pref on. Requires the manifest → action-set synthesis (GameNative's template-index-13 path) — needs the same PICS/engine verification as (1).
3. **SteamLite mode (genuine client):** turn the `SteamController_*Support` keys ON (the inverse of `RealSteamLauncher.applyControllerPassthrough`) plus `apps/<appid>/UseSteamControllerConfig=2`. High risk: the virtual pad reaching a Wine game is unproven (see `docs/releases/3.1.3-pre3.md`). Write separately, after a device spike.

---

## Self-Review

**1. Spec coverage** (against the four GameNative mechanisms in "Scope"):
- (2) VDF→`steam_settings/controller` conversion → Task 1 + 2. ✅
- (1) layout resolution (user/external → bundled) → Task 3. ✅ (Steam-sourced layouts deferred to follow-on 1.)
- per-game toggle + Goldberg integration → Tasks 4, 5, 6, 7. ✅
- (3) headless-host `steamhost_controller_<appid>.vdf` → N/A for Bannerlator (no headless host on the Goldberg path). Documented in Architecture. ✅
- (4) `localconfig.vdf` SteamController keys → N/A for the Goldberg path (no real client); only relevant to the SteamLite follow-on 3. ✅
- (5) auto-enable → explicitly deferred to follow-on 2, per the Phase split. ✅

**2. Placeholder scan:** Task 7 Step 2 is the only conditional ("if already routed, make no change") — it is a verification step with a concrete fallback action, not a TODO. No "TBD"/"handle edge cases"/"similar to Task N".

**3. Type consistency:** `SteamInputVdfConverter.convert(String): Map<String,String>` (Task 1) is consumed by `SteamInputConfigWriter.write(File, String): Int` (Task 2); `SteamInputLayouts.resolve(Context, File): String?` (Task 3) feeds `write`; `SteamPrefs.getUseSteamInput/setUseSteamInput(Int[,Boolean])` (Task 4) is consumed in Tasks 5/6. `CONTROLLER_DIR = "steam_settings/controller"` is the single source of the path. Names match throughout.
