package com.forgeide.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.app.execution.ExecutionClient;
import com.forgeide.core.sandbox.SandboxPolicy;
import com.forgeide.core.toolchain.RunConfiguration;
import com.forgeide.core.util.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SandboxDeviceTest {

    private RunConfiguration config() {
        return new RunConfiguration(
            "Device test",
            "javascript",
            "test.js",
            "",
            List.of(),
            Map.of(),
            "",
            List.of(),
            List.of(),
            List.of()
        );
    }

    @Test
    public void runtimeHasDifferentUidAndNoNetworkPermission() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File witness = new java.io.File(context.getFilesDir(), "state/isolation-witness.txt");
        assertEquals(
            new java.io.File(
                new java.io.File(context.getApplicationInfo().dataDir, "files"),
                "state/isolation-witness.txt"
            ).getCanonicalFile(),
            witness.getCanonicalFile()
        );
        com.forgeide.core.util.AtomicFiles.write(witness.toPath(), "host-private-fixture");
        assertTrue(
            "The witness must exist and be readable by the host before testing denial",
            witness.isFile() && witness.canRead()
        );
        ExecutionClient client = new ExecutionClient(context);
        AtomicReference<CompletableFuture<Integer>> completion = new AtomicReference<>();
        AtomicReference<JsonObject> identity = new AtomicReference<>();
        StringBuffer output = new StringBuffer();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            completion.set(
                client.run(
                    "print(6 * 7, typeof Packages, typeof java, typeof require);",
                    config(),
                    SandboxPolicy.safeDefault(),
                    List.of(),
                    false,
                    output::append,
                    output::append,
                    event -> {
                        JsonObject j = JsonObject.parse(event);
                        if (j.getString("event", "").equals("sandboxReady")) identity.set(j);
                    }
                )
            )
        );
        Integer exit = completion.get().get(20, TimeUnit.SECONDS);
        assertEquals(output.toString(), Integer.valueOf(0), exit);
        assertTrue(output.toString(), output.toString().contains("42 undefined undefined undefined"));
        JsonObject ready = identity.get();
        assertNotNull("Runtime must report OS isolation identity", ready);
        assertNotEquals(android.os.Process.myUid(), ready.getInt("uid", 0));
        assertFalse(ready.getBoolean("hostFilesReadable", true));
        assertTrue(ready.getBoolean("isolated", false));
        assertFalse(ready.getBoolean("networkPermission", true));
        assertTrue("Kernel must deny socket creation", ready.getBoolean("networkSocketDenied", false));
        java.nio.file.Files.deleteIfExists(witness.toPath());
    }

    @Test
    public void runawayExecutionIsBoundedAndCanBeCancelled() throws Exception {
        ExecutionClient client = new ExecutionClient(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        AtomicReference<CompletableFuture<Integer>> completion = new AtomicReference<>();
        SandboxPolicy policy = new SandboxPolicy(500, 1024, SandboxPolicy.safeDefault().required);
        StringBuffer output = new StringBuffer();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            completion.set(
                client.run(
                    "while (true) {}",
                    config(),
                    policy,
                    List.of(),
                    false,
                    output::append,
                    output::append,
                    s -> {}
                )
            )
        );
        int exit = completion.get().get(12, TimeUnit.SECONDS);
        assertTrue("exit=" + exit + " " + output, exit == 124 || exit == 137);
    }

    @Test
    public void cancelIsImmediateAndOldCallbacksCannotKillTheNextRun() throws Exception {
        ExecutionClient client = new ExecutionClient(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        AtomicReference<CompletableFuture<Integer>> first = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            first.set(
                client.run(
                    "while(true){}",
                    config(),
                    SandboxPolicy.safeDefault(),
                    List.of(),
                    false,
                    s -> {},
                    s -> {},
                    s -> ready.countDown()
                )
            )
        );
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(client::cancel);
        assertEquals(Integer.valueOf(130), first.get().get(5, TimeUnit.SECONDS));
        AtomicReference<CompletableFuture<Integer>> second = new AtomicReference<>();
        StringBuffer output = new StringBuffer();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            second.set(
                client.run(
                    "print('second run');",
                    config(),
                    SandboxPolicy.safeDefault(),
                    List.of(),
                    false,
                    output::append,
                    output::append,
                    s -> {}
                )
            )
        );
        Integer exit = second.get().get(15, TimeUnit.SECONDS);
        assertEquals(output.toString(), Integer.valueOf(0), exit);
        assertTrue(output.toString().contains("second run"));
    }

    @Test
    public void realSqliteRunsInMemoryWithoutAHostFilesystem() throws Exception {
        ExecutionClient client = new ExecutionClient(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        AtomicReference<CompletableFuture<Integer>> result = new AtomicReference<>();
        StringBuffer output = new StringBuffer();
        RunConfiguration sql = new RunConfiguration(
            "SQL test",
            "sql",
            "test.sql",
            "",
            List.of(),
            Map.of(),
            "",
            List.of(),
            List.of(),
            List.of()
        );
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            result.set(
                client.run(
                    "CREATE TABLE test(n INTEGER); INSERT INTO test VALUES(42); SELECT n FROM test;",
                    sql,
                    SandboxPolicy.safeDefault(),
                    List.of(),
                    false,
                    output::append,
                    output::append,
                    s -> {}
                )
            )
        );
        Integer exit = result.get().get(15, TimeUnit.SECONDS);
        assertEquals(output.toString(), Integer.valueOf(0), exit);
        assertTrue(output.toString(), output.toString().contains("42"));
    }

    @Test
    public void sqlCannotAttachHostFilesOrLoadNativeExtensions() throws Exception {
        for (String source : List.of(
            "ATTACH '/data/local/tmp/forbidden.db' AS host;",
            "SELECT load_extension('/system/lib64/libc.so');"
        )) {
            ExecutionClient client = new ExecutionClient(
                InstrumentationRegistry.getInstrumentation().getTargetContext()
            );
            AtomicReference<CompletableFuture<Integer>> completion = new AtomicReference<>();
            StringBuffer output = new StringBuffer();
            RunConfiguration sql = new RunConfiguration(
                "SQL security",
                "sql",
                "test.sql",
                "",
                List.of(),
                Map.of(),
                "",
                List.of(),
                List.of(),
                List.of()
            );
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                completion.set(
                    client.run(
                        source,
                        sql,
                        SandboxPolicy.safeDefault(),
                        List.of(),
                        false,
                        output::append,
                        output::append,
                        s -> {}
                    )
                )
            );
            int exit = completion.get().get(15, TimeUnit.SECONDS);
            assertEquals(output.toString(), 1, exit);
        }
    }

    @Test
    public void sqliteNativeWorkIsStoppedByAnExternalWatchdog() throws Exception {
        ExecutionClient client = new ExecutionClient(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        AtomicReference<CompletableFuture<Integer>> completion = new AtomicReference<>();
        StringBuffer output = new StringBuffer();
        RunConfiguration sql = new RunConfiguration(
            "SQL deadline",
            "sql",
            "test.sql",
            "",
            List.of(),
            Map.of(),
            "",
            List.of(),
            List.of(),
            List.of()
        );
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            completion.set(
                client.run(
                    "WITH RECURSIVE counter(x) AS (SELECT 0 UNION ALL SELECT x+1 FROM counter) SELECT sum(x) FROM counter;",
                    sql,
                    new SandboxPolicy(500, 1024, SandboxPolicy.safeDefault().required),
                    List.of(),
                    false,
                    output::append,
                    output::append,
                    s -> {}
                )
            )
        );
        int exit = completion.get().get(12, TimeUnit.SECONDS);
        assertTrue("exit=" + exit + " " + output, exit == 124 || exit == 137);
    }

    @Test
    public void completedRunCancellationTokenCannotCancelANewerRun() throws Exception {
        ExecutionClient client = new ExecutionClient(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        com.forgeide.app.execution.IsolatedToolchain provider =
            new com.forgeide.app.execution.IsolatedToolchain("javascript", client);
        com.forgeide.core.util.CancellationToken old = new com.forgeide.core.util.CancellationToken(),
            current = new com.forgeide.core.util.CancellationToken();
        AtomicReference<CompletableFuture<Integer>> first = new AtomicReference<>(),
            second = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            first.set(provider.run("print(1);", config(), SandboxPolicy.safeDefault(), old, s -> {}, s -> {}))
        );
        assertEquals(Integer.valueOf(0), first.get().get(15, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            second.set(
                provider.run(
                    "print('ready');while(true){}",
                    config(),
                    SandboxPolicy.safeDefault(),
                    current,
                    s -> ready.countDown(),
                    s -> {}
                )
            )
        );
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        old.cancel();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(client.running());
        current.cancel();
        assertEquals(Integer.valueOf(130), second.get().get(5, TimeUnit.SECONDS));
    }

    @Test
    public void debuggerReallyStepsEvaluatesAndContinuesAcrossBinder() throws Exception {
        ExecutionClient client = new ExecutionClient(
            InstrumentationRegistry.getInstrumentation().getTargetContext()
        );
        AtomicReference<CompletableFuture<Integer>> result = new AtomicReference<>();
        BlockingQueue<JsonObject> stops = new LinkedBlockingQueue<>(),
            evaluations = new LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicInteger continued = new java.util.concurrent.atomic.AtomicInteger();
        StringBuffer output = new StringBuffer();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                result.set(
                    client.run(
                        "var counter = 1;\ncounter += 41;\nprint(counter);",
                        config(),
                        SandboxPolicy.safeDefault(),
                        List.of(),
                        true,
                        output::append,
                        output::append,
                        json -> {
                            JsonObject event = JsonObject.parse(json);
                            switch (event.getString("event", "")) {
                                case "stopped":
                                    stops.add(event);
                                    break;
                                case "evaluation":
                                    evaluations.add(event);
                                    break;
                                case "continued":
                                    continued.incrementAndGet();
                                    break;
                            }
                        }
                    )
                )
            );
            JsonObject initial = stops.poll(10, TimeUnit.SECONDS);
            assertNotNull("Must pause in the real interpreter", initial);
            assertFalse(initial.getArray("stack").isEmpty());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> client.control("over", ""));
            JsonObject stepped = stops.poll(5, TimeUnit.SECONDS);
            assertNotNull("Step over must reach the next source line", stepped);
            JsonObject frame = new JsonObject((Map<String, Object>) stepped.getArray("stack").get(0));
            assertEquals("test.js", frame.getString("file", ""));
            assertEquals(2, frame.getInt("line", 0));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                client.control("evaluate", "counter + 41")
            );
            JsonObject watch = evaluations.poll(5, TimeUnit.SECONDS);
            assertNotNull("Watch must evaluate in the paused scope", watch);
            assertEquals("42", watch.getString("value", ""));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> client.control("continue", ""));
            assertEquals(output.toString(), Integer.valueOf(0), result.get().get(10, TimeUnit.SECONDS));
            assertTrue(output.toString(), output.toString().contains("42"));
            assertTrue("Resume state must be reported, not left as paused", continued.get() >= 2);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(client::cancel);
        }
    }

    @Test
    public void strictUnsupportedLimitsRejectBeforeBinding() {
        Set<SandboxPolicy.Restriction> required = EnumSet.copyOf(SandboxPolicy.safeDefault().required);
        required.add(SandboxPolicy.Restriction.HARD_MEMORY_LIMIT);
        try {
            new ExecutionClient(InstrumentationRegistry.getInstrumentation().getTargetContext()).run(
                "print(1)",
                config(),
                new SandboxPolicy(1000, 1024, required),
                List.of(),
                false,
                s -> {},
                s -> {},
                s -> {}
            );
            fail("Must fail closed");
        } catch (SecurityException expected) {}
    }
}
