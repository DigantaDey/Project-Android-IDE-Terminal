package com.forgeide.core.language;

import java.net.*;
import java.util.*;

/** Pure URI mapping, with no filesystem access to either UID. Only the disposable project is mapped. */
public final class LspProjectUris {

    private static final Set<String> PRIVATE = Set.of(
        ".git",
        ".forge",
        ".forge-trash",
        ".forge-replace-backups",
        "replace-backups",
        ".gradle",
        ".idea"
    );
    private final List<String> roots = new ArrayList<>();

    public LspProjectUris(String root) {
        if (
            root == null ||
            root.length() < 2 ||
            !root.startsWith("/") ||
            root.indexOf('\\') >= 0 ||
            root.indexOf('\0') >= 0
        ) throw new IllegalArgumentException("Invalid LSP copy root");
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        for (String part : root.substring(1).split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException(
                "Invalid LSP copy root"
            );
        roots.add(root);
        // Android's primary-user /data/data alias can be canonicalized by native servers.
        if (root.startsWith("/data/user/0/com.forgex/")) roots.add(
            root.replaceFirst("/data/user/0/", "/data/data/")
        );
        if (root.startsWith("/data/data/com.forgex/")) roots.add(
            root.replaceFirst("/data/data/", "/data/user/0/")
        );
    }

    public static boolean allowed(String relative) {
        if (
            relative == null ||
            relative.isEmpty() ||
            relative.length() > 1024 ||
            relative.startsWith("/") ||
            relative.indexOf('\\') >= 0 ||
            relative.indexOf(':') >= 0 ||
            relative.indexOf('\0') >= 0
        ) return false;
        String[] parts = relative.split("/", -1);
        if (parts.length > 32) return false;
        for (String part : parts)
            if (
                part.isEmpty() || part.equals(".") || part.equals("..") || PRIVATE.contains(part)
            ) return false;
        return true;
    }

    private static String fileUri(String path) {
        try {
            return new URI("file", "", path, null, null).toASCIIString();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid project URI", invalid);
        }
    }

    public String rootUri() {
        return fileUri(roots.get(0) + "/");
    }

    public String uri(String relative) {
        if (!allowed(relative)) throw new IllegalArgumentException(
            "File cannot be shared with language servers"
        );
        return fileUri(roots.get(0) + "/" + relative);
    }

    public String relative(String value) {
        try {
            URI uri = new URI(value);
            if (
                !"file".equalsIgnoreCase(uri.getScheme()) ||
                uri.getRawAuthority() != null ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null
            ) return null;
            String path = uri.getPath();
            if (path == null) return null;
            for (String root : roots)
                if (path.startsWith(root + "/")) {
                    String relative = path.substring(root.length() + 1);
                    return allowed(relative) ? relative : null;
                }
        } catch (URISyntaxException | IllegalArgumentException ignored) {}
        return null;
    }
}
