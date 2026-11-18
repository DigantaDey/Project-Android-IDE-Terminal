package com.forgeide.app;

import android.app.Application;
import android.os.*;
import com.forgeide.app.execution.ExecutionClient;
import com.forgeide.core.debug.*;
import com.forgeide.core.editor.LineEndings;
import com.forgeide.core.project.*;
import com.forgeide.core.recovery.*;
import com.forgeide.core.settings.*;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.util.*;
import io.github.rosemoe.sora.text.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-lifetime workspace state; Activity recreation never destroys open documents or undo stacks. */
public final class ForgeApplication extends Application {

    public final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "forge-storage"));
    public final Handler main = new Handler(Looper.getMainLooper());
    public final Settings settings = new Settings();
    public final Breakpoints breakpoints = new Breakpoints();
    public final BuildLog buildHistory = new BuildLog();
    public final ToolchainRegistry toolchains = new ToolchainRegistry();
    public volatile CompletableFuture<Integer> activeRun;
    public volatile CancellationToken runCancel;

    public boolean isRunning() {
        return activeRun != null && !activeRun.isDone();
    }

    public final List<OpenDocument> documents = new CopyOnWriteArrayList<>();
    public final List<Runnable> observers = new CopyOnWriteArrayList<>();
    public final List<String> logs = new CopyOnWriteArrayList<>();
    public final BoundedLog output = new BoundedLog(262144);
    public final Map<String, List<com.forgeide.core.terminal.TerminalSession>> terminalSessions =
        new LinkedHashMap<>();
    private final AtomicBoolean changeScheduled = new AtomicBoolean();
    public Workspace workspace;
    public RecoveryStore recovery;
    public ExecutionClient execution;
    public String project = "",
        activePath = "",
        debugState = "",
        fatalError = "";
    public Path projects, stateDir;
    public boolean initialized;

    @Override
    public void onCreate() {
        super.onCreate();
        // Android also instantiates this Application in the isolated runtime: never open host state there.
        if (android.os.Process.myUid() != getApplicationInfo().uid) return;
        execution = new ExecutionClient(this);
        toolchains.register(new com.forgeide.app.execution.IsolatedToolchain("javascript", execution));
        toolchains.register(new com.forgeide.app.execution.IsolatedToolchain("sql", execution));
        try {
            projects = getFilesDir().toPath().resolve("projects");
            stateDir = getFilesDir().toPath().resolve("state");
            Files.createDirectories(projects);
            Files.createDirectories(stateDir);
            recovery = new RecoveryStore(getFilesDir().toPath().resolve("recovery"));
            try {
                settings.load(stateDir.resolve("settings.json"), false);
            } catch (IOException e) {
                log(e.getMessage());
            }
            String last = "Welcome";
            if (Files.exists(stateDir.resolve("session.json"))) try {
                last = JsonObject.parse(
                    com.forgeide.core.util.AtomicFiles.readUtf8(stateDir.resolve("session.json"))
                ).getString("project", last);
            } catch (Exception e) {
                log("Session restore: " + e.getMessage());
            }
            if (!Files.exists(projects.resolve("Welcome"))) createProject("Welcome", "JavaScript");
            if (
                !com.forgeide.core.util.TextUtils.isValidFileName(last) ||
                !Files.isDirectory(projects.resolve(last))
            ) last = "Welcome";
            loadProject(last);
            initialized = true;
        } catch (Exception e) {
            fatalError = "Could not initialize private project storage: " + e.getMessage();
        }
    }

    public void changed() {
        if (changeScheduled.compareAndSet(false, true)) main.postDelayed(
            () -> {
                changeScheduled.set(false);
                for (Runnable r : observers) r.run();
            },
            16
        );
    }

    public void log(String message) {
        logs.add(message);
        while (logs.size() > 200) logs.remove(0);
        changed();
    }

    public List<String> projects() throws IOException {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(projects)) {
            for (Path p : dirs)
                if (Files.isDirectory(p) && !Files.isSymbolicLink(p)) result.add(p.getFileName().toString());
        }
        Collections.sort(result);
        return result;
    }

    public void createProject(String name, String template) throws IOException {
        if (!com.forgeide.core.util.TextUtils.isValidFileName(name)) throw new IOException(
            "Choose a simple project name without path separators"
        );
        Path root = projects.resolve(name);
        if (Files.exists(root)) throw new IOException("A project with this name already exists");
        Workspace w = new Workspace(root);
        if (template.equals("JavaScript")) {
            w.write(
                "main.js",
                "// Welcome to Forge. Run locally in an isolated JavaScript sandbox.\n// console, print, readLine, arguments and env are available. No Node.js APIs.\n\nfunction fibonacci(n) {\n    if (n < 2) return n;\n    return fibonacci(n - 1) + fibonacci(n - 2);\n}\n\nconst name = arguments[0] || 'developer';\nconsole.log('Hello, ' + name + '!');\n\nfor (let i = 0; i < 10; i++) {\n    const result = fibonacci(i);\n    console.log('fib(' + i + ') = ' + result);\n}\n"
            );
            w.write(
                ".forge/run.json",
                new RunConfiguration(
                    "Current script",
                    "javascript",
                    "main.js",
                    "",
                    List.of("developer"),
                    Map.of(),
                    "",
                    List.of(),
                    List.of(),
                    List.of()
                ).toJson()
            );
        } else if (template.equals("SQL")) {
            w.write(
                "main.sql",
                "-- A fresh in-memory SQLite database is created for every run.\nCREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT);\nINSERT INTO notes(title) VALUES ('Built with Forge');\nSELECT * FROM notes;\n"
            );
        } else if (template.equals("Web")) {
            w.write(
                "index.html",
                "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n  <title>Made with Forge</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>Make something great.</h1>\n  <p>A local, offline web project.</p>\n  <p>Static preview: scripts and external requests are disabled for safety.</p>\n</body>\n</html>\n"
            );
            w.write(
                "style.css",
                "body { font: 18px system-ui; background: #17191f; color: #edd9ca; padding: 10vw; }\nbutton { padding: 12px 20px; background: #eea470; border: 0; border-radius: 8px; }\n"
            );
        } else if (template.equals("Python")) w.write(
            "main.py",
            "def greet(name):\n    return f'Hello, {name}!'\n\nprint(greet('developer'))\n"
        );
        else if (template.equals("C++")) w.write(
            "main.cpp",
            "#include <iostream>\n\nint main() {\n    std::cout << \"Hello from Forge!\\n\";\n    return 0;\n}\n"
        );
        else if (template.equals("Java")) w.write(
            "Main.java",
            "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello from Forge!\");\n    }\n}\n"
        );
        w.write(
            "README.md",
            "# " +
                name +
                "\n\nA project created in Forge IDE.\n\nThe editor and project tools work offline. JavaScript runs in an Android isolated process (Rhino ES6, not Node.js). Other native compilers, package managers and LSP/DAP servers require compatible isolated providers; Forge never executes them with app privileges.\n"
        );
    }

    public synchronized void loadProject(String name) throws IOException {
        if (
            !com.forgeide.core.util.TextUtils.isValidFileName(name) ||
            !Files.isDirectory(projects.resolve(name))
        ) throw new IOException("Project does not exist");
        workspace = new Workspace(projects.resolve(name));
        project = name;
        for (OpenDocument doc : documents) doc.detach();
        documents.clear();
        activePath = "";
        try {
            settings.load(workspace.resolve(".forge/settings.json"), true);
        } catch (IOException e) {
            log(e.getMessage());
        }
        Path bp = workspace.resolve(".forge/breakpoints.json");
        try {
            breakpoints.restore(Files.exists(bp) ? com.forgeide.core.util.AtomicFiles.readUtf8(bp) : "[]");
        } catch (Exception e) {
            log("Breakpoints not restored: " + e.getMessage());
        }
        Path session = stateDir.resolve("session.json");
        if (Files.exists(session)) try {
            JsonObject j = JsonObject.parse(com.forgeide.core.util.AtomicFiles.readUtf8(session));
            if (j.getString("project", "").equals(name)) {
                for (String p : j.getStringList("files"))
                    if (documents.size() < 20 && Files.isRegularFile(workspace.resolve(p))) open(p);
                activePath = j.getString("active", activePath);
            }
        } catch (Exception e) {
            log("Some previous tabs could not be restored: " + e.getMessage());
        }
        try {
            for (RecoveryStore.Entry e : recovery.recover())
                if (e.project.equals(name)) {
                    OpenDocument doc = find(e.path);
                    if (doc == null) {
                        doc = new OpenDocument(workspace, name, e.path, e.text, e.diskDigest);
                        documents.add(doc);
                    } else doc.content.replace(0, doc.content.length(), e.text);
                    doc.diskDigest = e.diskDigest;
                    doc.savedRevision = -1;
                    log("Recovered unsaved changes: " + e.path);
                }
        } catch (IOException e) {
            log(e.getMessage());
        }
        if (documents.isEmpty()) {
            for (String p : List.of(
                "main.js",
                "main.sql",
                "index.html",
                "main.py",
                "main.cpp",
                "Main.java",
                "README.md"
            ))
                if (Files.isRegularFile(workspace.resolve(p))) {
                    open(p);
                    break;
                }
        }
        if (find(activePath) == null) activePath = documents.isEmpty() ? "" : documents.get(0).path;
    }

    public OpenDocument find(String path) {
        for (OpenDocument d : documents) if (d.path.equals(path)) return d;
        return null;
    }

    public OpenDocument open(String path) throws IOException {
        path = workspace.relative(workspace.resolve(path));
        OpenDocument d = find(path);
        if (d != null) return d;
        if (documents.size() >= 20) throw new IOException(
            "Close a tab before opening more than 20 documents"
        );
        String text = workspace.read(path);
        String digest = AtomicFiles.sha256(Files.readAllBytes(workspace.resolve(path)));
        d = new OpenDocument(workspace, project, path, text, digest);
        documents.add(d);
        activePath = path;
        return d;
    }

    public void persistSession() throws IOException {
        List<String> files = new ArrayList<>();
        for (OpenDocument d : documents) files.add(d.path);
        AtomicFiles.write(
            stateDir.resolve("session.json"),
            Json.write(Map.of("project", project, "files", files, "active", activePath))
        );
        workspace.write(".forge/breakpoints.json", breakpoints.serialize());
    }

    public void journal(OpenDocument d) throws IOException {
        if (d.dirty()) recovery.save(d.project, d.path, d.content.toString(), d.diskDigest);
    }

    public void save(OpenDocument d) throws IOException {
        long revision = d.revision.get();
        String text = d.content.toString();
        recovery.save(d.project, d.path, text, d.diskDigest);
        String serialized = LineEndings.normalize(text, d.lineEndings);
        d.owner.writeChecked(d.path, serialized, d.diskDigest);
        d.diskDigest = AtomicFiles.sha256(serialized.getBytes(StandardCharsets.UTF_8));
        // Dirty state is derived, not a racing boolean. An edit arriving during this save
        // can never be marked clean by an older I/O completion.
        d.savedRevision = revision;
        if (d.dirty()) journal(d);
        else recovery.discard(d.project, d.path);
        changed();
    }

    public CompletableFuture<Void> saveAll() {
        return CompletableFuture.runAsync(
            () -> {
                try {
                    for (OpenDocument d : documents) if (d.dirty()) save(d);
                    persistSession();
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            },
            io
        );
    }

    public final class OpenDocument {

        public final Workspace owner;
        public final String project;
        public String path;
        public final Content content;
        public final LineEndings lineEndings;
        public final AtomicLong revision = new AtomicLong();
        public volatile String diskDigest;
        private volatile long savedRevision;

        public boolean dirty() {
            return revision.get() != savedRevision;
        }

        public void discardChanges() {
            main.removeCallbacks(journalTask);
            savedRevision = revision.get();
        }

        public void detach() {
            main.removeCallbacks(journalTask);
        }

        public int line, column;
        private final Runnable journalTask = () ->
            io.execute(() -> {
                try {
                    journal(this);
                } catch (IOException e) {
                    log("RECOVERY WARNING: " + e.getMessage());
                }
            });

        OpenDocument(Workspace owner, String project, String path, String text, String digest) {
            this.owner = owner;
            this.project = project;
            this.path = path;
            this.diskDigest = digest;
            lineEndings = LineEndings.detect(text);
            content = new Content(text);
            content.addContentListener(
                new ContentListener() {
                    public void beforeReplace(Content c) {}

                    public void afterInsert(Content c, int a, int b, int x, int y, CharSequence t) {
                        edited();
                    }

                    public void afterDelete(Content c, int a, int b, int x, int y, CharSequence t) {
                        edited();
                    }
                }
            );
        }

        private void edited() {
            revision.incrementAndGet();
            main.removeCallbacks(journalTask);
            main.postDelayed(journalTask, 600);
            changed();
        }
    }
}
