package com.forgeide.core.toolchain;

import java.util.*;

public final class ToolchainRegistry {
    private final Map<String, Toolchain> providers = new LinkedHashMap<>();
    public synchronized void register(Toolchain provider) { providers.put(provider.id(), provider); }
    public synchronized List<Toolchain> all() { return new ArrayList<>(providers.values()); }
    public synchronized Toolchain require(String id) { Toolchain p = providers.get(id); if (p == null || !p.available()) throw new IllegalStateException(p == null ? "No isolated toolchain installed for " + id : p.availabilityMessage()); return p; }
}
