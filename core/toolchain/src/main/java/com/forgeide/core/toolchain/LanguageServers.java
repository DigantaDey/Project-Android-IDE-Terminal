package com.forgeide.core.toolchain;

import java.util.*;

/** Pinned companion servers, separate from consent to execute a user's programs. */
public final class LanguageServers {

    private LanguageServers() {}

    public static final List<String> ORDER = List.of(
        "clangd",
        "pyright",
        "typescript",
        "bashls",
        "html",
        "css",
        "json"
    );

    public static String key(String id) {
        if (!ORDER.contains(id)) throw new IllegalArgumentException("Unknown bundled language server: " + id);
        return "native.lsp." + id;
    }

    public static String label(String id) {
        switch (id) {
            case "clangd":
                return "C / C++ · clangd 21.1.8";
            case "pyright":
                return "Python · Pyright 1.1.414";
            case "typescript":
                return "JavaScript / TypeScript · TS Language Server 6.1.0 + TypeScript 6.0.2";
            case "bashls":
                return "Bash · Bash Language Server 5.8.1";
            case "html":
                return "HTML · VS Code HTML server 4.10.0";
            case "css":
                return "CSS / SCSS / Less · VS Code CSS server 4.10.0";
            case "json":
                return "JSON / JSONC · VS Code JSON server 4.10.0";
            default:
                throw new IllegalArgumentException("Unknown bundled language server");
        }
    }

    public static String forLanguage(String language) {
        switch (language) {
            case "c":
            case "cpp":
                return "clangd";
            case "python":
                return "pyright";
            case "javascript":
            case "javascriptreact":
            case "typescript":
            case "typescriptreact":
                return "typescript";
            case "shellscript":
                return "bashls";
            case "html":
                return "html";
            case "css":
            case "scss":
            case "less":
                return "css";
            case "json":
            case "jsonc":
                return "json";
            default:
                return "";
        }
    }

    /** Only allowlisted argv: no shell, project executable, plugin installer or npx. */
    public static List<String> command(String id, String prefix) {
        key(id);
        if (id.equals("clangd")) return List.of(
            prefix + "/bin/clangd",
            "--background-index=false",
            "--clang-tidy=false",
            "--enable-config=false",
            "--log=error",
            "-j=2"
        );
        return List.of(
            prefix + "/bin/node",
            "--max-old-space-size=256",
            prefix + "/share/forge-lsp/launch.cjs",
            id
        );
    }

    public static Map<String, Object> initialization(String id, String prefix) {
        key(id);
        if (id.equals("typescript")) return Map.of(
            "hostInfo",
            "Forge Android",
            "disableAutomaticTypingAcquisition",
            true,
            "maxTsServerMemory",
            256,
            "tsserver",
            Map.of(
                "path",
                prefix + "/share/forge-lsp/node_modules/typescript/lib/tsserver.js",
                "useSyntaxServer",
                "never"
            ),
            "preferences",
            Map.of("includeCompletionsForModuleExports", true, "includeCompletionsWithInsertText", true)
        );
        if (id.equals("bashls")) return Map.of(
            "shellcheckPath",
            "",
            "shfmt",
            Map.of("path", ""),
            "explainshellEndpoint",
            "",
            "backgroundAnalysisMaxFiles",
            100
        );
        return Map.of();
    }

    /** User/project config never supplies client-side commands, downloads or live IDE paths. */
    public static Map<String, Object> configuration(String id, String prefix) {
        key(id);
        switch (id) {
            case "pyright":
                return Map.of(
                    "python",
                    Map.of(
                        "pythonPath",
                        prefix + "/bin/python",
                        "analysis",
                        Map.of(
                            "typeCheckingMode",
                            "basic",
                            "diagnosticMode",
                            "openFilesOnly",
                            "autoSearchPaths",
                            true,
                            "useLibraryCodeForTypes",
                            true
                        )
                    )
                );
            case "typescript":
                return Map.of(
                    "typescript",
                    Map.of("disableAutomaticTypeAcquisition", true),
                    "javascript",
                    Map.of("implicitProjectConfig", Map.of("checkJs", true))
                );
            case "bashls":
                return Map.of("bashIde", initialization(id, prefix));
            case "json":
                return Map.of(
                    "json",
                    Map.of(
                        "validate",
                        Map.of("enable", true),
                        "schemaDownload",
                        Map.of("enable", false),
                        "schemas",
                        List.of()
                    )
                );
            case "html":
                return Map.of("html", Map.of("validate", Map.of("scripts", true, "styles", true)));
            default:
                return Map.of();
        }
    }
}
