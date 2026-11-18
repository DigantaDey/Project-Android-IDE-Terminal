package com.forgeide.runtime;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import com.forgeide.core.project.RuntimeSnapshot;
import com.forgeide.core.toolchain.NativeTools;
import com.forgeide.runtime.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A permissionless-filesystem companion UID, not the IDE UID and not an isolatedProcess UID. */
public final class NativeRuntimeService extends Service {

    private static final String HOST = "com.forgeide.app";
    private final ExecutorService prepare = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session active;
    private NativePayload payload;
    private String unavailable = "";

    private final class Session {

        final String id;
        final INativeCallback callback;
        final AtomicBoolean done = new AtomicBoolean();
        volatile boolean cancelled;
        volatile int pid;
        volatile ParcelFileDescriptor master;
        volatile OutputStream input;
        volatile Path root;
        volatile long output;
        final int cap;
        Runnable deadline;

        Session(String id, INativeCallback callback, int cap) {
            this.id = id;
            this.callback = callback;
            this.cap = cap;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            payload = new NativePayload(this);
        } catch (Exception e) {
            unavailable = e.toString();
        }
    }

    private void caller() {
        try {
            int hostUid = getPackageManager().getApplicationInfo(HOST, 0).uid;
            if (
                Binder.getCallingUid() != hostUid ||
                hostUid == android.os.Process.myUid() ||
                getPackageManager().checkSignatures(HOST, getPackageName()) != PackageManager.SIGNATURE_MATCH
            ) throw new SecurityException("Only the same-signed Forge IDE may use native tools");
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("Forge IDE is not installed", e);
        }
    }

    private Session session(String id) {
        Session current = active;
        if (current == null || !current.id.equals(id) || current.done.get()) throw new IllegalStateException(
            "No active native session"
        );
        return current;
    }

    private final INativeRuntime.Stub binder = new INativeRuntime.Stub() {
        @Override
        public Bundle describe() {
            caller();
            Bundle b = new Bundle();
            b.putInt("protocol", NativeTools.PROTOCOL);
            b.putInt("uid", android.os.Process.myUid());
            b.putString("bundle", payload == null ? "" : payload.id());
            b.putString("abi", payload == null ? "unsupported" : payload.architecture);
            b.putString(
                "unavailable",
                payload == null ? unavailable : payload.supported() ? "" : "No compatible 64-bit ABI"
            );
            return b;
        }

        @Override
        public synchronized void start(
            String id,
            ParcelFileDescriptor snapshot,
            Bundle options,
            INativeCallback callback
        ) {
            caller();
            if (active != null) throw new IllegalStateException(
                "Only one native run or PTY session may be active"
            );
            if (
                id == null || !id.matches("[a-f0-9-]{36}") || snapshot == null || callback == null
            ) throw new IllegalArgumentException("Invalid native session");
            if (payload == null) throw new IllegalStateException(unavailable);
            Parcel parcel = Parcel.obtain();
            try {
                options.writeToParcel(parcel, 0);
                if (parcel.dataSize() > 128 * 1024) throw new IllegalArgumentException(
                    "Native launch data exceeds 128 KiB"
                );
            } finally {
                parcel.recycle();
            }
            String tool = options.getString("tool", "");
            NativeTools.key(tool);
            List<String> enabled = Arrays.asList(
                options.getStringArray("enabled") == null ? new String[0] : options.getStringArray("enabled")
            );
            if (
                !enabled.contains(tool) || (tool.equals("npm") && !enabled.contains("node"))
            ) throw new SecurityException("Native tool is switched off");
            Session s = new Session(
                id,
                callback,
                Math.max(1024, Math.min(1024 * 1024, options.getInt("output", 262144)))
            );
            active = s;
            try {
                callback.asBinder().linkToDeath(() -> stopSession(s, 130, "IDE disconnected"), 0);
            } catch (RemoteException dead) {
                active = null;
                throw new IllegalStateException("IDE callback has died", dead);
            }
            prepare.execute(() -> launch(s, snapshot, new Bundle(options)));
        }

        @Override
        public void write(String id, byte[] input) {
            caller();
            Session s = session(id);
            if (input == null || input.length > 8192) throw new IllegalArgumentException(
                "PTY input exceeds 8 KiB"
            );
            try {
                OutputStream stream = s.input;
                if (stream != null) synchronized (stream) {
                    stream.write(input);
                    stream.flush();
                }
            } catch (IOException e) {
                stopSession(s, 1, "PTY input closed");
            }
        }

        @Override
        public void resize(String id, int rows, int columns) {
            caller();
            Session s = session(id);
            if (s.master != null) NativePty.resize(
                s.master.getFd(),
                Math.max(2, Math.min(300, rows)),
                Math.max(2, Math.min(500, columns))
            );
        }

        @Override
        public void exportProject(String id, ParcelFileDescriptor destination) {
            caller();
            Session s = session(id);
            if (s.root == null || s.pid == 0) throw new IllegalStateException(
                "Native project copy is not ready"
            );
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
                RuntimeSnapshot.write(
                    new com.forgeide.core.project.Workspace(s.root.resolve("project")),
                    output
                );
            } catch (IOException e) {
                throw new IllegalStateException("Could not export native project copy: " + e.getMessage(), e);
            }
        }

        @Override
        public void stop(String id) {
            caller();
            stopSession(session(id), 130, "Stopped");
        }

        @Override
        public void stopAll() {
            caller();
            Session s = active;
            if (s != null) stopSession(s, 130, "Native tools switched off or stopped");
        }
    };

    private void check(Session s) throws IOException {
        if (s.cancelled || active != s || s.done.get()) throw new IOException("Native session cancelled");
    }

    private void launch(Session s, ParcelFileDescriptor snapshot, Bundle options) {
        try (InputStream source = new ParcelFileDescriptor.AutoCloseInputStream(snapshot)) {
            check(s);
            payload.ensure();
            check(s);
            Path sessions = getCacheDir().toPath().resolve("sessions");
            Files.createDirectories(sessions);
            s.root = Files.createDirectory(sessions.resolve(s.id));
            Path project = Files.createDirectory(s.root.resolve("project"));
            RuntimeSnapshot.read(project, source);
            check(s);
            boolean interactive = options.getBoolean("interactive");
            String file = options.getString("file", ""),
                directory = options.getString("cwd", "");
            NativeTools.relativePath(file, interactive || options.getString("tool", "").equals("npm"));
            NativeTools.relativePath(directory, true);
            Path cwd = directory.isEmpty() ? project : NativePayload.inside(project, directory);
            if (!Files.isDirectory(cwd, LinkOption.NOFOLLOW_LINKS)) throw new IOException(
                "Native working directory does not exist"
            );
            if (
                !interactive &&
                !options.getString("tool", "").equals("npm") &&
                !Files.isRegularFile(NativePayload.inside(project, file), LinkOption.NOFOLLOW_LINKS)
            ) throw new IOException("Native entry file is missing from the project copy");
            String[] args = options.getStringArray("arguments");
            List<String> command = NativeTools.command(
                options.getString("tool", ""),
                options.getString("language", ""),
                payload.prefix.toString(),
                project.toString(),
                s.root.resolve("program").toString(),
                file,
                args == null ? List.of() : Arrays.asList(args),
                interactive
            );
            Map<String, String> requested = new LinkedHashMap<>();
            Bundle environment = options.getBundle("environment");
            if (environment != null) for (String key : environment.keySet())
                requested.put(key, environment.getString(key, ""));
            Map<String, String> env = NativeTools.environment(requested);
            String prefix = payload.prefix.toString();
            Path home = Files.createDirectory(s.root.resolve("home"));
            Path commands = Files.createDirectory(s.root.resolve("bin"));
            Set<String> enabled = new HashSet<>(Arrays.asList(options.getStringArray("enabled")));
            try (DirectoryStream<Path> binaries = Files.newDirectoryStream(payload.prefix.resolve("bin"))) {
                for (Path binary : binaries) {
                    String name = binary.getFileName().toString();
                    String owner = NativeTools.commandTool(name);
                    if (
                        !owner.isEmpty() &&
                        (!enabled.contains(owner) || (owner.equals("npm") && !enabled.contains("node")))
                    ) continue;
                    android.system.Os.symlink(binary.toString(), commands.resolve(name).toString());
                }
            }
            env.put("PATH", commands.toString());
            env.put("HOME", home.toString());
            env.put("TMPDIR", prefix + "/tmp");
            env.put("PREFIX", prefix);
            env.put("SHELL", prefix + "/bin/bash");
            env.put("LD_LIBRARY_PATH", prefix + "/lib");
            env.put("ANDROID_ROOT", "/system");
            env.put("ANDROID_DATA", "/data");
            env.put("LANG", "C.UTF-8");
            env.put("TERM", "xterm-256color");
            env.put("COLORTERM", "truecolor");
            env.put("PS1", "forge \\w $ ");
            env.put("PYTHONHOME", prefix);
            env.put("PYTHONNOUSERSITE", "1");
            env.put("PYTHONDONTWRITEBYTECODE", "1");
            env.put("npm_config_cache", home.resolve(".npm").toString());
            env.put("npm_config_script_shell", prefix + "/bin/bash");
            env.put("npm_config_audit", "false");
            env.put("npm_config_fund", "false");
            env.put("npm_config_ignore_scripts", Boolean.toString(!options.getBoolean("installScripts")));
            if (!options.getBoolean("network")) env.put("npm_config_offline", "true");
            List<String> envp = new ArrayList<>();
            env.forEach((k, v) -> envp.add(k + "=" + v));
            synchronized (this) {
                check(s);
                long[] process = NativePty.start(
                    cwd.toString(),
                    command.toArray(new String[0]),
                    envp.toArray(new String[0]),
                    options.getInt("rows", 24),
                    options.getInt("columns", 80),
                    options.getBoolean("network")
                );
                s.pid = (int) process[0];
                s.master = ParcelFileDescriptor.adoptFd((int) process[1]);
                s.input = new ParcelFileDescriptor.AutoCloseOutputStream(
                    ParcelFileDescriptor.dup(s.master.getFileDescriptor())
                );
            }
            Bundle ready = new Bundle();
            ready.putInt("uid", android.os.Process.myUid());
            ready.putInt("pid", s.pid);
            ready.putString("project", project.toString());
            ready.putBoolean("network", options.getBoolean("network"));
            s.callback.onReady(ready);
            long timeout = interactive
                ? 30L * 60 * 1000
                : Math.max(100, Math.min(60000, options.getLong("timeout", 10000)));
            s.deadline = () -> stopSession(s, 124, "Native session deadline reached");
            main.postDelayed(s.deadline, timeout);
            Thread output = new Thread(() -> readOutput(s), "native-pty-output");
            output.start();
            String initial = options.getString("stdin", "");
            if (!interactive) {
                byte[] bytes = (initial + "\u0004\u0004").getBytes(StandardCharsets.UTF_8);
                synchronized (s.input) {
                    s.input.write(bytes);
                    s.input.flush();
                }
            }
            int status = NativePty.waitFor(s.pid);
            // Drain PTY output before reporting the exit. EIO is normal when its slave closes.
            output.join(1000);
            stopSession(s, status, "");
        } catch (Exception error) {
            stopSession(s, s.cancelled ? 130 : 1, String.valueOf(error.getMessage()));
        } finally {
            if (s.root != null) try {
                NativePayload.delete(s.root);
            } catch (IOException ignored) {}
        }
    }

    private void readOutput(Session s) {
        try (
            InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.dup(s.master.getFileDescriptor())
            )
        ) {
            byte[] buffer = new byte[8192];
            int n;
            while (!s.done.get() && (n = input.read(buffer)) != -1) {
                s.output += n;
                if (s.output > s.cap) {
                    stopSession(s, 1, "Native output limit reached");
                    return;
                }
                s.callback.onOutput(Arrays.copyOf(buffer, n));
            }
        } catch (IOException ignored) {} catch (RemoteException dead) {
            stopSession(s, 130, "IDE disconnected");
        }
    }

    private synchronized void stopSession(Session s, int status, String message) {
        s.cancelled = true;
        if (!s.done.compareAndSet(false, true)) return;
        if (s.deadline != null) main.removeCallbacks(s.deadline);
        // A late callback must never kill a newer session.
        if (active == s) {
            if (s.pid != 0) NativePty.killAll();
            active = null;
        }
        try {
            if (s.input != null) s.input.close();
        } catch (IOException ignored) {}
        try {
            if (s.master != null) s.master.close();
        } catch (IOException ignored) {}
        try {
            s.callback.onExit(status, message.length() > 2000 ? message.substring(0, 2000) : message);
        } catch (RemoteException ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Session s = active;
        if (s != null) stopSession(s, 130, "IDE disconnected");
        return false;
    }

    @Override
    public void onDestroy() {
        Session s = active;
        if (s != null) stopSession(s, 130, "Runtime service stopped");
        main.removeCallbacksAndMessages(null);
        prepare.shutdownNow();
        super.onDestroy();
    }
}
