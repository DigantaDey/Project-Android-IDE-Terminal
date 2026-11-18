package com.forgeide.app.nativebridge;

import com.forgeide.app.ForgeApplication;
import com.forgeide.core.language.LanguageRegistry;
import com.forgeide.core.sandbox.SandboxPolicy;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.util.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real native provider: execution always crosses into the companion's separate Android UID. */
public final class NativeToolchain implements Toolchain {

    private final String tool;
    private final ForgeApplication app;

    public NativeToolchain(String tool, ForgeApplication app) {
        NativeTools.key(tool);
        this.tool = tool;
        this.app = app;
    }

    @Override
    public String id() {
        return tool;
    }

    @Override
    public String displayName() {
        return NativeTools.label(tool);
    }

    @Override
    public Set<String> languages() {
        return NativeTools.languages(tool);
    }

    @Override
    public Set<SandboxPolicy.Restriction> restrictions() {
        Set<SandboxPolicy.Restriction> supported = EnumSet.of(
            SandboxPolicy.Restriction.SEPARATE_APP_UID,
            SandboxPolicy.Restriction.NO_HOST_FILES,
            SandboxPolicy.Restriction.DEADLINE,
            SandboxPolicy.Restriction.OUTPUT_LIMIT
        );
        if (!app.settings.bool("native.network")) supported.add(SandboxPolicy.Restriction.NO_NETWORK);
        return supported;
    }

    @Override
    public boolean available() {
        return app.nativeRuntime.available(tool);
    }

    @Override
    public String availabilityMessage() {
        return app.nativeRuntime.description(tool);
    }

    @Override
    public CompletableFuture<Integer> run(
        String source,
        RunConfiguration config,
        SandboxPolicy policy,
        CancellationToken cancel,
        Consumer<String> stdout,
        Consumer<String> stderr
    ) {
        policy.requireSupported(restrictions());
        Utf8Chunks decoder = new Utf8Chunks();
        java.util.concurrent.atomic.AtomicReference<String> project =
            new java.util.concurrent.atomic.AtomicReference<>("");
        NativeRuntimeClient.Run run = app.nativeRuntime.start(
            app.workspace,
            tool,
            LanguageRegistry.builtins().forFile(config.file).id(),
            config,
            policy.timeoutMillis,
            policy.outputBytes,
            false,
            cancel,
            bytes -> {
                String output = decoder.append(bytes);
                String root = project.get();
                stdout.accept(root.isEmpty() ? output : output.replace(root + "/", ""));
            },
            info -> project.set(info.getString("project", ""))
        );
        return run.result.whenComplete((exit, failure) -> {
            String tail = decoder.finish();
            if (!tail.isEmpty()) stdout.accept(tail);
        });
    }
}
