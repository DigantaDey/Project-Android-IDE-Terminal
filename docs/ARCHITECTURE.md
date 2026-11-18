# Architecture and provider contracts

## Boundaries

```text
Android app UID (trusted IDE)
  MainActivity / Sora editor / application workspace state
  private project authority, recovery, settings, Git, Keystore
            │ bounded source pipe + explicit launch bundle
            │ callbacks: bounded output / events / exit
            ▼
Android isolated UID (ephemeral, non-exported service)
  ExecutionService → Rhino JS + debugger, or in-memory SQLite
  no project directory, vault, SAF grant, app permissions, native shell
```

`ForgeApplication` owns process-lifetime documents and the single-threaded storage executor. Recreating an Activity does not recreate document contents or undo stacks. Sora `Content` instances are shared by split views; each editor has its own language/analyzer instance and is released when its view is destroyed. Recovery journals survive app process loss; undo history does not.

The Application is also created by Android in the isolated process. Its early UID check deliberately skips all host project, recovery, and credential initialization there.

## Modules

Core modules are Java 17 libraries without Android UI dependencies. The Android app supplies OS services and adapters.

- **editor:** indexed document/edit models, compound undo/redo, indentation, paired characters, folding calculation, bounded find/replace.
- **language:** language/lexer registry; bounded lexical completion/symbol/declaration/fold services; JSON layout formatter; SQL statement splitting; LSP client facade.
- **project:** path-confined UTF-8 file authority, checked writes, bounded ZIP transfer, recoverable trash. A resolved workspace root is the only file authority given to project tools.
- **search:** cancellation-aware project search and digest-checked preview replacement.
- **terminal:** a project-scoped command interpreter; built-ins never use a host shell. External command names require explicit provider registration.
- **toolchain / sandbox:** immutable run data, installed-provider registry, diagnostic/build-log parsing, required restriction negotiation.
- **runtime / debug:** Rhino interpreter/debugger, breakpoint definitions, DAP client facade.
- **git:** trusted Git interface, HTTPS remote policy, conflict-marker parser. The Android JGit adapter disables process/hooks/symlink integrations.
- **settings / recovery / commands:** validated settings overlays; checksummed journals outside projects; shared command/palette/shortcut actions.
- **util:** atomic fsynced temporary writes, cancellation, JSON/framing, protocol request correlation, bounded regex input/output.

## Adding a language

1. Register a `Language` and `Tokenizer` in `LanguageRegistry` (or an injected registry).
2. Tokens must tile the input without overlap, gaps, invalid offsets, or infinite loops—even for malformed Unicode/unclosed literals.
3. Provide lexical intelligence using `LanguageService`, or an LSP transport/adapter with explicit lifecycle ownership.
4. Keep long work off the UI thread; honor cancellation, input limits, and stale-revision checks.
5. Add malformed-input, Unicode, completion/indentation, and tokenizer-tiling regressions. Update the capability UI/documentation without claiming a compiler exists.

## Adding an execution provider

Implement `Toolchain`, or `DebuggableToolchain` for optional debugging, then register it in `ToolchainRegistry`. `IsolatedToolchain` demonstrates built-in registration rather than shell command selection in the UI.

Required rules:

- Negotiate `SandboxPolicy.required` **before** running. Refuse unsupported restrictions; do not silently lower the policy.
- Execute untrusted code outside the app UID. Never pass `Context`, Keystore access, GitHub credentials, global settings/recovery paths, SAF grants, or arbitrary Binder/file authorities into a runtime.
- Treat `RunConfiguration` command fields as argv, not concatenated shell text. Built-in interpreters reject custom commands entirely.
- Specify exactly which languages, ABI/API levels, filesystem/network/process operations, quotas, debugger functions, and dependency installers are actually supported.
- Own pipes, process/binding, cancellation, output budgets, watchdog, and cleanup per launch. Make stop idempotent; stale callbacks from a finished launch must not affect another launch.
- Do not download, install, update, execute hooks, or launch package-manager scripts without explicit user-visible consent/status/errors and a compatible isolation model.
- Handle service death, bind failure, Android background limits, output floods, malformed IPC, and resource-pressure termination as real errors.

The current app ships only JavaScript and SQL providers. The extension contracts do not imply an arbitrary runtime/plugin downloader or safe native-container implementation exists.

## Protocol transports

`FramedJson` implements bounded Content-Length framing in UTF-8. `ProtocolClient` owns a reader, correlated pending futures, deadlines, disconnection, and transport closure. `LspClient` and `DapClient` expose protocol operations without binding them to a fake local server.

No server executable is bundled. A future transport must isolate its server, enforce its capabilities, close streams/processes on cancellation, validate coordinates/URIs, and re-check document revisions before applying edits. Untrusted servers must not inherit app credentials or host filesystem access.

## Data flow and lifecycle

- Files are loaded with strict UTF-8/binary/size checks. Saves serialize the document's line-ending policy and use a last-known disk digest to detect conflicting changes.
- Dirty state derives from edit and saved revisions, so an older background save cannot mark a newer edit clean.
- Edits schedule a 600 ms recovery flush; configurable autosave is independent. `onStop` queues session/journal persistence, not an assertion that Android will grant unlimited background time.
- Activity observers, diagnostic tasks, analyzers, and native editors are detached on destruction. Execution belongs to application state, not an obsolete editor view. User stop/finish terminates it.
- Runtime source crosses a bounded pipe instead of a potentially oversized Binder string. The launch bundle, source, breakpoints, watch expression, output, and time have separate limits.
- Static preview receives only the current private workspace reference in the trusted app process and serves confined resources under `https://forge.invalid/`. Its renderer has no native bridge or JavaScript.

## Conventions

Use explicit, actionable error states. Do not catch a failure and present successful build/install output. Preserve recovery/backups on failure. Keep generated artifacts and credentials out of version control. Pin dependencies; run core tests, Android compile/lint, and device integration before merging. Formatting is configured by `.editorconfig` and `.prettierrc.json`.

## Optional offline native companion

`native-runtime` is a separate application (`com.forgex`) with a distinct regular
Android UID and explicit target-28 compatibility mode; `app` stays target 35.
`runtime-api` carries a bounded snapshot FD, launch bundle, PTY byte I/O/resize,
termination, and explicit project-copy export. Both packages verify the same
signature, expected UID/protocol and reviewed package-lock ID. Native switches are
global consent, never project overrides. Cancellation is serialized with the start
RPC, and late run IDs cannot stop a new run.

`native-bundle.py` constructs APK assets from SHA256-locked Android/bionic package
inputs at build time. Payload/file hashes and bounded install paths are verified
before publishing the prefix. No maintainer script or runtime downloader runs on
the phone. The JNI PTY launcher exists only in the companion; the IDE's vendored
Apache-2.0 terminal renderer uses a remote-only session adapter and cannot fork.
The companion starts children with only PTY FDs and an explicit environment,
network seccomp filtering when off, and basic RLIMITs. Its UID is shared between
native tools, and no aggregate hard CPU/memory quota is claimed.

Native launches receive copies (32 MiB/2,000 entries), not mounts or project URI
capabilities. Changes and npm installations remain in the PTY copy until exit,
with explicit bounded ZIP export; no automatic IDE import/write-back. One native
run/PTY is active at once. Activity recreation retains the terminal renderer and
remote session in application state; Android process death is not process restore.
See [NATIVE_RUNTIME.md](NATIVE_RUNTIME.md) for operational and security limits.
