# Bundled offline semantic language servers

Available in **0.3.0-preview.1** with the matching offline native companion. This is
a sideloaded **debug/developer preview**, not a production-signed or Play-ready app.
Install both APKs from the same release. See [installation/security](NATIVE_RUNTIME.md).

## Included engines

| Switch | Bundled engine | Languages / useful features |
| --- | --- | --- |
| clangd | LLVM clangd 21.1.8 (same pinned Clang package, revision 3) | C/C++ completion, types, definition/references, symbols, diagnostics and formatting |
| Pyright | 1.1.414, including typeshed fallback | Python type-aware completion, hover, navigation, symbols and diagnostics; Pyright does not provide document formatting |
| TypeScript | typescript-language-server 6.1.0 + **JavaScript-based TypeScript 6.0.2** | JavaScript, TypeScript, JSX, TSX; inference, completion, hover, imports/types, navigation, symbols, diagnostics and formatting |
| Bash | bash-language-server 5.8.1 + bundled tree-sitter WASM | Bash function/variable completion, navigation, symbols and parser diagnostics; **not** ShellCheck linting or shfmt formatting |
| HTML | vscode-langservers-extracted 4.10.0 | HTML element/attribute completion, hover, document symbols, supported navigation and formatting |
| CSS | vscode-langservers-extracted 4.10.0 | CSS/SCSS/Less property/value completion, hover, symbols, validation and formatting |
| JSON | vscode-langservers-extracted 4.10.0 | JSON/JSONC validation, symbols, formatting and contextual completion; no remote schema downloads |

These are actual upstream servers, not a lexical engine renamed “semantic.”
The table is not a promise that every LSP method applies to every language:
**initialize capabilities are checked before requests**; an unsupported method
reports that it is unavailable. HTML/CSS/JSON provide structured language services,
not general-purpose program type checking. No Java/Kotlin/Rust/Go/etc. server,
additional debugger or DAP adapter is included.

## Enable and use

1. Open **Settings → Toolchains & native runtime switches → Bundled semantic
   language servers**, or use the palette command of that name.
2. Enable the desired engine and accept the project-copy/security notice. All seven
   switches are **off by default** and global; imported projects cannot enable them.
   Enabling a server does **not** enable running user programs with Node/Python/Bash
   or enable the network/npm-script switches. The server may use its bundled engine
   and subprocess helpers in the companion.
3. Open a supported file. The status bar shows **starting offline**, then
   **server · semantic**, or a real failure. When off, the original suggestions and
   navigation remain explicitly **lexical/textual**, not silently semantic.
4. Type for Sora completion suggestions, or choose **Semantic completion
   suggestions** (`Ctrl+Space`). Use **Semantic hover / type information** (`Ctrl+K`),
   **Find definition** (`F12`), **Find references** (`Shift+F12`), document symbols
   and Format. Semantic diagnostics appear in Problems and editor markers with
   an `LSP · server` source.
5. **Restart language server / refresh project copy** rereads the project from disk
   and resends unsaved open documents. Use this after changing closed files through
   external tools. Forge project file operations also refresh the copy. Failed
   servers do not enter an automatic crash/relaunch loop; use Restart.

## Lifecycle, correctness and limits

One **server family** and project copy is active at a time. C/C++ share clangd;
JS/TS/JSX/TSX share TypeScript. Switching family/project replaces the process/copy.
Native runs and the native PTY preempt background intelligence, rather than sharing
an untrusted process with it. Servers stop when the editor leaves the foreground,
on consent changes, on Stop, or at a 30-minute deadline. **Stop** pauses semantic
intelligence until Restart; background/foreground suspension resumes automatically.

The copy excludes Git/Forge/backup metadata and is bounded to **32 MiB / 2,000
entries**. A server sees up to 20 open supported text documents, including unsaved
text, each bounded to **512 KiB**. Edits are synchronized using LSP open/change/close
and monotonically increasing versions. Versioned stale diagnostics are discarded;
servers that omit diagnostic versions are necessarily best-effort. There is no
live filesystem watcher or automatic write-back from the companion. Configurations,
compile_commands.json, installed project stubs/headers/types and project size still
matter; unsupported external SDKs and missing dependencies cannot be invented.

Requests use **UTF-16 positions** and raw **Content-Length framed UTF-8** over three
separate stdin/stdout/stderr pipes. They never use cooked terminal I/O or a socket
listener. Frames are limited to 8 MiB; 64 pending requests and 128 queued writes
are allowed. Requests time out and cancellation sends `$/cancelRequest`; stopping
closes the protocol and kills/reaps the server's native descendants. stderr is
drained separately into bounded logs, never injected into JSON-RPC.

Completion and formatting apply validated single-document edits only while the
same project, document, revision and provider still own the result. Completion
also checks cursor position. Only plain insertions/replacements and a simple final
`$0` cursor marker are supported; interactive placeholders/snippet variables,
completion commands, lazy resolve, auto-import additional edits and multi-file
workspace edits are **not executed**. The CSS server's suggestion re-trigger
command is ignored, not run. Invalid/overlapping/surrogate-splitting formatter
ranges are rejected atomically. Server-initiated edits, command execution, live
watch registration and URI-opening requests are rejected. Hover is displayed as
plain selectable text, not active HTML. Definition/reference URIs can map only
inside the disposable project, including Android's primary-user path alias;
external library/SDK and IDE-private paths are never opened as project files.

## Isolation and offline guarantee

LSP processes use the **separate regular companion UID**, not the IDE UID and not
the stricter Rhino/SQLite isolated-process sandbox. They receive no GitHub vault,
Keystore keys, SAF grants or live IDE file descriptors. Ordinary source files can
contain secrets: opt in only for projects you are willing to share with this UID.
Servers parse project configuration and may use subprocesses; this is not a
hostile-code VM, per-language isolation or an immutable tool installation.

**Language-server networking is always denied by the native child syscall policy,
even when ordinary native networking is enabled.** This includes new Internet and
UNIX socket connections/addressed sends; preconnected private socketpairs/pipes
remain available to subprocess helpers. Automatic TS typing acquisition and JSON
schema downloads are disabled as well. No npm/npx/pip installer is invoked. Missing
dependencies stay missing until the user explicitly supplies them separately.
A 256 MiB V8 heap preference is not a hard process-memory ceiling. Strict hard CPU/
memory quota policy refuses server launch; do not mistake it for a full container.

## Reproducible payload and notices

- `tools/native/servers/package-lock.json`: exact SHA-512 npm distribution integrity
  for all dependencies; **64 non-optional, platform-independent distributions**.
- TypeScript is overridden transitively to the pinned JS release; incompatible TS 7
  native platform packages and optional Darwin fsevents are not included.
- `scripts/semantic_bundle.py` verifies and extracts the original archives directly.
  It never invokes npm, package lifecycle scripts or native-addon build scripts.
  Upstream license/copyright/third-party notices and WASM data are retained.
- `usr/share/forge-lsp/{inventory.json,NOTICE.md,package-lock.json}` in the companion
  records the installed versions, original URLs, licenses and hashes. The matching
  corresponding-source release archive includes every original npm distribution,
  these locks/notices and the exact Forge integration source.
- Compatibility ID hashes the native lock, server package/lock and launch recipe,
  not just the old compiler payload. **Protocol 3** requires raw stdio; older
  companions fail the matching-pair checks. Native APK installation additionally
  verifies the whole ZIP and each extracted file before activation.

See [testing](TESTING.md) for the host protocol tests, real engine smoke tests and
Android API 26/29/35 device matrix. Emulator success is not physical ARM/OEM,
16-KiB-page, production-signing or security-audit certification.
