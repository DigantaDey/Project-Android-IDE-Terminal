package com.forgeide.core.toolchain;

import static org.junit.Assert.*;

import com.forgeide.core.language.Diagnostic;
import java.util.*;
import org.junit.Test;

public class ToolchainContractsTest {

    @Test
    public void runConfigRoundTripKeepsArgvEnvironmentAndInput() {
        RunConfiguration a = new RunConfiguration(
            "test",
            "javascript",
            "src/main.js",
            "src",
            List.of("hello world", "--flag"),
            Map.of("NAME", "value"),
            "input\n",
            List.of(),
            List.of(),
            List.of()
        );
        RunConfiguration b = RunConfiguration.parse(a.toJson());
        assertEquals(a.arguments, b.arguments);
        assertEquals(a.environment, b.environment);
        assertEquals(a.stdin, b.stdin);
        assertEquals(a.workingDirectory, b.workingDirectory);
    }

    @Test
    public void compilerLocationsBecomeZeroBasedProblems() {
        List<Diagnostic> p = BuildLog.diagnostics(
            "src/main.cpp:12:4: warning: unused variable\nother.java:6: error: missing symbol"
        );
        assertEquals(2, p.size());
        assertEquals(11, p.get(0).line);
        assertEquals(3, p.get(0).column);
        assertEquals(Diagnostic.Severity.WARNING, p.get(0).severity);
        assertEquals(5, p.get(1).line);
    }

    @Test
    public void historyIsBounded() {
        BuildLog history = new BuildLog();
        for (int i = 0; i < 30; i++) history.add("run " + i);
        assertEquals(20, history.history().size());
        assertEquals("run 29", history.history().get(0));
    }

    @Test
    public void unavailableProvidersAreNeverReplacedByHostShell() {
        try {
            new ToolchainRegistry().require("python");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No isolated toolchain"));
        }
    }

    @Test
    public void configuredProviderMustReallySupportTheFileLanguage() {
        Toolchain provider = new Toolchain() {
            public String id() {
                return "javascript";
            }

            public String displayName() {
                return "Fixture JS";
            }

            public java.util.Set<String> languages() {
                return java.util.Set.of("javascript");
            }

            public java.util.Set<com.forgeide.core.sandbox.SandboxPolicy.Restriction> restrictions() {
                return com.forgeide.core.sandbox.SandboxPolicy.safeDefault().required;
            }

            public boolean available() {
                return true;
            }

            public String availabilityMessage() {
                return "Installed fixture";
            }

            public java.util.concurrent.CompletableFuture<Integer> run(
                String source,
                RunConfiguration config,
                com.forgeide.core.sandbox.SandboxPolicy policy,
                com.forgeide.core.util.CancellationToken cancel,
                java.util.function.Consumer<String> out,
                java.util.function.Consumer<String> errors
            ) {
                throw new UnsupportedOperationException();
            }
        };
        ToolchainRegistry registry = new ToolchainRegistry();
        registry.register(provider);
        assertSame(provider, registry.requireForLanguage("javascript", "javascript"));
        try {
            registry.requireForLanguage("javascript", "sql");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("does not support sql"));
        }
        try {
            registry.requireForLanguage("python", "python");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No isolated toolchain"));
        }
    }
}
