package com.forgeide.core.toolchain;

import java.util.*;

/** Shared, versioned companion contract. These switches are launch consent, not DRM. */
public final class NativeTools {

    private NativeTools() {}

    public static final String PACKAGE = "com.forgex";
    public static final String SERVICE = "com.forgeide.runtime.NativeRuntimeService";
    public static final int PROTOCOL = 1;
    public static final Set<String> IDS = Set.of("clang", "python", "node", "npm", "bash");
    public static final List<String> ORDER = List.of("clang", "python", "node", "npm", "bash");

    public static String key(String id) {
        if (!IDS.contains(id)) throw new IllegalArgumentException("Unknown native tool: " + id);
        return "native." + id;
    }

    public static String label(String id) {
        switch (id) {
            case "clang":
                return "C / C++ · Clang + LLD";
            case "python":
                return "Python · CPython";
            case "node":
                return "JavaScript · Node.js";
            case "npm":
                return "npm · Node package manager";
            case "bash":
                return "Bash · native PTY shell";
            default:
                throw new IllegalArgumentException("Unknown native tool");
        }
    }

    public static Set<String> languages(String id) {
        switch (id) {
            case "clang":
                return Set.of("c", "cpp");
            case "python":
                return Set.of("python");
            case "node":
                return Set.of("javascript");
            case "bash":
                return Set.of("shellscript");
            case "npm":
                return Set.of();
            default:
                throw new IllegalArgumentException("Unknown native tool");
        }
    }

    /** Controls ordinary PTY command discovery, not an absolute-path or programmable-code ban. */
    public static String commandTool(String name) {
        if (
            name.startsWith("python") ||
            name.startsWith("pydoc") ||
            name.startsWith("idle") ||
            name.equals("2to3")
        ) return "python";
        if (name.equals("node") || name.equals("nodejs")) return "node";
        if (name.equals("npm") || name.equals("npx")) return "npm";
        if (name.equals("bash") || name.equals("sh") || name.equals("dash")) return "bash";
        if (
            name.startsWith("clang") ||
            name.startsWith("llvm-") ||
            Set.of("cc", "c++", "cpp", "gcc", "g++", "ld", "ld.lld", "lld", "llc", "opt").contains(name)
        ) return "clang";
        return "";
    }

    public static void relativePath(String path, boolean allowEmpty) {
        if (
            path == null ||
            path.length() > 1024 ||
            (!allowEmpty && path.isEmpty()) ||
            path.startsWith("/") ||
            path.indexOf('\\') >= 0 ||
            path.indexOf('\0') >= 0 ||
            path.indexOf(':') >= 0
        ) throw new IllegalArgumentException("Invalid native project path");
        if (path.isEmpty() && allowEmpty) return;
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException(
                "Invalid native project path"
            );
    }

    /** Protected runtime/loader variables never come from imported project configuration. */
    public static Map<String, String> environment(Map<String, String> requested) {
        if (requested.size() > 100) throw new IllegalArgumentException("Maximum 100 environment variables");
        Map<String, String> safe = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String name = entry.getKey(),
                value = entry.getValue();
            if (
                !name.matches("[A-Za-z_][A-Za-z_0-9]*") ||
                value == null ||
                value.indexOf('\0') >= 0 ||
                value.length() > 8192
            ) throw new IllegalArgumentException("Invalid environment variable");
            String upper = name.toUpperCase(Locale.ROOT);
            if (
                Set.of(
                    "PATH",
                    "HOME",
                    "TMPDIR",
                    "TMP",
                    "TEMP",
                    "PREFIX",
                    "SHELL",
                    "BASH_ENV",
                    "ENV",
                    "IFS",
                    "CDPATH",
                    "GLOBIGNORE",
                    "BASHOPTS",
                    "SHELLOPTS"
                ).contains(upper) ||
                upper.startsWith("LD_") ||
                upper.startsWith("DYLD_") ||
                upper.startsWith("PYTHON") ||
                upper.startsWith("NODE_") ||
                upper.startsWith("NPM_") ||
                upper.startsWith("TERMUX") ||
                upper.startsWith("BASH_FUNC_") ||
                upper.startsWith("FORGE_")
            ) throw new IllegalArgumentException("Protected native environment variable: " + name);
            total += name.length() + value.length();
            if (total > 32768) throw new IllegalArgumentException("Environment exceeds 32 KiB");
            safe.put(name, value);
        }
        return safe;
    }

    public static void arguments(List<String> arguments) {
        if (arguments.size() > 100) throw new IllegalArgumentException("Maximum 100 arguments");
        int size = 0;
        for (String argument : arguments) {
            if (argument == null || argument.indexOf('\0') >= 0) throw new IllegalArgumentException(
                "Invalid native argument"
            );
            size += argument.length();
            if (size > 32768) throw new IllegalArgumentException("Arguments exceed 32 KiB");
        }
    }

    /** All user-controlled values stay argv entries, never interpolated into the build script. */
    public static List<String> command(
        String tool,
        String language,
        String prefix,
        String project,
        String output,
        String file,
        List<String> arguments,
        boolean interactive
    ) {
        key(tool);
        relativePath(file, interactive || tool.equals("npm"));
        arguments(arguments);
        List<String> argv = new ArrayList<>();
        if (interactive) {
            if (!tool.equals("bash")) throw new IllegalArgumentException(
                "Only Bash has an interactive PTY entry point"
            );
            return List.of(prefix + "/bin/bash", "--noprofile", "--norc", "-i");
        }
        switch (tool) {
            case "clang":
                if (!languages(tool).contains(language)) throw new IllegalArgumentException(
                    "Clang requires C or C++"
                );
                argv.addAll(
                    List.of(
                        prefix + "/bin/bash",
                        "--noprofile",
                        "--norc",
                        "-c",
                        "\"$1\" \"$2\" -o \"$3\" -lm || exit \"$?\"; shift 3; exec \"$@\"",
                        "forge-build",
                        prefix + "/bin/" + (language.equals("cpp") ? "clang++" : "clang"),
                        project + "/" + file,
                        output,
                        output
                    )
                );
                break;
            case "python":
                argv.addAll(List.of(prefix + "/bin/python", "-s", project + "/" + file));
                break;
            case "node":
                argv.addAll(List.of(prefix + "/bin/node", project + "/" + file));
                break;
            case "npm":
                argv.addAll(List.of(prefix + "/bin/node", prefix + "/lib/node_modules/npm/bin/npm-cli.js"));
                break;
            case "bash":
                argv.addAll(List.of(prefix + "/bin/bash", "--noprofile", "--norc", project + "/" + file));
                break;
            default:
                throw new IllegalArgumentException("Unknown native tool");
        }
        argv.addAll(arguments);
        return List.copyOf(argv);
    }
}
