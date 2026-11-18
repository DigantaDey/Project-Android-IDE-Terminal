# Forge IDE for Android

A native, offline-first coding workspace for Android phones, tablets, and resizable/foldable windows. Built with Java, the Android SDK, and the Sora code editor—not a WebView editor or a website packaged as an app.

**Android 8.0+ · min SDK 26 · target SDK 35 · Java 17**

## What works

- Native code editing with tabs, shared-document split views, undo/redo, selection/clipboard, line numbers, indentation/pairs, syntax colors, lexical completion, document symbols, find/replace, and project search/replace.
- Language definitions for C, C++, Java, JavaScript, TypeScript, Python, HTML, CSS, JSON, XML, Markdown, SQL, Shell/Bash, Kotlin, and plain text.
- Private projects, file/folder operations, recoverable trash, quick open, session restore, checked saves, and checksummed crash-recovery journals.
- Storage Access Framework import/export, ZIP project backups, opening shared text, and sharing project files without broad storage permissions.
- **Real local JavaScript execution** using interpreted Rhino ES6 in an Android `isolatedProcess` service, with arguments, environment values, initial stdin, output, deadlines, cancellation, and exit codes.
- **Real local SQLite execution** in an isolated, ephemeral, in-memory database.
- **Real JavaScript debugging**: conditional breakpoints, pause/continue, stepping, stack/variables, and watch evaluation.
- Multiple project-shell sessions with scoped file commands, cwd, environment, history, output search, and persisted transcripts. This is explicitly **not a native shell or PTY**.
- Pure-Java Git with local commits/diffs/history/branches/merges/stashes and consent-based HTTPS clone/fetch/pull/push. Repository hooks and native processes are disabled. GitHub credentials are encrypted with Android Keystore and never passed to executed programs.
- Light/dark/system appearance, syntax palettes, font/indentation settings, minimap, symbol keyboard row, command palette, keyboard shortcuts, and adaptive panels.

## Important capability boundaries

Syntax highlighting is not a compiler. Lexical completion/navigation is not semantic type analysis. JSON layout formatting and JavaScript/JSON diagnostics are bundled; full language formatters, linters, LSP servers, and DAP adapters are not.

**No C/C++ or Java compiler, Python interpreter, TypeScript compiler, Node.js, Bash/PTY, npm, pip, Maven, Gradle, CMake, or Make runtime is bundled in the Android app.** The app lists these as unavailable; it does not simulate successful builds, download tools silently, or fall back to `/system/bin/sh`. Provider interfaces are included for future compatible integrations.

The HTML/CSS preview is **static and offline**: JavaScript, external requests, file/content URL access, and native bridges are disabled. SQLite databases do not persist between runs. Hard memory ceilings and CPU-share quotas are unavailable; enabling the strict policy refuses execution.

See the [capability matrix](docs/CAPABILITIES.md) for the exact supported/limited/unavailable behavior.

## Build and install

Install **JDK 17**, the Android SDK, **Android SDK Platform 35**, and a compatible SDK Build Tools version. Configure `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir=…`.

```bash
./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build needs access to Google Maven, Maven Central, and the Gradle distribution. Editing and bundled local tools work offline after installation. No signing secrets or API keys are required to build the debug app.

For the latest branch build, open [GitHub Actions](https://github.com/DigantaDey/Project-Android-IDE-Terminal/actions/workflows/ci.yml) and download **forge-ide-debug-apk** from a successful run. Debug APKs are development artifacts, not Play Store releases.

## Try it

1. Launch **Forge IDE**. The Welcome project opens with `main.js`.
2. Tap **Run** or press **F5**. Its Fibonacci example executes locally in a separate isolated Android UID.
3. Open the command palette, choose **Debug current JavaScript file**, and use the Debugger panel to step or evaluate an expression.
4. Use the project title to create/switch projects or import a folder/ZIP. Imports are private copies, not live external-directory mounts.
5. Use **Settings → Toolchains & dependencies** before relying on a compiler/package manager. Uninstalled providers cannot execute.

More: [user guide](docs/USER_GUIDE.md) · [architecture and extension contracts](docs/ARCHITECTURE.md) · [security and storage model](docs/SECURITY.md) · [testing](docs/TESTING.md) · [third-party notices](THIRD_PARTY_NOTICES.md).

## Repository layout

```text
app/              Native Android UI, SAF, isolated service, Git, Keystore, preview
core/editor/      Text edits, undo, search, indentation, folding model
core/language/    Language registry, lexers, local intelligence, LSP client
core/project/     Confined UTF-8 workspace, ZIP import/export, recoverable trash
core/search/      Bounded project search and checked replacement
core/runtime/     Interpreted JavaScript and its debugger (hosted in isolation on Android)
core/terminal/    Project-scoped virtual shell and session serialization
core/toolchain/   Run configuration, capability registry, build diagnostics
core/sandbox/     Fail-closed isolation/resource capability negotiation
core/git/         Git contracts, remote URL policy, conflict parsing
core/debug/       Breakpoint storage and DAP client
core/settings/    Validated global settings and project overrides
core/recovery/    Checksummed recovery journals outside project storage
core/commands/    Shared command/shortcut/palette registry
core/util/        Atomic files, bounded output/regex, JSON, protocol framing, cancellation
```

All source changes are on the working branch; see the CI run associated with the exact commit for verification. An Android build passing does not, by itself, prove a physical-device security audit or support for every device/ABI.
