package com.forgeide.core.toolchain;

import static org.junit.Assert.*;

import com.forgeide.core.util.JsonObject;
import java.util.*;
import org.junit.Test;

public class LanguageServersTest {

    @Test
    public void everyDeclaredServerHasAnIndependentConsentAndAllowlistedCommand() {
        assertEquals(7, LanguageServers.ORDER.size());
        for (String id : LanguageServers.ORDER) {
            assertEquals("native.lsp." + id, LanguageServers.key(id));
            assertFalse(LanguageServers.label(id).isEmpty());
            List<String> command = LanguageServers.command(id, "/runtime");
            assertEquals(id.equals("clangd") ? "/runtime/bin/clangd" : "/runtime/bin/node", command.get(0));
            assertFalse(command.contains("npm"));
            assertFalse(command.contains("npx"));
            assertFalse(command.contains("-c"));
        }
        try {
            LanguageServers.command("../../node", "/runtime");
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void unsupportedLanguagesAreNotRelabelledAsSemantic() {
        assertEquals("typescript", LanguageServers.forLanguage("typescriptreact"));
        assertEquals("clangd", LanguageServers.forLanguage("cpp"));
        assertEquals("pyright", LanguageServers.forLanguage("python"));
        assertEquals("", LanguageServers.forLanguage("java"));
        assertEquals("", LanguageServers.forLanguage("rust"));
    }

    @Test
    public void configurationDisablesAutomaticDownloadsAndMissingBashHelpers() {
        JsonObject ts = new JsonObject(LanguageServers.initialization("typescript", "/runtime"));
        assertTrue(ts.getBoolean("disableAutomaticTypingAcquisition", false));
        assertEquals(
            "/runtime/share/forge-lsp/node_modules/typescript/lib/tsserver.js",
            ts.getObject("tsserver").getString("path", "")
        );
        JsonObject bash = new JsonObject(LanguageServers.initialization("bashls", "/runtime"));
        assertEquals("", bash.getString("shellcheckPath", "missing"));
        assertEquals("", bash.getObject("shfmt").getString("path", "missing"));
        assertFalse(
            new JsonObject(LanguageServers.configuration("json", "/runtime"))
                .getObject("json")
                .getObject("schemaDownload")
                .getBoolean("enable", true)
        );
    }
}
