package com.forgeide.core.toolchain;

import java.util.*;

public final class ToolchainRegistry {

    private final Map<String, Toolchain> providers = new LinkedHashMap<>();

    public synchronized void register(Toolchain provider) {
        providers.put(provider.id(), provider);
    }

    public synchronized List<Toolchain> all() {
        return new ArrayList<>(providers.values());
    }

    public synchronized Toolchain forLanguage(String language) {
        for (Toolchain t : providers.values())
            if (t.available() && t.languages().contains(language)) return t;
        return null;
    }

    public synchronized Toolchain require(String id) {
        Toolchain p = providers.get(id);
        if (p == null || !p.available()) throw new IllegalStateException(
            p == null ? "No isolated toolchain installed for " + id : p.availabilityMessage()
        );
        return p;
    }

    /** A configured provider must really support the selected file; never silently substitute one. */
    public synchronized Toolchain requireForLanguage(String id, String language) {
        Toolchain provider = require(id);
        if (!provider.languages().contains(language)) throw new IllegalArgumentException(
            "Toolchain " + id + " does not support " + language
        );
        return provider;
    }
}
