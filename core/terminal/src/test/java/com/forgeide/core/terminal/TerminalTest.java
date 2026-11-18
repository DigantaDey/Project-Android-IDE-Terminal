package com.forgeide.core.terminal;

import static org.junit.Assert.*;

import com.forgeide.core.project.*;
import com.forgeide.core.util.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class TerminalTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void quotingAndVariables() {
        assertEquals(
            List.of("echo", "hello world", "", "a b", "yes"),
            ShellWords.parse("echo 'hello world' \"\" a\\ b $X", Map.of("X", "yes"))
        );
    }

    @Test
    public void hostSyntaxRejected() {
        for (String c : List.of("ls | sh", "a; b", "cat > x", "a && b"))
            try {
                ShellWords.parse(c, Map.of());
                fail();
            } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void realFileCommandsAndConfinement() throws Exception {
        Workspace w = new Workspace(tmp.newFolder().toPath());
        TerminalSession t = new TerminalSession("one", w);
        CancellationToken c = new CancellationToken();
        assertEquals(0, t.execute("mkdir src", c).exitCode);
        assertEquals(0, t.execute("cd src", c).exitCode);
        assertEquals(0, t.execute("touch hello.js", c).exitCode);
        assertTrue(t.execute("ls", c).stdout.contains("hello.js"));
        assertEquals(1, t.execute("cd ../../..", c).exitCode);
        assertEquals(127, t.execute("/system/bin/sh", c).exitCode);
        assertEquals("src", t.cwd());
    }

    @Test
    public void environmentDoesNotInheritHostSecrets() throws Exception {
        TerminalSession t = new TerminalSession("one", new Workspace(tmp.newFolder().toPath()));
        String env = t.execute("env", new CancellationToken()).stdout;
        assertFalse(env.contains("GH_TOKEN"));
        assertTrue(env.contains("TERM=forge-virtual"));
    }

    @Test
    public void historyAndOutputAreBounded() throws Exception {
        TerminalSession t = new TerminalSession("x", new Workspace(tmp.newFolder().toPath()));
        t.append("x".repeat(300000));
        assertEquals(262144, t.output().length());
        t.execute("export SECRET_TOKEN=abc", new CancellationToken());
        assertEquals(0, t.history().size());
        String json = t.serialize();
        TerminalSession restored = new TerminalSession("x", new Workspace(tmp.newFolder().toPath()));
        restored.restore(json);
        assertEquals(t.output(), restored.output());
    }

    @Test
    public void ideMutationGuardProtectsOpenBuffersFromShellChanges() throws Exception {
        Workspace workspace = new Workspace(tmp.newFolder().toPath());
        workspace.write("src/open.js", "unsaved buffer owns this path");
        workspace.write("other.js", "other");
        TerminalSession terminal = new TerminalSession("guard", workspace);
        terminal.protectMutations(path -> {
            if (path.equals("src") || path.equals("src/open.js")) throw new java.io.IOException(
                "Close affected editor tabs"
            );
        });
        CancellationToken cancel = new CancellationToken();
        for (String command : java.util.List.of(
            "rm src/open.js",
            "mv src moved",
            "mv other.js src/open.js",
            "cp other.js src/open.js"
        )) {
            TerminalSession.Result result = terminal.execute(command, cancel);
            assertEquals(command, 1, result.exitCode);
            assertTrue(result.stderr.contains("Close affected editor tabs"));
        }
        assertEquals("unsaved buffer owns this path", workspace.read("src/open.js"));
        assertEquals("other", workspace.read("other.js"));
        assertEquals(0, terminal.execute("cp other.js copy.js", cancel).exitCode);
    }
}
