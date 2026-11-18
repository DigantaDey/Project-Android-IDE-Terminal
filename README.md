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
- **Optional offline native companion**: real Clang/Clang++/LLD, CPython, Node.js/npm, Make and a Bash PTY. Separate Android UID, disposable project copies, individual on/off switches, networking and npm install scripts off by default. Install both APKs from the same offline bundle; see [native setup and security boundaries](docs/NATIVE_RUNTIME.md).
- Pure-Java Git with local commits/diffs/history/branches/merges/stashes and consent-based HTTPS clone/fetch/pull/push. Repository hooks and native processes are disabled. GitHub credentials are encrypted with Android Keystore and never passed to executed programs.
- Light/dark/system appearance, syntax palettes, font/indentation settings, minimap, symbol keyboard row, command palette, keyboard shortcuts, and adaptive panels.

## Important capability boundaries

Syntax highlighting is not a compiler. Lexical completion/navigation is not semantic type analysis. JSON layout formatting and JavaScript/JSON diagnostics are bundled; full language formatters, linters, LSP servers, and DAP adapters are not.

**Native tools are optional and disabled by default.** They are bundled in a separate offline companion APK—not executed under the IDE UID. The companion deliberately uses legacy target-SDK execution compatibility; the IDE keeps target SDK 35. This is a sideloaded developer bundle, not a Play-ready release. arm64-v8a and x86_64 primary-user installations are supported; native tools share one regular companion UID, not the interpreter's ephemeral `isolatedProcess` boundary.

Java SDK, TypeScript compiler, pip, Maven, Gradle and CMake are **not** installed by this native extension. Unsupported builds are not simulated. No tools are silently downloaded at runtime. A single native run or PTY is active at once; settings switches stop it and gate launch/PATH discovery, not absolute-path access or languages implemented by an enabled programmable shell.

The HTML/CSS preview is **static and offline**: JavaScript, external requests, file/content URL access, and native bridges are disabled. SQLite databases do not persist between runs. Hard memory ceilings and CPU-share quotas are unavailable; enabling the strict policy refuses execution.

See the [capability matrix](docs/CAPABILITIES.md) for the exact supported/limited/unavailable behavior.

## Build and install

Install **JDK 17**, the Android SDK, **Android SDK Platform 35**, **NDK 27.2.12479018**, Python 3 and `dpkg-deb` (for the build-time package inputs). Configure `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir=…`.

```bash
python3 scripts/native-bundle.py build
./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :native-runtime:assembleDebug :native-runtime:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r native-runtime/build/outputs/apk/debug/native-runtime-debug.apk
```

The first build needs access to Google Maven, Maven Central, the Android SDK/NDK, the Gradle distribution, and the pinned Termux package inputs. Large payloads are generated under ignored `build/native/`, never checked into Git. Editing and bundled local tools work offline after installation. No signing secrets or API keys are required to build the debug app.

For the latest branch build, open [GitHub Actions](https://github.com/DigantaDey/Project-Android-IDE-Terminal/actions/workflows/ci.yml) and download **forge-ide-offline-bundle** (both APKs) and **forge-native-corresponding-sources** from the same successful run. The standalone **forge-ide-debug-apk** still works without installing/enabling native capabilities. Debug APKs are development artifacts, not Play Store releases.

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
native-runtime/   Separate legacy-compatibility app UID; native PTY/launcher
runtime-api/      Narrow authenticated Binder transport, not filesystem grants
terminal-ui/      Vendored Apache-2.0 ANSI renderer with a remote-only adapter
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
