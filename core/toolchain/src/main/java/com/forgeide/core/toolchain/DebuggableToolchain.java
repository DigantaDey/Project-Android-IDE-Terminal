package com.forgeide.core.toolchain;

import com.forgeide.core.debug.Breakpoints;
import com.forgeide.core.sandbox.SandboxPolicy;
import com.forgeide.core.util.CancellationToken;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Optional capability, independent of the editor and of any particular debugger protocol. */
public interface DebuggableToolchain extends Toolchain {
    boolean supportsDebug();
    CompletableFuture<Integer> debug(
        String source,
        RunConfiguration config,
        SandboxPolicy policy,
        CancellationToken cancel,
        List<Breakpoints.Point> breakpoints,
        Consumer<String> stdout,
        Consumer<String> stderr,
        Consumer<String> events
    );
}
