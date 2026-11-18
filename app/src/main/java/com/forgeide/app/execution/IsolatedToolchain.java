package com.forgeide.app.execution;

import com.forgeide.core.debug.Breakpoints;
import com.forgeide.core.sandbox.SandboxPolicy;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.util.CancellationToken;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Built-in providers are registered like future providers; the UI does not select native executables. */
public final class IsolatedToolchain implements DebuggableToolchain {

    private final String id;
    private final ExecutionClient client;

    public IsolatedToolchain(String id, ExecutionClient client) {
        this.id = id;
        this.client = client;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return id.equals("sql") ? "SQLite · isolated in-memory database" : "JavaScript · isolated Rhino ES6";
    }

    @Override
    public Set<String> languages() {
        return Set.of(id);
    }

    @Override
    public Set<SandboxPolicy.Restriction> restrictions() {
        return SandboxPolicy.safeDefault().required;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String availabilityMessage() {
        return "Bundled offline · no host filesystem or network access";
    }

    @Override
    public boolean supportsDebug() {
        return id.equals("javascript");
    }

    private void validate(RunConfiguration config, SandboxPolicy policy) {
        policy.requireSupported(restrictions());
        if (
            !config.buildCommand.isEmpty() || !config.runCommand.isEmpty() || !config.debugCommand.isEmpty()
        ) throw new IllegalArgumentException(
            "This interpreter does not execute custom native commands. Install a compatible isolated provider."
        );
    }

    @Override
    public CompletableFuture<Integer> run(
        String source,
        RunConfiguration config,
        SandboxPolicy policy,
        CancellationToken cancel,
        Consumer<String> out,
        Consumer<String> err
    ) {
        validate(config, policy);
        CompletableFuture<Integer> f = client.run(
            source,
            config,
            policy,
            List.of(),
            false,
            out,
            err,
            s -> {}
        );
        cancel.onCancel(client::cancel);
        return f;
    }

    @Override
    public CompletableFuture<Integer> debug(
        String source,
        RunConfiguration config,
        SandboxPolicy policy,
        CancellationToken cancel,
        List<Breakpoints.Point> points,
        Consumer<String> out,
        Consumer<String> err,
        Consumer<String> events
    ) {
        validate(config, policy);
        if (!supportsDebug()) throw new UnsupportedOperationException("This provider has no debugger");
        CompletableFuture<Integer> f = client.run(source, config, policy, points, true, out, err, events);
        cancel.onCancel(client::cancel);
        return f;
    }
}
