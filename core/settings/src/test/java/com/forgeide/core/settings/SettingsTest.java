package com.forgeide.core.settings;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SettingsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void overridesAndRoundTrip() throws Exception {
        Settings s = new Settings();
        s.set("fontSize", 18, false);
        s.set("fontSize", 21, true);
        assertEquals(21, s.integer("fontSize"));
        Path p = tmp.newFile().toPath();
        s.save(p, true);
        Settings t = new Settings();
        t.load(p, true);
        assertEquals(21, t.integer("fontSize"));
    }

    @Test
    public void limitsValidated() {
        for (Object v : new Object[] {
            0,
            1000,
            "14",
            4294967310L,
            14.5,
            Double.NaN,
            Double.POSITIVE_INFINITY,
        })
            try {
                new Settings().set("fontSize", v, false);
                fail();
            } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void malformedFilePreserved() throws Exception {
        Path p = tmp.newFile().toPath();
        Files.writeString(p, "not JSON");
        try {
            new Settings().load(p, false);
            fail();
        } catch (java.io.IOException expected) {}
        assertEquals("not JSON", Files.readString(p));
    }

    @Test
    public void failedSettingsLoadDoesNotPartiallyChangePreferences() throws Exception {
        Settings settings = new Settings();
        Path p = tmp.newFile().toPath();
        Files.writeString(p, "{\"fontSize\":20,\"tabSize\":1000}");
        try {
            settings.load(p, false);
            fail();
        } catch (java.io.IOException expected) {}
        assertEquals(14, settings.integer("fontSize"));
        assertEquals(4, settings.integer("tabSize"));
    }

    @Test
    public void importedProjectCannotWeakenGlobalSecurityPolicy() {
        Settings settings = new Settings();
        settings.set("requireHardLimits", true, false);
        settings.set("requireHardLimits", false, true);
        settings.set("executionSeconds", 60, true);
        settings.set("outputKiB", 1024, true);
        assertTrue(settings.bool("requireHardLimits"));
        assertEquals(10, settings.integer("executionSeconds"));
        assertEquals(256, settings.integer("outputKiB"));
        settings.set("executionSeconds", 2, true);
        assertEquals(2, settings.integer("executionSeconds"));
        assertEquals(true, settings.snapshot().get("requireHardLimits"));
    }

    @Test
    public void nativeToolsNeedGlobalConsentAndCannotBeEnabledByImportedProject() throws Exception {
        Settings settings = new Settings();
        for (String key : new String[] {
            "native.clang",
            "native.python",
            "native.node",
            "native.npm",
            "native.bash",
            "native.network",
            "native.installScripts",
            "native.lsp.clangd",
            "native.lsp.pyright",
            "native.lsp.typescript",
            "native.lsp.bashls",
            "native.lsp.html",
            "native.lsp.css",
            "native.lsp.json",
        }) {
            assertFalse(key, settings.bool(key));
            try {
                settings.set(key, true, true);
                fail(key);
            } catch (IllegalArgumentException expected) {}
        }
        Path file = tmp.newFile().toPath();
        Files.writeString(
            file,
            "{\"native.python\":true,\"native.network\":true,\"native.lsp.pyright\":true,\"fontSize\":18}"
        );
        settings.load(file, true);
        assertFalse(settings.bool("native.python"));
        assertFalse(settings.bool("native.network"));
        assertFalse(settings.bool("native.lsp.pyright"));
        assertEquals(18, settings.integer("fontSize"));
        settings.set("native.python", true, false);
        settings.save(file, false);
        Settings restored = new Settings();
        restored.load(file, false);
        assertTrue(restored.bool("native.python"));
        assertFalse(restored.bool("native.network"));
    }
}
