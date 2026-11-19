package com.forgeide.app;

import static org.junit.Assert.*;

import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.app.nativebridge.*;
import com.forgeide.core.language.*;
import com.forgeide.core.project.Workspace;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real bundled servers under the actual offline companion policy. Missing tools FAIL, never skip. */
@RunWith(AndroidJUnit4.class)
public final class SemanticServerDeviceTest {

    private ForgeApplication app;
    private Workspace workspace;
    private final Map<String, Object> before = new LinkedHashMap<>();
    private NativeRuntimeClient.Run run;
    private LspClient client;
    private LspProjectUris uris;
    private String uri;
    private final StringBuffer stderr = new StringBuffer();
    private final BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
    private Bundle identity;

    @Before
    public void setup() throws Exception {
        app = (ForgeApplication) InstrumentationRegistry.getInstrumentation()
            .getTargetContext()
            .getApplicationContext();
        assertEquals(
            "Install both matching APKs; semantic tests must not skip",
            "",
            app.nativeRuntime.installationProblem()
        );
        for (String tool : NativeTools.ORDER) set(NativeTools.key(tool), false);
        for (String id : LanguageServers.ORDER) set(LanguageServers.key(id), true);
        set("requireHardLimits", false);
        // Language servers must remain offline even with ordinary native networking enabled.
        set("native.network", true);
        workspace = new Workspace(Files.createTempDirectory(app.getCacheDir().toPath(), "semantic-fixture-"));
    }

    private void set(String key, Object value) {
        before.putIfAbsent(key, app.settings.get(key));
        app.settings.set(key, value, false);
    }

    private void stop() throws Exception {
        if (run != null && !run.result.isDone()) {
            app.nativeRuntime.stop(run);
            run.result.get(15, TimeUnit.SECONDS);
        }
        if (client != null) client.close();
        client = null;
        run = null;
    }

    @After
    public void teardown() throws Exception {
        try {
            stop();
        } finally {
            for (Map.Entry<String, Object> entry : before.entrySet())
                app.settings.set(entry.getKey(), entry.getValue(), false);
            if (workspace != null) try (java.util.stream.Stream<Path> files = Files.walk(workspace.root())) {
                for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private void start(String server) throws Exception {
        events.clear();
        stderr.setLength(0);
        CompletableFuture<LspClient> connected = new CompletableFuture<>();
        run = app.nativeRuntime.startServer(workspace, server, new CancellationToken(), info -> {
            try {
                identity = info;
                uris = new LspProjectUris(info.getString("project", ""));
                ParcelFileDescriptor input = info.getParcelable("stdin"),
                    output = info.getParcelable("stdout"),
                    error = info.getParcelable("stderr");
                if (input == null || output == null || error == null) throw new IOException(
                    "Missing raw stdio descriptors"
                );
                Thread errors = new Thread(
                    () -> {
                        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(error)) {
                            byte[] block = new byte[4096];
                            int n;
                            while ((n = stream.read(block)) != -1) {
                                stderr.append(new String(block, 0, n, StandardCharsets.UTF_8));
                                if (stderr.length() > 8192) stderr.delete(0, stderr.length() - 8192);
                            }
                        } catch (IOException ignored) {}
                    },
                    "device-semantic-stderr"
                );
                errors.setDaemon(true);
                errors.start();
                LspClient peer = new LspClient(
                    new ParcelFileDescriptor.AutoCloseInputStream(output),
                    new ParcelFileDescriptor.AutoCloseOutputStream(input),
                    events::add
                );
                client = peer;
                String prefix = info.getString("prefix", "");
                peer
                    .initialize(
                        uris.rootUri(),
                        LanguageServers.initialization(server, prefix),
                        LanguageServers.configuration(server, prefix)
                    )
                    .whenComplete((result, failure) -> {
                        if (failure != null) connected.completeExceptionally(failure);
                        else connected.complete(peer);
                    });
            } catch (Exception failure) {
                connected.completeExceptionally(failure);
            }
        });
        run.result.whenComplete((exit, failure) ->
            connected.completeExceptionally(
                new IOException("Server exited before initialize: " + exit + "; " + stderr, failure)
            )
        );
        try {
            client = connected.get(150, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(server + " failed: " + stderr + "; runtime=" + run.message, failure);
        }
        assertNotEquals(android.os.Process.myUid(), identity.getInt("uid"));
        assertFalse(
            "Semantic network must ignore the native-network opt-in",
            identity.getBoolean("network", true)
        );
        assertEquals(server, identity.getString("server"));
        for (String tool : NativeTools.ORDER)
            assertFalse(
                "Server consent must not enable user execution: " + tool,
                app.settings.bool(NativeTools.key(tool))
            );
    }

    private void open(String path, String language, String text) throws Exception {
        uri = uris.uri(path);
        client.open(uri, language, 1, text);
    }

    private JsonObject at(String method, int line, int column) throws Exception {
        return client.at(method, uri, line, column).get(35, TimeUnit.SECONDS);
    }

    private boolean completion(String text, int cursor, int prefixStart, int line, int column, String label)
        throws Exception {
        List<LspResults.Completion> items = LspResults.completions(
            at("completion", line, column),
            text,
            cursor,
            prefixStart
        );
        return items.stream().anyMatch(item -> item.label.trim().equals(label));
    }

    /**
     * Waits for a publishDiagnostics event for the open document matching the expected version and
     * emptiness, plus every content marker. The bundled typescript-language-server omits the
     * publishDiagnostics version field, so unversioned events are matched by content instead of
     * being trusted as the requested version; a stale pre-change publish therefore keeps the wait
     * running instead of satisfying it.
     */
    private JsonObject diagnostics(int version, boolean nonempty, String... markers) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 35000;
        String last = "";
        while (android.os.SystemClock.elapsedRealtime() < end) {
            JsonObject event = events.poll(
                Math.max(1, end - android.os.SystemClock.elapsedRealtime()),
                TimeUnit.MILLISECONDS
            );
            if (event == null) break;
            JsonObject params = event.getObject("params");
            if (
                event.getString("method", "").equals("textDocument/publishDiagnostics") &&
                params.getString("uri", "").equals(uri) &&
                (!params.has("version") || params.getInt("version", version) == version) &&
                !params.getArray("diagnostics").isEmpty() == nonempty
            ) {
                last = params.toJson();
                boolean complete = true;
                for (String marker : markers) if (!last.contains(marker)) complete = false;
                if (complete) return params;
            }
        }
        throw new AssertionError(
            "No publishDiagnostics for version " + version +
            (markers.length == 0 ? "" : " containing \"" + String.join("\", \"", markers) + "\"") +
            "; last seen: " + last + "; stderr=" + stderr
        );
    }

    @Test
    public void clangdResolvesCStructuresBundledHeadersCompletionDefinitionAndLiveErrors() throws Exception {
        String source =
            "#include <stddef.h>\nstruct Rocket { int speed; };\nint main(void) { size_t n = 1; struct Rocket rocket; rocket.speed = 42; return rocket.speed + n; }\n";
        workspace.write("main.c", source);
        start("clangd");
        open("main.c", "c", source);
        int column = source.split("\n")[2].indexOf("rocket.speed") + 7;
        int cursor = source.indexOf("rocket.speed") + 7;
        assertTrue(
            "clangd did not infer the structure's member: " + stderr,
            completion(source, cursor, cursor, 2, column, "speed")
        );
        assertFalse(LspResults.locations(at("definition", 2, column + 1), uris).isEmpty());
        assertTrue(LspResults.hover(at("hover", 2, column + 1)).contains("int"));
        assertFalse(
            LspResults.symbols(client.symbols(uri).get(35, TimeUnit.SECONDS), uris, "main.c").isEmpty()
        );
        events.clear();
        client.change(uri, 2, "int main(void) { return missing_identifier; }\n");
        diagnostics(2, true, "missing_identifier");
    }

    @Test
    public void pyrightInfersClassMembersAndReportsUnsavedTypeErrorsWithUserPythonOff() throws Exception {
        String source = "class Rocket:\n    speed: int = 42\nrocket = Rocket()\nrocket.speed\n";
        workspace.write("main.py", source);
        start("pyright");
        open("main.py", "python", source);
        int cursor = source.lastIndexOf("rocket.speed") + 7;
        assertTrue(completion(source, cursor, cursor, 3, 7, "speed"));
        assertTrue(LspResults.hover(at("hover", 2, 2)).contains("Rocket"));
        assertFalse(LspResults.locations(at("definition", 3, 9), uris).isEmpty());
        client.change(uri, 2, "value: int = 'wrong'\n");
        diagnostics(2, true);
        assertEquals(source, workspace.read("main.py"));
    }

    @Test
    public void typescriptUsesBundledTsserverForInferenceReferencesAndTypeDiagnosticsWithNodeOff()
        throws Exception {
        String source =
            "function twice(value: number): number { return value * 2; }\nconst answer = twice(21);\nanswer.toFi\n";
        workspace.write("main.ts", source);
        start("typescript");
        open("main.ts", "typescript", source);
        int cursor = source.indexOf("answer.toFi") + 11;
        assertTrue(completion(source, cursor, cursor - 4, 2, 11, "toFixed"));
        assertTrue(LspResults.hover(at("hover", 1, 7)).contains("number"));
        assertFalse(LspResults.locations(at("definition", 1, 17), uris).isEmpty());
        assertTrue(
            LspResults.locations(client.references(uri, 0, 11).get(35, TimeUnit.SECONDS), uris).size() >= 2
        );
        events.clear();
        client.change(uri, 2, "const answer: number = 'wrong';\n");
        diagnostics(2, true, "2322");
    }

    @Test
    public void bashWasmParserResolvesRealFunctionsWithoutDownloadingShellcheckOrShfmt() throws Exception {
        String source = "#!/bin/bash\ngreet() { echo hello; }\ngreet\n";
        workspace.write("main.sh", source);
        start("bashls");
        open("main.sh", "shellscript", source);
        diagnostics(1, false); // The server's parse-complete signal, not a timing sleep.
        assertTrue(client.symbols(uri).get(35, TimeUnit.SECONDS).toJson().contains("greet"));
        assertFalse(LspResults.locations(at("definition", 2, 2), uris).isEmpty());
        int cursor = source.lastIndexOf("greet") + 3;
        assertTrue(completion(source, cursor, cursor - 3, 2, 3, "greet"));
    }

    @Test
    public void htmlServerHasRealElementCompletions() throws Exception {
        workspace.write("index.html", "<di");
        start("html");
        open("index.html", "html", "<di");
        assertTrue(
            LspResults.completions(at("completion", 0, 3), "<di", 3, 1)
                .stream()
                .anyMatch(item -> item.label.contains("div"))
        );
    }

    @Test
    public void cssServerHasPropertyCompletionAndHover() throws Exception {
        workspace.write("style.css", "a { colo }");
        start("css");
        open("style.css", "css", "a { colo }");
        assertTrue(completion("a { colo }", 8, 4, 0, 8, "color"));
        client.change(uri, 2, "a { color: #fff; }");
        assertTrue(LspResults.hover(at("hover", 0, 6)).toLowerCase(Locale.ROOT).contains("color"));
    }

    @Test
    public void jsonServerValidatesChangesAndProvidesActualFormattingEdits() throws Exception {
        workspace.write("main.json", "{\"a\":}");
        start("json");
        open("main.json", "json", "{\"a\":}");
        diagnostics(1, true);
        events.clear();
        client.change(uri, 2, "{\"a\":1}");
        diagnostics(2, false);
        String formatted = LspResults.applyEdits(
            "{\"a\":1}",
            client.format(uri, 2, true).get(35, TimeUnit.SECONDS)
        );
        assertTrue(formatted, formatted.contains("\"a\": 1"));
        assertEquals("{\"a\":}", workspace.read("main.json"));
    }

    @Test
    public void disabledServerAndStrictQuotaPolicyRefuseRatherThanSilentlyFallingBack() {
        app.settings.set("native.lsp.pyright", false, false);
        try {
            app.nativeRuntime.startServer(workspace, "pyright", new CancellationToken(), info -> {});
            fail("off server launched");
        } catch (IllegalStateException expected) {}
        app.settings.set("native.lsp.pyright", true, false);
        app.settings.set("requireHardLimits", true, false);
        try {
            app.nativeRuntime.startServer(workspace, "pyright", new CancellationToken(), info -> {});
            fail("unsupported hard quota bypassed");
        } catch (IllegalStateException expected) {}
        assertFalse(app.nativeRuntime.busy());
    }

    @Test
    public void cancelPreparationRepeatedlyThenStartARealServerWithoutOldCleanupDeletingItsCopy()
        throws Exception {
        workspace.write("main.json", "{\"ok\":true}");
        for (int i = 0; i < 3; i++) {
            run = app.nativeRuntime.startServer(workspace, "json", new CancellationToken(), info -> {});
            app.nativeRuntime.stop(run);
            assertEquals(130, (int) run.result.get(15, TimeUnit.SECONDS));
            run = null;
        }
        start("json");
        open("main.json", "json", "{\"ok\":true}");
        assertFalse(client.symbols(uri).get(35, TimeUnit.SECONDS).getArray("result").isEmpty());
    }
}
