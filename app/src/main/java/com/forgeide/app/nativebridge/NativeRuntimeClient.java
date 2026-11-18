package com.forgeide.app.nativebridge;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import com.forgeide.app.BuildConfig;
import com.forgeide.app.ForgeApplication;
import com.forgeide.core.project.*;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.util.CancellationToken;
import com.forgeide.runtime.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Narrow, authenticated bridge. No credentials, live project paths or broad URI grants cross it. */
public final class NativeRuntimeClient {

    private final ForgeApplication app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private volatile Run active;

    public NativeRuntimeClient(ForgeApplication app) {
        this.app = app;
    }

    public String installationProblem() {
        if (
            !Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a") &&
            !Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64")
        ) return "Native packs support arm64-v8a and x86_64 only";
        try {
            ApplicationInfo info = app
                .getPackageManager()
                .getApplicationInfo(NativeTools.PACKAGE, PackageManager.GET_META_DATA);
            if (
                info.uid == android.os.Process.myUid() ||
                app.getPackageManager().checkSignatures(app.getPackageName(), NativeTools.PACKAGE) !=
                PackageManager.SIGNATURE_MATCH
            ) return "Runtime signature does not match this IDE build; install both APKs from the same offline bundle";
            if (
                info.metaData == null ||
                !BuildConfig.NATIVE_BUNDLE_ID.equals(info.metaData.getString("forge.native.bundle")) ||
                info.metaData.getInt("forge.native.protocol", -1) != NativeTools.PROTOCOL
            ) return "Runtime payload/security protocol does not match this IDE; install both APKs from the same bundle";
            return "";
        } catch (PackageManager.NameNotFoundException missing) {
            return "Install the included forge-native-runtime APK first";
        }
    }

    public boolean available(String tool) {
        return (
            app.settings.bool(NativeTools.key(tool)) &&
            (!tool.equals("npm") || app.settings.bool("native.node")) &&
            installationProblem().isEmpty()
        );
    }

    public boolean busy() {
        return active != null;
    }

    public String description(String tool) {
        if (
            !app.settings.bool(NativeTools.key(tool))
        ) return "Off in Settings → Toolchains & native runtimes";
        if (
            tool.equals("npm") && !app.settings.bool("native.node")
        ) return "npm also requires the Node.js switch";
        String problem = installationProblem();
        return problem.isEmpty() ? "Bundled companion · separate UID · disposable project copy" : problem;
    }

    public static final class Run {

        public final String id = UUID.randomUUID().toString();
        public final CompletableFuture<Integer> result = new CompletableFuture<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicBoolean ready = new AtomicBoolean();
        private ServiceConnection connection;
        private INativeRuntime service;
        private boolean bound;
        private Path snapshot;
        private Runnable deadline;
        private int expectedUid;
    }

    private void finish(Run run, int exit, Throwable failure) {
        if (!run.done.compareAndSet(false, true)) return;
        main.post(() -> {
            if (run.deadline != null) main.removeCallbacks(run.deadline);
            if (run.bound) {
                try {
                    app.unbindService(run.connection);
                } catch (IllegalArgumentException ignored) {}
                run.bound = false;
            }
            if (active == run) active = null;
            if (run.snapshot != null) control.execute(() -> {
                try {
                    Files.deleteIfExists(run.snapshot);
                } catch (IOException ignored) {}
            });
            if (failure == null) run.result.complete(exit);
            else run.result.completeExceptionally(failure);
            app.changed();
        });
    }

    public void stopAll() {
        Run run = active;
        if (run != null) stop(run);
    }

    public void stop(Run run) {
        run.cancelled.set(true);
        CompletableFuture.runAsync(() -> {
            // Serializes cancellation with the tiny start RPC, never with blocking PTY input.
            synchronized (run) {
                try {
                    if (run.service != null) run.service.stop(run.id);
                } catch (RemoteException | RuntimeException ignored) {} finally {
                    finish(run, 130, null);
                }
            }
        });
    }

    public void write(Run run, byte[] input) {
        if (input.length > 8192) throw new IllegalArgumentException("Native input exceeds 8 KiB");
        byte[] copy = input.clone();
        control.execute(() -> {
            if (!run.done.get() && run.service != null) try {
                run.service.write(run.id, copy);
            } catch (RemoteException | RuntimeException error) {
                finish(run, 1, error);
            }
        });
    }

    public CompletableFuture<Void> exportProject(Run run, android.net.Uri uri) {
        return CompletableFuture.runAsync(() -> {
            if (run.done.get() || run.service == null || !run.ready.get()) throw new IllegalStateException(
                "Native project copy is not active"
            );
            try (ParcelFileDescriptor destination = app.getContentResolver().openFileDescriptor(uri, "wt")) {
                if (destination == null) throw new IOException("Export provider returned no destination");
                run.service.exportProject(run.id, destination);
            } catch (IOException | RemoteException e) {
                throw new CompletionException(e);
            }
        });
    }

    public void resize(Run run, int rows, int columns) {
        control.execute(() -> {
            if (!run.done.get() && run.ready.get() && run.service != null) try {
                run.service.resize(run.id, rows, columns);
            } catch (RemoteException | RuntimeException error) {
                finish(run, 1, error);
            }
        });
    }

    public synchronized Run start(
        Workspace workspace,
        String tool,
        String language,
        RunConfiguration config,
        long timeout,
        int outputLimit,
        boolean interactive,
        CancellationToken cancel,
        Consumer<byte[]> output,
        Consumer<Bundle> ready
    ) {
        if (active != null) throw new IllegalStateException("Stop the native run or close its PTY first");
        if (!available(tool)) throw new IllegalStateException(description(tool));
        if (
            !config.buildCommand.isEmpty() || !config.runCommand.isEmpty() || !config.debugCommand.isEmpty()
        ) throw new IllegalArgumentException(
            "Native launch commands are managed by the selected provider; use the explicit Bash PTY for custom commands"
        );
        NativeTools.environment(config.environment);
        NativeTools.arguments(config.arguments);
        NativeTools.relativePath(config.file, interactive || tool.equals("npm"));
        NativeTools.relativePath(config.workingDirectory, true);
        Bundle options = new Bundle();
        options.putString("tool", tool);
        options.putString("language", language);
        options.putString("file", config.file);
        options.putString("cwd", config.workingDirectory);
        options.putString("stdin", config.stdin);
        options.putStringArray("arguments", config.arguments.toArray(new String[0]));
        options.putLong("timeout", timeout);
        options.putInt("output", outputLimit);
        options.putBoolean("interactive", interactive);
        options.putBoolean("network", app.settings.bool("native.network"));
        options.putBoolean("installScripts", app.settings.bool("native.installScripts"));
        options.putStringArray(
            "enabled",
            NativeTools.ORDER.stream()
                .filter(t -> app.settings.bool(NativeTools.key(t)))
                .toArray(String[]::new)
        );
        Bundle env = new Bundle();
        config.environment.forEach(env::putString);
        options.putBundle("environment", env);
        Parcel parcel = Parcel.obtain();
        try {
            options.writeToParcel(parcel, 0);
            if (parcel.dataSize() > 128 * 1024) throw new IllegalArgumentException(
                "Native run configuration exceeds 128 KiB"
            );
        } finally {
            parcel.recycle();
        }
        Run run = new Run();
        active = run;
        run.connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (run.done.get()) return;
                run.service = INativeRuntime.Stub.asInterface(binder);
                control.execute(() -> {
                    try {
                        if (run.done.get() || run.cancelled.get() || !available(tool)) {
                            finish(run, 130, null);
                            return;
                        }
                        Bundle info = run.service.describe();
                        int uid = app.getPackageManager().getApplicationInfo(NativeTools.PACKAGE, 0).uid;
                        if (
                            info.getInt("protocol") != NativeTools.PROTOCOL ||
                            !BuildConfig.NATIVE_BUNDLE_ID.equals(info.getString("bundle")) ||
                            info.getInt("uid") != uid ||
                            uid == android.os.Process.myUid() ||
                            !info.getString("unavailable", "").isEmpty()
                        ) throw new SecurityException(
                            "Incompatible native runtime: " +
                                info.getString("unavailable", "protocol/identity mismatch")
                        );
                        run.expectedUid = uid;
                        binder.linkToDeath(
                            () ->
                                finish(
                                    run,
                                    137,
                                    new IOException("Native runtime stopped or Android reclaimed it")
                                ),
                            0
                        );
                        try (
                            ParcelFileDescriptor snapshot = ParcelFileDescriptor.open(
                                run.snapshot.toFile(),
                                ParcelFileDescriptor.MODE_READ_ONLY
                            )
                        ) {
                            synchronized (run) {
                                if (run.done.get() || run.cancelled.get()) {
                                    finish(run, 130, null);
                                    return;
                                }
                                run.service.start(
                                    run.id,
                                    snapshot,
                                    options,
                                    new INativeCallback.Stub() {
                                        private void caller() {
                                            if (
                                                Binder.getCallingUid() != run.expectedUid
                                            ) throw new SecurityException(
                                                "Unexpected native callback identity"
                                            );
                                        }

                                        @Override
                                        public void onReady(Bundle info) {
                                            caller();
                                            if (
                                                run.done.get() || !run.ready.compareAndSet(false, true)
                                            ) return;
                                            main.post(() -> {
                                                if (run.deadline != null) main.removeCallbacks(run.deadline);
                                                run.deadline = () -> stop(run);
                                                main.postDelayed(
                                                    run.deadline,
                                                    (interactive ? 30L * 60 * 1000 : timeout) + 2500
                                                );
                                            });
                                            ready.accept(new Bundle(info));
                                        }

                                        @Override
                                        public void onOutput(byte[] bytes) {
                                            caller();
                                            if (run.done.get() || bytes == null || bytes.length == 0) return;
                                            if (
                                                bytes.length > 8192 ||
                                                run.bytes.addAndGet(bytes.length) > outputLimit
                                            ) {
                                                stop(run);
                                                return;
                                            }
                                            output.accept(bytes.clone());
                                        }

                                        @Override
                                        public void onExit(int status, String message) {
                                            caller();
                                            if (!message.isEmpty() && !run.done.get()) output.accept(
                                                ("\r\n[" +
                                                    message.substring(0, Math.min(2000, message.length())) +
                                                    "]\r\n").getBytes(StandardCharsets.UTF_8)
                                            );
                                            finish(run, status, null);
                                        }
                                    }
                                );
                            }
                        }
                    } catch (Exception error) {
                        finish(run, 1, error);
                    }
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                finish(run, 137, null);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                finish(run, 137, null);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                finish(run, 1, new IOException("Native companion refused binding"));
            }
        };
        cancel.onCancel(() -> stop(run));
        app.io.execute(() -> {
            try {
                if (run.done.get()) return;
                Path snapshot = Files.createTempFile(app.getCacheDir().toPath(), "native-project-", ".zip");
                run.snapshot = snapshot;
                try (OutputStream stream = Files.newOutputStream(snapshot)) {
                    RuntimeSnapshot.write(workspace, stream);
                }
                if (run.done.get()) {
                    Files.deleteIfExists(snapshot);
                    return;
                }
                main.post(() -> {
                    if (run.done.get() || run.cancelled.get() || !available(tool)) {
                        finish(run, 130, null);
                        return;
                    }
                    try {
                        run.bound = app.bindService(
                            new Intent().setComponent(
                                new ComponentName(NativeTools.PACKAGE, NativeTools.SERVICE)
                            ),
                            run.connection,
                            Context.BIND_AUTO_CREATE
                        );
                        if (!run.bound) finish(run, 1, new IOException("Could not bind native companion"));
                    } catch (RuntimeException error) {
                        finish(run, 1, error);
                    }
                });
            } catch (Exception error) {
                finish(run, 1, error);
            }
        });
        run.deadline = () -> stop(run);
        main.postDelayed(run.deadline, 120000);
        return run;
    }
}
