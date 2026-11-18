package com.forgeide.core.commands;

import static org.junit.Assert.*;

import org.junit.Test;

public class CommandRegistryTest {

    @Test
    public void disabledCommandsAreNotExecutedOrShown() {
        CommandRegistry c = new CommandRegistry();
        boolean[] called = { false };
        c.register(
            new CommandRegistry.Command("x", "Build project", "Ctrl+B", () -> called[0] = true, () -> false)
        );
        assertFalse(c.execute("x"));
        assertTrue(c.search("build").isEmpty());
        assertFalse(called[0]);
    }

    @Test
    public void paletteAndShortcutUseSameAction() {
        CommandRegistry c = new CommandRegistry();
        int[] calls = { 0 };
        c.register(
            new CommandRegistry.Command("save", "Save document", "Ctrl+S", () -> calls[0]++, () -> true)
        );
        assertEquals("save", c.search("svdoc").get(0).id);
        assertTrue(c.execute("save"));
        assertEquals(1, calls[0]);
    }
}
