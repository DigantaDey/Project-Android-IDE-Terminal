package com.forgeide.core.toolchain;

import com.forgeide.core.sandbox.SandboxPolicy;
import com.forgeide.core.util.CancellationToken;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Android adapters must execute untrusted programs only in a negotiated sandbox. */
public interface Toolchain {
    String id();
    String displayName();
    Set<String> languages();
    Set<SandboxPolicy.Restriction> restrictions();
    boolean available();
    String availabilityMessage();
    CompletableFuture<Integer> run(String source, RunConfiguration configuration, SandboxPolicy policy, CancellationToken cancel, Consumer<String> stdout, Consumer<String> stderr);
}
