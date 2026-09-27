package com.winlator.star.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure tests for the RealSteam localconfig Steam Input preference (mirrors GameNative's
 *  setSteamInputPreference: system/SteamController_*Support + apps/<id>/UseSteamControllerConfig). */
public class RealSteamSteamInputTest {

    private static final String EMPTY = "\"UserLocalConfigStore\"\n{\n}\n";

    private static final String[] SUPPORT_KEYS = {
        "SteamController_XBoxSupport",
        "SteamController_GenericGamepadSupport",
        "SteamController_PSSupport",
        "SteamController_SwitchSupport",
    };

    /** Collapse all runs of whitespace so the indentation injectVdfKeys picks doesn't matter. */
    private static String norm(String s) { return s.replaceAll("\\s+", " "); }

    @Test
    public void enable_setsSupportKeysToOne_andPerAppForceOn() {
        String out = RealSteamLauncher.injectSteamInputPreference(EMPTY, 2742830, true);
        assertNotNull(out);
        String n = norm(out);
        for (String k : SUPPORT_KEYS) assertTrue(n.contains("\"" + k + "\" \"1\""));
        assertTrue(n.contains("\"2742830\" { \"UseSteamControllerConfig\" \"2\" }"));
    }

    @Test
    public void disable_setsSupportKeysToZero_andPerAppGlobalDefault() {
        String out = RealSteamLauncher.injectSteamInputPreference(EMPTY, 2742830, false);
        assertNotNull(out);
        String n = norm(out);
        for (String k : SUPPORT_KEYS) assertTrue(n.contains("\"" + k + "\" \"0\""));
        assertTrue(n.contains("\"UseSteamControllerConfig\" \"0\""));
    }

    @Test
    public void enable_isIdempotent() {
        String once = RealSteamLauncher.injectSteamInputPreference(EMPTY, 2742830, true);
        String twice = RealSteamLauncher.injectSteamInputPreference(once, 2742830, true);
        assertEquals(once, twice);
    }

    @Test
    public void notALocalConfig_returnsNull() {
        assertNull(RealSteamLauncher.injectSteamInputPreference("\"Other\"\n{\n}\n", 2742830, true));
    }

    @Test
    public void preserveExistingKeys() {
        String in = "\"UserLocalConfigStore\"\n{\n\t\"system\"\n\t{\n\t\t\"EnableGameOverlay\"\t\t\"0\"\n\t}\n}\n";
        String out = RealSteamLauncher.injectSteamInputPreference(in, 2742830, true);
        assertNotNull(out);
        assertTrue(norm(out).contains("\"EnableGameOverlay\" \"0\""));
        assertTrue(norm(out).contains("\"SteamController_XBoxSupport\" \"1\""));
    }
}
