package com.forgeide.core.toolchain;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class NativeToolsTest {

    @Test
    public void onlyKnownToolsAndLanguages() {
        assertEquals(Set.of("c", "cpp"), NativeTools.languages("clang"));
        assertEquals(Set.of("shellscript"), NativeTools.languages("bash"));
        assertEquals("native.python", NativeTools.key("python"));
        try {
            NativeTools.key("../../sh");
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void compilerArgumentsNeverBecomeShellSource() {
        String file = "evil ' $(touch owned).cpp";
        List<String> plan = NativeTools.command(
            "clang",
            "cpp",
            "/runtime/usr",
            "/copy",
            "/copy/out",
            file,
            List.of("a;rm", "বাংলা 🐍"),
            false
        );
        assertEquals("/runtime/usr/bin/bash", plan.get(0));
        assertFalse(plan.get(4).contains(file));
        assertTrue(plan.get(4).contains("-fno-color-diagnostics"));
        assertTrue(plan.contains("/copy/" + file));
        assertEquals(List.of("a;rm", "বাংলা 🐍"), plan.subList(plan.size() - 2, plan.size()));
        assertTrue(plan.contains("/runtime/usr/bin/clang++"));
    }

    @Test
    public void rejectsHostPathsAndTraversal() {
        for (String path : List.of(
            "/data/secret",
            "../x",
            "a/../x",
            "a//x",
            "C:/x",
            "a\\b",
            "x/",
            ".",
            "x\0z"
        )) {
            try {
                NativeTools.relativePath(path, false);
                fail(path);
            } catch (IllegalArgumentException expected) {}
        }
        NativeTools.relativePath("dir/main.py", false);
        NativeTools.relativePath("", true);
    }

    @Test
    public void protectsLoaderAndInterpreterEnvironment() {
        for (String name : List.of(
            "LD_PRELOAD",
            "PATH",
            "BASH_ENV",
            "NODE_OPTIONS",
            "PYTHONPATH",
            "npm_config_ignore_scripts",
            "TERMUX_PREFIX",
            "FORGE_TOKEN",
            "SHELLOPTS"
        )) {
            try {
                NativeTools.environment(Map.of(name, "payload"));
                fail(name);
            } catch (IllegalArgumentException expected) {}
        }
        assertEquals(Map.of("GREETING", "hello"), NativeTools.environment(Map.of("GREETING", "hello")));
        try {
            NativeTools.environment(Map.of("X", "x".repeat(8193)));
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void nativeNpmAndInteractiveShellAreRealEntryPoints() {
        assertEquals(
            List.of("/usr/bin/bash", "--noprofile", "--norc", "-i"),
            NativeTools.command("bash", "shellscript", "/usr", "/project", "/out", "", List.of(), true)
        );
        List<String> npm = NativeTools.command(
            "npm",
            "",
            "/usr",
            "/project",
            "/out",
            "",
            List.of("--version"),
            false
        );
        assertEquals(List.of("/usr/bin/node", "/usr/lib/node_modules/npm/bin/npm-cli.js", "--version"), npm);
    }

    @Test
    public void argumentBoundsAreEnforced() {
        for (List<String> values : List.of(
            Collections.nCopies(101, "x"),
            List.of("x".repeat(32769)),
            List.of("x\0y")
        )) {
            try {
                NativeTools.arguments(values);
                fail();
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void shellCommandDiscoveryFollowsToolFamilies() {
        assertEquals("python", NativeTools.commandTool("python3.14"));
        assertEquals("npm", NativeTools.commandTool("npx"));
        assertEquals("clang", NativeTools.commandTool("clang++"));
        assertEquals("clang", NativeTools.commandTool("cc"));
        assertEquals("node", NativeTools.commandTool("node"));
        assertEquals("", NativeTools.commandTool("ls"));
    }
}
