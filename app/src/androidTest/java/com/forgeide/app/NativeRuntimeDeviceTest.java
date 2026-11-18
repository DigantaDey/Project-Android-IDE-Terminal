package com.forgeide.app;

import static org.junit.Assert.*;

import android.os.Bundle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.app.nativebridge.*;
import com.forgeide.core.project.Workspace;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real offline native binaries on disposable emulators; missing companions are failures, never skips. */
@RunWith(AndroidJUnit4.class)
public final class NativeRuntimeDeviceTest {

    private ForgeApplication app;
    private Workspace workspace;
    private final Map<String, Object> before = new LinkedHashMap<>();
    private NativeRuntimeClient.Run active;
    private CountDownLatch connected;
    private final StringBuffer output = new StringBuffer();
    private final AtomicReference<Bundle> identity = new AtomicReference<>();

    @Before
    public void setup() throws Exception {
        app = (ForgeApplication) InstrumentationRegistry.getInstrumentation()
            .getTargetContext()
            .getApplicationContext();
        assertEquals(
            "The matching offline runtime APK must be installed by CI",
            "",
            app.nativeRuntime.installationProblem()
        );
        for (String id : NativeTools.ORDER) {
            String key = NativeTools.key(id);
            before.put(key, app.settings.get(key));
            app.settings.set(key, true, false);
        }
        for (String key : List.of("native.network", "native.installScripts", "requireHardLimits")) {
            before.put(key, app.settings.get(key));
            app.settings.set(key, false, false);
        }
        workspace = new Workspace(Files.createTempDirectory(app.getCacheDir().toPath(), "native-fixture-"));
    }

    @After
    public void teardown() throws Exception {
        if (active != null && !active.result.isDone()) {
            app.nativeRuntime.stop(active);
            active.result.get(10, TimeUnit.SECONDS);
        }
        if (app != null) {
            app.nativeRuntime.stopAll();
            for (Map.Entry<String, Object> entry : before.entrySet())
                app.settings.set(entry.getKey(), entry.getValue(), false);
        }
        if (workspace != null) try (java.util.stream.Stream<Path> files = Files.walk(workspace.root())) {
            for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(path);
        }
    }

    private NativeRuntimeClient.Run start(
        String tool,
        String language,
        String file,
        List<String> args,
        Map<String, String> env,
        String stdin,
        long timeout,
        int cap,
        boolean interactive,
        java.util.function.Consumer<byte[]> observe
    ) {
        output.setLength(0);
        identity.set(null);
        CountDownLatch connection = new CountDownLatch(1);
        connected = connection;
        Utf8Chunks decoder = new Utf8Chunks();
        RunConfiguration config = new RunConfiguration(
            "Native fixture",
            tool,
            file,
            "",
            args,
            env,
            stdin,
            List.of(),
            List.of(),
            List.of()
        );
        active = app.nativeRuntime.start(
            workspace,
            tool,
            language,
            config,
            timeout,
            cap,
            interactive,
            new CancellationToken(),
            bytes -> {
                output.append(decoder.append(bytes));
                if (observe != null) observe.accept(bytes);
            },
            info -> {
                identity.set(info);
                connection.countDown();
            }
        );
        return active;
    }

    private int run(String tool, String language, String file) throws Exception {
        return start(tool, language, file, List.of(), Map.of(), "", 60000, 262144, false, null).result.get(
            100,
            TimeUnit.SECONDS
        );
    }

    private void assertRun(String tool, String language, String file) throws Exception {
        int exit = run(tool, language, file);
        assertEquals(tool + ": " + output, 0, exit);
    }

    @Test
    public void pythonUsesRealStdinUnicodeArgvAndSeparateUidWithKernelDenials() throws Exception {
        Path witness = app.stateDir.resolve("native-private-witness.txt");
        AtomicFiles.write(witness, "IDE-private");
        assertTrue(Files.isReadable(witness));
        workspace.write(
            "native-🐍.py",
            "import os,sys,socket\nprint('PY_RESULT_'+str(6*7))\nprint('ARG_'+sys.argv[1])\nprint('ENV_'+os.environ['GREETING'])\nprint('INPUT_'+input())\ntry:\n open(" +
                Json.write(witness.toString()) +
                ").read()\n print('HOST_READABLE')\nexcept PermissionError:\n print('HOST_DENIED')\ntry:\n socket.socket(socket.AF_INET,socket.SOCK_STREAM)\n print('NETWORK_ALLOWED')\nexcept PermissionError:\n print('NETWORK_DENIED')\n"
        );
        try {
            int exit = start(
                "python",
                "python",
                "native-🐍.py",
                List.of("বাংলা 🐍"),
                Map.of("GREETING", "hello"),
                "stdin-value\n",
                60000,
                262144,
                false,
                null
            ).result.get(100, TimeUnit.SECONDS);
            assertEquals(output.toString(), 0, exit);
            assertTrue(output.toString(), output.toString().contains("PY_RESULT_42"));
            assertTrue(output.toString(), output.toString().contains("ARG_বাংলা 🐍"));
            assertTrue(output.toString(), output.toString().contains("ENV_hello"));
            assertTrue(output.toString(), output.toString().contains("INPUT_stdin-value"));
            assertTrue(output.toString(), output.toString().contains("HOST_DENIED"));
            assertTrue(output.toString(), output.toString().contains("NETWORK_DENIED"));
            assertFalse(output.toString().contains("HOST_READABLE"));
            assertFalse(output.toString().contains("NETWORK_ALLOWED"));
            assertNotNull(identity.get());
            assertNotEquals(android.os.Process.myUid(), identity.get().getInt("uid"));
        } finally {
            Files.deleteIfExists(witness);
        }
    }

    @Test
    public void nativeClangCompilesAndRunsBothCAndCpp() throws Exception {
        workspace.write(
            "hello.c",
            "#include <stdio.h>\nint main(void){printf(\"C_RESULT_%d\\n\",6*7);return 0;}\n"
        );
        assertRun("clang", "c", "hello.c");
        assertTrue(output.toString(), output.toString().contains("C_RESULT_42"));
        workspace.write(
            "hello.cpp",
            "#include <iostream>\n#include <vector>\nint main(){std::vector<int> n{6,7};std::cout<<\"CPP_RESULT_\"<<n[0]*n[1]<<std::endl;}\n"
        );
        assertRun("clang", "cpp", "hello.cpp");
        assertTrue(output.toString(), output.toString().contains("CPP_RESULT_42"));
    }

    @Test
    public void compilerErrorsCannotBeReportedAsSuccess() throws Exception {
        workspace.write("broken.c", "int main( { broken source\n");
        assertNotEquals(output.toString(), 0, run("clang", "c", "broken.c"));
        assertTrue(output.toString(), output.toString().contains("error:"));
    }

    @Test
    public void nodeReallyHasCommonJsAndFilesystemApis() throws Exception {
        workspace.write(
            "main.js",
            "const fs=require('fs');fs.writeFileSync('node-created.txt','42');console.log('NODE_RESULT_'+fs.readFileSync('node-created.txt','utf8'));\n"
        );
        assertRun("node", "javascript", "main.js");
        assertTrue(output.toString(), output.toString().contains("NODE_RESULT_42"));
        assertFalse(
            "Native writes must not reach the original IDE project",
            Files.exists(workspace.resolve("node-created.txt"))
        );
    }

    @Test
    public void npmActuallyInstallsAnOfflineLocalDependencyWithoutLifecycleScripts() throws Exception {
        workspace.write(
            "package.json",
            "{\"name\":\"forge-fixture\",\"version\":\"1.0.0\",\"dependencies\":{\"fixture\":\"file:./dep\"},\"scripts\":{\"postinstall\":\"node -e \\\"require('fs').writeFileSync('unsafe-script-ran','yes')\\\"\"}}\n"
        );
        workspace.write(
            "dep/package.json",
            "{\"name\":\"fixture\",\"version\":\"1.0.0\",\"main\":\"index.js\"}\n"
        );
        workspace.write("dep/index.js", "module.exports={answer:42};\n");
        workspace.write(
            "npm-test.sh",
            "set -e\nnpm --version\nnpm install --offline\ntest ! -f unsafe-script-ran\nnode -e \"console.log('NPM_RESULT_'+require('fixture').answer)\"\n"
        );
        assertRun("bash", "shellscript", "npm-test.sh");
        assertTrue(output.toString(), output.toString().contains("NPM_RESULT_42"));
    }

    @Test
    public void switchesBlockLaunchesAndStrictPolicyIsNotSilentlyWeakened() throws Exception {
        app.settings.set("native.python", false, false);
        assertFalse(app.nativeRuntime.available("python"));
        try {
            start("python", "python", "main.py", List.of(), Map.of(), "", 1000, 1024, false, null);
            fail();
        } catch (IllegalStateException expected) {}
        app.settings.set("native.node", false, false);
        assertFalse(app.nativeRuntime.available("npm"));
        Toolchain provider = app.toolchains.require("clang");
        assertFalse(
            app
                .executionPolicy(provider)
                .required.contains(com.forgeide.core.sandbox.SandboxPolicy.Restriction.NO_PROCESS_CREATION)
        );
        app.settings.set("requireHardLimits", true, false);
        try {
            app.executionPolicy(provider).requireSupported(provider.restrictions());
            fail();
        } catch (SecurityException expected) {}
    }

    @Test
    public void deadlineAndDisableStopRealNativeCodeAndAllowRerun() throws Exception {
        workspace.write("loop.py", "while True: pass\n");
        assertEquals(
            output.toString(),
            Integer.valueOf(124),
            start("python", "python", "loop.py", List.of(), Map.of(), "", 200, 4096, false, null).result.get(
                100,
                TimeUnit.SECONDS
            )
        );
        CountDownLatch ready = new CountDownLatch(1);
        workspace.write("loop.py", "print('LOOP_READY_42',flush=True)\nwhile True: pass\n");
        NativeRuntimeClient.Run looping = start(
            "python",
            "python",
            "loop.py",
            List.of(),
            Map.of(),
            "",
            60000,
            4096,
            false,
            bytes -> {
                if (output.toString().contains("LOOP_READY_42")) ready.countDown();
            }
        );
        assertTrue(output.toString(), ready.await(20, TimeUnit.SECONDS));
        app.settings.set("native.python", false, false);
        app.nativeRuntime.stopAll();
        assertEquals(Integer.valueOf(130), looping.result.get(10, TimeUnit.SECONDS));
        app.settings.set("native.python", true, false);
        workspace.write("ok.py", "print('RERUN_42')\n");
        assertRun("python", "python", "ok.py");
        assertTrue(output.toString(), output.toString().contains("RERUN_42"));
    }

    @Test
    public void realPtySupportsTerminalInputResizeInterruptAndRecreation() throws Exception {
        CountDownLatch first = new CountDownLatch(1),
            after = new CountDownLatch(1);
        StringBuffer terminalOutput = new StringBuffer();
        NativeRuntimeClient.Run pty = start(
            "bash",
            "shellscript",
            "",
            List.of(),
            Map.of(),
            "",
            60000,
            262144,
            true,
            bytes -> {
                terminalOutput.append(new String(bytes, StandardCharsets.UTF_8));
                if (terminalOutput.toString().contains("\r\nTTY_READY_42\r\n")) first.countDown();
                if (terminalOutput.toString().contains("\r\nAFTER_INTERRUPT_42\r\n")) after.countDown();
            }
        );
        assertTrue(
            "Native Bash must actually reach its launch callback",
            connected.await(60, TimeUnit.SECONDS)
        );
        app.nativeRuntime.resize(pty, 32, 100);
        app.nativeRuntime.write(
            pty,
            "test -t 0 && printf '\\x54\\x54\\x59_READY_%d\\n' \"$((6*7))\"; while :; do :; done\r".getBytes(
                StandardCharsets.UTF_8
            )
        );
        assertTrue(terminalOutput.toString(), first.await(20, TimeUnit.SECONDS));
        app.nativeRuntime.write(pty, new byte[] { 3 });
        app.nativeRuntime.write(
            pty,
            "printf '\\x41\\x46\\x54\\x45\\x52_INTERRUPT_%d\\n' \"$((6*7))\"\r".getBytes(
                StandardCharsets.UTF_8
            )
        );
        assertTrue(terminalOutput.toString(), after.await(20, TimeUnit.SECONDS));
        app.nativeRuntime.stop(pty);
        assertEquals(Integer.valueOf(130), pty.result.get(10, TimeUnit.SECONDS));
        AtomicReference<NativeTerminalState> retained = new AtomicReference<>();
        try (
            ActivityScenario<NativeTerminalActivity> scenario = ActivityScenario.launch(
                NativeTerminalActivity.class
            )
        ) {
            scenario.onActivity(a -> {
                assertNotNull(a.findViewById(NativeTerminalActivity.TERMINAL_ID));
                retained.set(app.nativeTerminal);
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                assertSame(retained.get(), app.nativeTerminal);
                assertNotNull(a.findViewById(NativeTerminalActivity.TERMINAL_ID));
            });
        }
        retained.get().run.result.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void cancelledNativePreparationAndOldStopCannotOwnTheNextRun() throws Exception {
        workspace.write("ok.py", "print('NEW_OWNER_42',flush=True)\n");
        NativeRuntimeClient.Run old = start(
            "python",
            "python",
            "ok.py",
            List.of(),
            Map.of(),
            "",
            60000,
            4096,
            false,
            null
        );
        app.nativeRuntime.stop(old);
        assertEquals(Integer.valueOf(130), old.result.get(10, TimeUnit.SECONDS));
        NativeRuntimeClient.Run next = start(
            "python",
            "python",
            "ok.py",
            List.of(),
            Map.of(),
            "",
            60000,
            4096,
            false,
            null
        );
        app.nativeRuntime.stop(old);
        assertEquals(output.toString(), Integer.valueOf(0), next.result.get(100, TimeUnit.SECONDS));
        assertTrue(output.toString(), output.toString().contains("NEW_OWNER_42"));
    }
}
