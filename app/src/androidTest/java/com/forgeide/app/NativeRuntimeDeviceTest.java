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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.ZipFile;
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
        active.result.whenComplete((exit, error) -> {
            if (error != null) output.append("Native run failed: ").append(error);
            connection.countDown();
        });
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

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int count;
        while ((count = input.read(block)) != -1) {
            if (bytes.size() + count > 65536) throw new IOException("Fixture response exceeds 64 KiB");
            bytes.write(block, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void untrustedShellCannotAcquireTheCompanionsSignaturePermission() throws Exception {
        String response;
        try (
            InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .executeShellCommand(
                        // API 26 UiAutomation captures stdout only. The single no-space -c
                        // argument redirects stderr too, without Runtime.exec quote parsing.
                        "/system/bin/sh -c (am${IFS}startservice${IFS}-n${IFS}com.forgex/com.forgeide.runtime.NativeRuntimeService)2>&1"
                    )
            )
        ) {
            response = readBounded(input);
        }
        assertTrue(response, response.contains("com.forgeide.app.permission.NATIVE_RUNTIME"));
        assertTrue(
            response,
            response.contains("Permission Denial") ||
                response.contains("SecurityException") ||
                response.contains("Error: Requires permission com.forgeide.app.permission.NATIVE_RUNTIME")
        );
        assertFalse(app.nativeRuntime.busy());
    }

    @Test
    public void nativeNetworkNeedsOptInAndBinderTransactionsStayDenied() throws Exception {
        workspace.write(
            "kernel-guards.py",
            String.join(
                "\n",
                "import socket,os,fcntl,errno",
                "try:",
                " socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)",
                " raise AssertionError('UNIX proxy bypass')",
                "except PermissionError:",
                " print('UNIX_PROXY_DENIED')",
                "with open('/dev/null','rb') as fd:",
                " try:",
                "  fcntl.ioctl(fd,0xc0306201,bytes(48))",
                "  raise AssertionError('Binder bypass')",
                " except PermissionError:",
                "  print('BINDER_DENIED')",
                ""
            )
        );
        assertRun("python", "python", "kernel-guards.py");
        assertTrue(output.toString(), output.toString().contains("UNIX_PROXY_DENIED"));
        assertTrue(output.toString(), output.toString().contains("BINDER_DENIED"));
        app.settings.set("native.network", true, false);
        workspace.write(
            "network-on.py",
            String.join(
                "\n",
                "import socket,fcntl",
                "with socket.socket() as server, socket.socket() as client:",
                " server.bind(('127.0.0.1',0))",
                " server.listen(1)",
                " client.connect(server.getsockname())",
                " peer,_=server.accept()",
                " with peer:",
                "  client.sendall(b'42')",
                "  print('NETWORK_OPT_IN_'+peer.recv(2).decode())",
                "with socket.socket(socket.AF_UNIX): pass",
                "with open('/dev/null','rb') as fd:",
                " try:",
                "  fcntl.ioctl(fd,0xc0306201,bytes(48))",
                "  raise AssertionError('Binder bypass after network opt-in')",
                " except PermissionError:",
                "  print('BINDER_STILL_DENIED')",
                ""
            )
        );
        assertRun("python", "python", "network-on.py");
        assertTrue(output.toString(), output.toString().contains("NETWORK_OPT_IN_42"));
        assertTrue(output.toString(), output.toString().contains("BINDER_STILL_DENIED"));
    }

    @Test
    public void nativeWritesRequireExplicitBoundedExportAndPrivateMetadataStaysOut() throws Exception {
        workspace.write("original.txt", "IDE original");
        workspace.write(".git/config", "fixture private Git metadata");
        workspace.write(".forge/settings.json", "{\"native.network\":true}");
        workspace.write(
            "export.py",
            "from pathlib import Path\nimport time\nassert not Path('.git').exists()\nassert not Path('.forge').exists()\nPath('native-generated.txt').write_text('NATIVE_GENERATED_42')\nprint('EXPORT_READY_42',flush=True)\nwhile True: time.sleep(1)\n"
        );
        CountDownLatch ready = new CountDownLatch(1);
        NativeRuntimeClient.Run run = start(
            "python",
            "python",
            "export.py",
            List.of(),
            Map.of(),
            "",
            60000,
            4096,
            false,
            bytes -> {
                if (output.toString().contains("EXPORT_READY_42")) ready.countDown();
            }
        );
        assertTrue(output.toString(), ready.await(110, TimeUnit.SECONDS));
        assertFalse(Files.exists(workspace.resolve("native-generated.txt")));
        assertFalse(app.settings.bool("native.network"));
        Path destination = Files.createTempFile(app.projects, "native-export-fixture-", ".zip");
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                app,
                app.getPackageName() + ".files",
                destination.toFile()
            );
            app.nativeRuntime.exportProject(run, uri).get(15, TimeUnit.SECONDS);
            try (ZipFile zip = new ZipFile(destination.toFile())) {
                assertNull(zip.getEntry(".git/config"));
                assertNull(zip.getEntry(".forge/settings.json"));
                assertNotNull(zip.getEntry("native-generated.txt"));
                try (InputStream input = zip.getInputStream(zip.getEntry("native-generated.txt"))) {
                    assertEquals("NATIVE_GENERATED_42", readBounded(input));
                }
                try (InputStream input = zip.getInputStream(zip.getEntry("original.txt"))) {
                    assertEquals("IDE original", readBounded(input));
                }
            }
            assertEquals("IDE original", workspace.read("original.txt"));
        } finally {
            Files.deleteIfExists(destination);
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
        workspace.write(
            "check-off.sh",
            "if command -v python >/dev/null; then echo FLAG_FAILED; exit 1; fi\nprintf 'PYTHON_SWITCH_OFF\\n'\n"
        );
        assertRun("bash", "shellscript", "check-off.sh");
        assertTrue(output.toString(), output.toString().contains("PYTHON_SWITCH_OFF"));
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
    public void stopReapsDetachedNativeDescendantsBeforeTheNextRun() throws Exception {
        workspace.write(
            "descendants.py",
            String.join(
                "\n",
                "import subprocess,time",
                "child=subprocess.Popen(['bash','--noprofile','--norc','-c','sleep 120 & printf \"NATIVE_PID_%s\\\\n\" \"$!\"; wait'],start_new_session=True)",
                "print('NATIVE_PID_'+str(child.pid),flush=True)",
                "other=subprocess.Popen(['sleep','120'],start_new_session=True)",
                "print('NATIVE_PID_'+str(other.pid),flush=True)",
                "while True: time.sleep(1)",
                ""
            )
        );
        Set<Integer> pids = new ConcurrentSkipListSet<>();
        CountDownLatch descendants = new CountDownLatch(1);
        NativeRuntimeClient.Run run = start(
            "python",
            "python",
            "descendants.py",
            List.of(),
            Map.of(),
            "",
            60000,
            8192,
            false,
            bytes -> {
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("NATIVE_PID_(\\d+)").matcher(
                    output.toString()
                );
                while (match.find()) pids.add(Integer.parseInt(match.group(1)));
                if (pids.size() == 3) descendants.countDown();
            }
        );
        assertTrue(output.toString(), descendants.await(110, TimeUnit.SECONDS));
        app.nativeRuntime.stop(run);
        assertEquals(Integer.valueOf(130), run.result.get(10, TimeUnit.SECONDS));
        workspace.write(
            "check-stopped.py",
            "import os\nfor pid in " +
                pids.toString() +
                ":\n try:\n  os.kill(pid,0)\n except ProcessLookupError:\n  continue\n raise AssertionError('Native descendant survived stop: '+str(pid))\nprint('DESCENDANTS_STOPPED_42')\n"
        );
        assertRun("python", "python", "check-stopped.py");
        assertTrue(output.toString(), output.toString().contains("DESCENDANTS_STOPPED_42"));
    }

    @Test
    public void realPtySupportsTerminalInputResizeInterruptAndRecreation() throws Exception {
        CountDownLatch first = new CountDownLatch(1),
            after = new CountDownLatch(1);
        StringBuffer terminalOutput = new StringBuffer();
        CountDownLatch initialPrompt = new CountDownLatch(1),
            interruptedPrompt = new CountDownLatch(1);
        AtomicInteger promptFrom = new AtomicInteger();
        AtomicReference<CountDownLatch> promptWaiter = new AtomicReference<>(initialPrompt);
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
                if (terminalOutput.substring(promptFrom.get()).contains(" $ ")) promptWaiter
                    .get()
                    .countDown();
                if (terminalOutput.toString().contains("TTY_READY_42_32_100\r\n")) first.countDown();
                if (terminalOutput.toString().contains("AFTER_INTERRUPT_42\r\n")) after.countDown();
            }
        );
        assertTrue(
            "Native Bash must actually reach its launch callback",
            connected.await(110, TimeUnit.SECONDS)
        );
        assertNotNull(
            "Native Bash launch failed: " +
                output +
                "; completion=" +
                pty.result
                    .handle((exit, error) -> error == null ? String.valueOf(exit) : error.toString())
                    .getNow("still running"),
            identity.get()
        );
        // onReady acknowledges the fork/transport, not Bash's initial readline setup.
        // Wait for actual prompt output before sending keys, and again after SIGINT.
        assertTrue(terminalOutput.toString(), initialPrompt.await(20, TimeUnit.SECONDS));
        app.nativeRuntime.resize(pty, 32, 100);
        app.nativeRuntime.write(
            pty,
            "bash --noprofile --norc -c 'test -t 0 || exit 1; set -- $(stty size); printf \"\\x54\\x54\\x59_READY_%d_%s_%s\\n\" \"$((6*7))\" \"$1\" \"$2\"; exec sleep 60'\r".getBytes(
                StandardCharsets.UTF_8
            )
        );
        assertTrue(terminalOutput.toString(), first.await(20, TimeUnit.SECONDS));
        promptFrom.set(terminalOutput.length());
        promptWaiter.set(interruptedPrompt);
        app.nativeRuntime.write(pty, new byte[] { 3 });
        assertTrue(terminalOutput.toString(), interruptedPrompt.await(20, TimeUnit.SECONDS));
        app.nativeRuntime.write(
            pty,
            "printf '\\x50\\x49\\x50\\x45_RESULT_'; printf '%d\\n' 42 | cat; printf '\\x41\\x46\\x54\\x45\\x52_INTERRUPT_%d\\n' \"$((6*7))\"\r".getBytes(
                StandardCharsets.UTF_8
            )
        );
        assertTrue(terminalOutput.toString(), after.await(20, TimeUnit.SECONDS));
        assertTrue(terminalOutput.toString(), terminalOutput.toString().contains("PIPE_RESULT_42\r\n"));
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
