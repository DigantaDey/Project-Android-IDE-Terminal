package com.forgeide.core.sandbox;

import java.util.*;

/** Fail-closed capability negotiation: never silently weaken requested isolation. */
public final class SandboxPolicy {

    public enum Restriction {
        ISOLATED_UID,
        SEPARATE_APP_UID,
        NO_HOST_FILES,
        NO_NETWORK,
        NO_PROCESS_CREATION,
        DEADLINE,
        OUTPUT_LIMIT,
        HARD_MEMORY_LIMIT,
        CPU_QUOTA,
    }

    public final long timeoutMillis;
    public final int outputBytes;
    public final Set<Restriction> required;

    public SandboxPolicy(long timeoutMillis, int outputBytes, Set<Restriction> required) {
        if (
            timeoutMillis < 100 || timeoutMillis > 60000 || outputBytes < 1024 || outputBytes > 1024 * 1024
        ) throw new IllegalArgumentException("Unsafe sandbox limits");
        this.timeoutMillis = timeoutMillis;
        this.outputBytes = outputBytes;
        this.required = Collections.unmodifiableSet(
            required.isEmpty() ? EnumSet.noneOf(Restriction.class) : EnumSet.copyOf(required)
        );
    }

    public static SandboxPolicy safeDefault() {
        return new SandboxPolicy(
            10000,
            262144,
            EnumSet.of(
                Restriction.ISOLATED_UID,
                Restriction.NO_HOST_FILES,
                Restriction.NO_NETWORK,
                Restriction.NO_PROCESS_CREATION,
                Restriction.DEADLINE,
                Restriction.OUTPUT_LIMIT
            )
        );
    }

    public void requireSupported(Set<Restriction> supported) {
        Set<Restriction> missing = required.isEmpty()
            ? EnumSet.noneOf(Restriction.class)
            : EnumSet.copyOf(required);
        missing.removeAll(supported);
        if (!missing.isEmpty()) throw new SecurityException("Runtime cannot enforce: " + missing);
    }
}
