package com.forgeide.app;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.view.View;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.core.util.AtomicFiles;
import io.github.rosemoe.sora.widget.CodeEditor;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class WorkspaceDeviceTest {

    @Rule
    public ActivityScenarioRule<MainActivity> activity = new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void editUndoRedoSaveAndRecreatePreserveRealDocument() throws Exception {
        AtomicReference<ForgeApplication> state = new AtomicReference<>();
        AtomicReference<ForgeApplication.OpenDocument> doc = new AtomicReference<>();
        AtomicReference<String> original = new AtomicReference<>();
        String marker = "// device-test-" + System.currentTimeMillis() + "\n";
        activity
            .getScenario()
            .onActivity(a -> {
                ForgeApplication app = (ForgeApplication) a.getApplication();
                state.set(app);
                CodeEditor editor = a.findViewById(MainActivity.EDITOR_ID);
                ForgeApplication.OpenDocument d = app.find(app.activePath);
                doc.set(d);
                original.set(d.content.toString());
                d.content.insert(0, 0, marker);
                assertTrue(d.dirty());
                assertTrue(editor.getText().toString().startsWith(marker));
                editor.undo();
                assertEquals(original.get(), editor.getText().toString());
                editor.redo();
                assertTrue(editor.getText().toString().startsWith(marker));
            });
        state.get().saveAll().get(10, TimeUnit.SECONDS);
        assertFalse(doc.get().dirty());
        assertTrue(doc.get().owner.read(doc.get().path).startsWith(marker));
        activity.getScenario().recreate();
        activity
            .getScenario()
            .onActivity(a -> {
                CodeEditor editor = a.findViewById(MainActivity.EDITOR_ID);
                assertTrue(editor.getText().toString().startsWith(marker));
                editor.getText().replace(0, editor.getText().length(), original.get());
            });
        state.get().saveAll().get(10, TimeUnit.SECONDS);
    }

    @Test
    public void splitSharesContentAndSurvivesRecreation() {
        activity
            .getScenario()
            .onActivity(a -> {
                long time = android.os.SystemClock.uptimeMillis();
                assertTrue(
                    a.dispatchKeyEvent(
                        new android.view.KeyEvent(
                            time,
                            time,
                            android.view.KeyEvent.ACTION_DOWN,
                            android.view.KeyEvent.KEYCODE_BACKSLASH,
                            0,
                            android.view.KeyEvent.META_CTRL_ON
                        )
                    )
                );
                CodeEditor left = a.findViewById(MainActivity.EDITOR_ID),
                    right = a.findViewById(MainActivity.SECONDARY_EDITOR_ID);
                assertNotNull(right);
                assertSame(left.getText(), right.getText());
            });
        activity.getScenario().recreate();
        activity
            .getScenario()
            .onActivity(a -> {
                CodeEditor left = a.findViewById(MainActivity.EDITOR_ID),
                    right = a.findViewById(MainActivity.SECONDARY_EDITOR_ID);
                assertNotNull(right);
                assertSame(left.getText(), right.getText());
            });
    }

    @Test
    public void terminalTranscriptIsRetainedAcrossRecreation() {
        AtomicReference<com.forgeide.core.terminal.TerminalSession> session = new AtomicReference<>();
        AtomicReference<String> before = new AtomicReference<>();
        activity
            .getScenario()
            .onActivity(a -> {
                ForgeApplication app = (ForgeApplication) a.getApplication();
                session.set(app.terminalSessions.get(app.project).get(0));
                before.set(session.get().output());
                session.get().append("recreation-marker\n");
            });
        activity.getScenario().recreate();
        activity
            .getScenario()
            .onActivity(a -> {
                ForgeApplication app = (ForgeApplication) a.getApplication();
                assertSame(session.get(), app.terminalSessions.get(app.project).get(0));
                assertTrue(session.get().output().contains("recreation-marker"));
                session.get().clear();
                session.get().append(before.get());
            });
    }

    @Test
    public void commandPaletteAndNativeControlsArePresent() {
        activity
            .getScenario()
            .onActivity(a -> {
                assertNotNull(a.findViewById(MainActivity.RUN_ID));
                assertNotNull(a.findViewById(MainActivity.SAVE_ID));
                View palette = a.findViewById(MainActivity.PALETTE_ID);
                android.view.ViewGroup content = a.findViewById(android.R.id.content);
                int background = ((android.graphics.drawable.ColorDrawable) content
                        .getChildAt(0)
                        .getBackground()).getColor();
                androidx.core.view.WindowInsetsControllerCompat bars =
                    new androidx.core.view.WindowInsetsControllerCompat(
                        a.getWindow(),
                        a.getWindow().getDecorView()
                    );
                assertEquals(
                    "Status icons must contrast with the native theme",
                    android.graphics.Color.luminance(background) > 0.4,
                    bars.isAppearanceLightStatusBars()
                );
                if (android.os.Build.VERSION.SDK_INT >= 27) assertEquals(
                    android.graphics.Color.luminance(background) > 0.4,
                    bars.isAppearanceLightNavigationBars()
                );
                assertEquals("Command palette", palette.getContentDescription());
                assertTrue(palette.performClick());
            });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void recoveryJournalIsDurableOutsideProject() throws Exception {
        AtomicReference<ForgeApplication> state = new AtomicReference<>();
        AtomicReference<ForgeApplication.OpenDocument> document = new AtomicReference<>();
        AtomicReference<String> original = new AtomicReference<>();
        activity
            .getScenario()
            .onActivity(a -> {
                ForgeApplication app = (ForgeApplication) a.getApplication();
                state.set(app);
                ForgeApplication.OpenDocument doc = app.find(app.activePath);
                document.set(doc);
                original.set(doc.content.toString());
                doc.content.insert(0, 0, "// recovery-device-test\n");
            });
        ForgeApplication app = state.get();
        ForgeApplication.OpenDocument doc = document.get();
        app.io
            .submit(() -> {
                app.journal(doc);
                return null;
            })
            .get(10, TimeUnit.SECONDS);
        assertTrue(
            app.recovery
                .recover()
                .stream()
                .anyMatch(e -> e.path.equals(doc.path) && e.text.startsWith("// recovery-device-test"))
        );
        activity.getScenario().onActivity(a -> doc.content.replace(0, doc.content.length(), original.get()));
        app.saveAll().get(10, TimeUnit.SECONDS);
    }

    @Test
    public void folderRenamePreservesDirtyBuffersRecoveryAndBreakpoints() throws Exception {
        AtomicReference<ForgeApplication> state = new AtomicReference<>();
        activity.getScenario().onActivity(a -> state.set((ForgeApplication) a.getApplication()));
        ForgeApplication app = state.get();
        String source = "rename-fixture-" + java.util.UUID.randomUUID(),
            destination = source + "-moved";
        String originalActive = app.activePath;
        ForgeApplication.OpenDocument doc = app.io
            .submit(() -> {
                app.workspace.write(source + "/main.js", "print('fixture');\n");
                return app.open(source + "/main.js");
            })
            .get(10, TimeUnit.SECONDS);
        try {
            activity
                .getScenario()
                .onActivity(a -> {
                    doc.content.insert(0, 0, "// edits during rename\n");
                    app.breakpoints.condition(doc.path, 1, "count > 0");
                });
            app.io
                .submit(() -> {
                    app.journal(doc);
                    app.moveItem(source, destination);
                    return null;
                })
                .get(10, TimeUnit.SECONDS);
            assertEquals(destination + "/main.js", doc.path);
            assertEquals(doc.path, app.activePath);
            assertTrue(doc.dirty());
            assertTrue(doc.content.toString().startsWith("// edits during rename"));
            assertFalse(Files.exists(app.workspace.resolve(source)));
            assertEquals("print('fixture');\n", app.workspace.read(doc.path));
            assertTrue(
                app.breakpoints
                    .all()
                    .stream()
                    .anyMatch(p -> p.file.equals(doc.path) && p.condition.equals("count > 0"))
            );
            java.util.List<com.forgeide.core.recovery.RecoveryStore.Entry> recovered = app.recovery.recover(
                app.project,
                20,
                app::log
            );
            assertTrue(
                recovered
                    .stream()
                    .anyMatch(e -> e.path.equals(doc.path) && e.text.startsWith("// edits during rename"))
            );
            assertFalse(recovered.stream().anyMatch(e -> e.path.startsWith(source + "/")));
            app.io
                .submit(() -> {
                    app.save(doc);
                    return null;
                })
                .get(10, TimeUnit.SECONDS);
            assertFalse(doc.dirty());
            assertTrue(app.workspace.read(doc.path).startsWith("// edits during rename"));
        } finally {
            activity
                .getScenario()
                .onActivity(a -> {
                    doc.detach();
                    app.documents.remove(doc);
                    app.activePath = originalActive;
                    for (com.forgeide.core.debug.Breakpoints.Point point : app.breakpoints.all()) {
                        if (
                            point.file.startsWith(source + "/") || point.file.startsWith(destination + "/")
                        ) app.breakpoints.toggle(point.file, point.line);
                    }
                });
            app.io
                .submit(() -> {
                    app.recovery.discard(doc.project, source + "/main.js");
                    app.recovery.discard(doc.project, destination + "/main.js");
                    if (Files.exists(app.workspace.resolve(source))) app.workspace.delete(source);
                    if (Files.exists(app.workspace.resolve(destination))) app.workspace.delete(destination);
                    app.persistSession();
                    return null;
                })
                .get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shellCannotRemoveTheBackingFileOfAnOpenEditor() throws Exception {
        AtomicReference<ForgeApplication> state = new AtomicReference<>();
        activity.getScenario().onActivity(a -> state.set((ForgeApplication) a.getApplication()));
        ForgeApplication app = state.get();
        String path = app.activePath;
        String disk = app.workspace.read(path);
        com.forgeide.core.terminal.TerminalSession terminal = app.terminalSessions.get(app.project).get(0);
        com.forgeide.core.terminal.TerminalSession.Result result = app.io
            .submit(() ->
                terminal.execute(
                    "rm '" + path.replace("'", "'\\''") + "'",
                    new com.forgeide.core.util.CancellationToken()
                )
            )
            .get(10, TimeUnit.SECONDS);
        assertEquals(1, result.exitCode);
        assertTrue(result.stderr, result.stderr.contains("Close affected editor tabs"));
        assertEquals(disk, app.workspace.read(path));
    }

    @Test
    public void executionAndDebugShareTheConfiguredPolicyCeilings() {
        activity
            .getScenario()
            .onActivity(a -> {
                ForgeApplication app = (ForgeApplication) a.getApplication();
                com.forgeide.core.sandbox.SandboxPolicy policy = app.executionPolicy();
                assertEquals(app.settings.integer("executionSeconds") * 1000L, policy.timeoutMillis);
                assertEquals(app.settings.integer("outputKiB") * 1024, policy.outputBytes);
                assertEquals(
                    app.settings.bool("requireHardLimits"),
                    policy.required.contains(
                        com.forgeide.core.sandbox.SandboxPolicy.Restriction.HARD_MEMORY_LIMIT
                    )
                );
                assertEquals(
                    app.settings.bool("requireHardLimits"),
                    policy.required.contains(com.forgeide.core.sandbox.SandboxPolicy.Restriction.CPU_QUOTA)
                );
                assertTrue(
                    policy.required.containsAll(
                        com.forgeide.core.sandbox.SandboxPolicy.safeDefault().required
                    )
                );
            });
    }

    @Test
    public void delayedFormattingCannotOverwriteADifferentTab() throws Exception {
        AtomicReference<ForgeApplication> state = new AtomicReference<>();
        AtomicReference<ForgeApplication.OpenDocument> original = new AtomicReference<>();
        activity
            .getScenario()
            .onActivity(a -> {
                ForgeApplication app = (ForgeApplication) a.getApplication();
                state.set(app);
                CodeEditor editor = a.findViewById(MainActivity.EDITOR_ID);
                original.set(
                    app.documents
                        .stream()
                        .filter(doc -> doc.content == editor.getText())
                        .findFirst()
                        .get()
                );
            });
        ForgeApplication app = state.get();
        String path = "format-race-" + java.util.UUID.randomUUID() + ".js";
        String fixture = "const fixture = 'never replace the newly selected tab';\n";
        String before = original.get().content.toString();
        ForgeApplication.OpenDocument other = app.io
            .submit(() -> {
                app.workspace.write(path, fixture);
                return app.open(path);
            })
            .get(20, TimeUnit.SECONDS);
        CountDownLatch occupied = new CountDownLatch(1),
            release = new CountDownLatch(1);
        Future<?> blocker = app.io.submit(() -> {
            occupied.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Test storage gate timed out");
            return null;
        });
        try {
            assertTrue(occupied.await(10, TimeUnit.SECONDS));
            activity
                .getScenario()
                .onActivity(a -> {
                    long time = android.os.SystemClock.uptimeMillis();
                    assertTrue(
                        a.dispatchKeyEvent(
                            new android.view.KeyEvent(
                                time,
                                time,
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_L,
                                0,
                                android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_ALT_ON
                            )
                        )
                    );
                    CodeEditor editor = a.findViewById(MainActivity.EDITOR_ID);
                    for (int i = 0; i < app.documents.size() && editor.getText() != other.content; i++) {
                        a.dispatchKeyEvent(
                            new android.view.KeyEvent(
                                time,
                                time,
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_TAB,
                                0,
                                android.view.KeyEvent.META_CTRL_ON
                            )
                        );
                    }
                    assertSame(
                        "A real native tab switch must happen while the formatting task is queued",
                        other.content,
                        editor.getText()
                    );
                });
            release.countDown();
            blocker.get(20, TimeUnit.SECONDS);
            app.io.submit(() -> {}).get(20, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            activity
                .getScenario()
                .onActivity(a -> {
                    CodeEditor editor = a.findViewById(MainActivity.EDITOR_ID);
                    assertSame(other.content, editor.getText());
                    assertEquals(fixture, editor.getText().toString());
                    assertEquals(before, original.get().content.toString());
                });
        } finally {
            release.countDown();
            blocker.get(20, TimeUnit.SECONDS);
            activity
                .getScenario()
                .onActivity(a -> {
                    long time = android.os.SystemClock.uptimeMillis();
                    CodeEditor editor = a.findViewById(MainActivity.EDITOR_ID);
                    for (
                        int i = 0;
                        i < app.documents.size() && editor.getText() != original.get().content;
                        i++
                    ) {
                        a.dispatchKeyEvent(
                            new android.view.KeyEvent(
                                time,
                                time,
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_TAB,
                                0,
                                android.view.KeyEvent.META_CTRL_ON
                            )
                        );
                    }
                    other.detach();
                    app.documents.remove(other);
                    app.activePath = original.get().path;
                });
            app.io
                .submit(() -> {
                    app.recovery.discard(other.project, path);
                    app.workspace.delete(path);
                    app.persistSession();
                    return null;
                })
                .get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    public void captureNativeWorkspaceScreenshot() throws Exception {
        // Idle input is not a rendered frame. Wait for two actual frames, including system bars.
        CountDownLatch rendered = new CountDownLatch(1);
        activity
            .getScenario()
            .onActivity(a ->
                a
                    .getWindow()
                    .getDecorView()
                    .postOnAnimation(() -> a.getWindow().getDecorView().postOnAnimation(rendered::countDown))
            );
        assertTrue(
            "The native workspace must draw before visual capture",
            rendered.await(10, TimeUnit.SECONDS)
        );
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        // Test-only shell capture survives the connected-test runner uninstalling the fixture app.
        // No shell API or permission is present in the production app or available to executed code.
        try (
            InputStream shell = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .executeShellCommand("screencap -p /data/local/tmp/forge-workspace.png")
            )
        ) {
            byte[] drain = new byte[1024];
            while (shell.read(drain) != -1) {}
        }
        File dir = new File(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),
            "test-screenshots"
        );
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (OutputStream out = new FileOutputStream(new File(dir, "workspace.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        // One manifest plus at most nine notices fits GitHub's ten-notice per-step limit.
        // A wider tablet thumbnail remains legible; original PNGs stay in the normal artifacts.
        ByteArrayOutputStream preview = new ByteArrayOutputStream();
        int width = Math.min(
            bitmap.getWidth(),
            InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getResources()
                        .getConfiguration()
                        .screenWidthDp >=
                    840
                ? 720
                : 360
        );
        int quality = 72;
        for (int attempt = 0; ; attempt++) {
            Bitmap thumbnail = Bitmap.createScaledBitmap(
                bitmap,
                width,
                Math.max(1, (bitmap.getHeight() * width) / bitmap.getWidth()),
                true
            );
            preview.reset();
            assertTrue(thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, preview));
            if (thumbnail != bitmap) thumbnail.recycle();
            if (preview.size() <= 24000) break;
            assertTrue("Native QA thumbnail must fit the bounded annotation transport", attempt < 10);
            if (quality > 42) quality -= 10;
            else width = Math.max(120, (width * 4) / 5);
        }
        Files.write(new File(dir, "workspace-preview.jpg").toPath(), preview.toByteArray());
        // Only opt in on disposable fixture installations. The app itself never logs screenshots.
        if ("true".equals(InstrumentationRegistry.getArguments().getString("visualReport"))) {
            String encoded = android.util.Base64.encodeToString(
                Files.readAllBytes(new File(dir, "workspace-preview.jpg").toPath()),
                android.util.Base64.NO_WRAP
            );
            int chunk = 2800,
                count = (encoded.length() + chunk - 1) / chunk;
            for (int i = 0; i < count; i++) android.util.Log.i(
                "ForgeVisual",
                "FORGE_VISUAL " +
                    i +
                    "/" +
                    count +
                    " " +
                    encoded.substring(i * chunk, Math.min(encoded.length(), (i + 1) * chunk))
            );
        }
        bitmap.recycle();
    }
}
