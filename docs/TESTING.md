# Build and test

## Standard build

Use JDK 17, an Android SDK with Platform 35, and Gradle's checked-in wrapper (8.10.2). The first build resolves AGP 8.7.3, AndroidX, Sora, Rhino, JGit, and test dependencies from their declared repositories.

```bash
./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Build outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `core/*/build/reports/tests/test/index.html`
- `app/build/reports/lint-results-debug.html`

Do not commit APKs, Gradle caches, SDKs, downloaded runtimes, credentials, or local build output.

## Device integration

Start a hardware-accelerated emulator (CI uses API 29 x86_64 / Pixel 2), or connect an Android 8.0+ device with debugging enabled:

```bash
./gradlew :app:connectedDebugAndroidTest
# Or collect logcat/screenshot reports even on a failure:
bash scripts/device-tests.sh
```

Instrumentation exercises:

- Real native editor input, undo/redo, disk save, shared split views and terminal retention across Activity recreation, command controls, and out-of-project recovery.
- Runtime identity, real kernel socket denial and private-file probes, real JavaScript/SQLite, interpreter/native-work deadlines, immediate cancel followed by rerun, old-token cancellation ownership, denied SQL attach/extensions, and unsupported strict-policy refusal.
- Real local JGit commits/diffs/history/branches, repository TLS/redirect hardening and forbidden external cookie files, an executable hook witness that must not run, explicit process denial, and encrypted Keystore token round trips/deletion using fake fixture credentials.
- Real static HTML rendering with scripts disabled and confined resource/URL handling.
- A screenshot of the actual native workspace, written to the test app's external files directory and pulled into CI artifacts. It is not a design mock.

No real GitHub token or external network dependency is used by device tests. Network authentication/remote server compatibility must be tested separately with a dedicated least-privilege test account; do not put credentials in CI logs or this repository.

## Core regressions

The Java library suites cover the edit/undo model and 4,000 randomized edits; malformed-Unicode token tiling; indentation/pairs/folds; bounded catastrophic regex, zero-width/contextual replacements, Java replacement-template compatibility, large single-pass replacement, stale matches and oversized expansions; JSON validity/formatting lexemes; SQL splitting; path/symlink confinement, invalid UTF-8, checked-save conflicts and ZIP traversal/limits; recoverable trash and restore conflicts; project-search snapshots; cancellation races; UTF-8 output limits; recovery checksums; terminal confinement/history; URL/conflict policy; actual JS execution/deadline/cancellation/debug watches; command registry; run configs/build-log parsing; and real in-memory LSP/DAP framing/correlation/disconnect tests.

An optional offline ECJ/JUnit harness exists at `scripts/test-local.sh` for restricted environments. It needs ECJ, JUnit, Hamcrest, and Rhino jars in `.local/tools` and a working Java runtime (`JAVA=…`). Those jars are not vendored. The standard reproducible path is Gradle; the optional harness does not compile the Android app or replace Android lint/device tests.

## CI

`.github/workflows/ci.yml` runs two required verification stages:

1. Core Gradle tests, Android app + test APK compilation, and Android lint.
2. Hardware-accelerated emulator instrumentation after a successful build.

A newer push cancels an older in-progress run. A cancelled emulator is not a passed emulator. Check the exact HEAD commit's run, not an older green build.

Artifacts: **forge-ide-debug-apk**, **test-and-lint-reports**, and **device-test-reports**. Failure diagnostics are also emitted as GitHub check annotations, including instrumentation XML failures, so assertion messages remain visible if artifact download is unavailable.

## Manual release matrix

Automated API 29 coverage is not a substitute for:

- API 26, 29, 33, and 35+ devices; low-memory device; OEM file providers/Keystore/WebView.
- Phone portrait/landscape, tablet, split-screen/freeform/foldable resize, large font/accessibility scaling, IME visible/hidden, RTL and TalkBack.
- Dirty split documents during rotation, close/discard, project switching, autosave, terminal/file/Git changes, force-stop/relaunch, low storage, and document-provider cancellation/revocation.
- Import/export/share with malicious paths, huge/deep/corrupt archives, binary/invalid UTF-8, symlinks, and conflicting destinations.
- Output floods, deep recursion, allocation pressure, cancel-before-bind, cancel/rerun, binder death, backgrounding, and debug evaluation with getters/side effects.
- HTTPS public/private clone/fetch/pull/push, unusual default branches, redirected/malicious remotes, conflicts, stash conflicts, offline errors, and credential rotation/disconnect.

Do not describe this manual matrix as passed unless it was actually run and recorded for the release.

## Source formatting

Node.js is development tooling only, not a requirement to build or run the Android app with Gradle.

```bash
npm ci --prefix tools/format --ignore-scripts
npm --prefix tools/format run check
# To apply the configured Java formatter:
npm --prefix tools/format run format
```

The lockfile pins Prettier and its Java plugin. No package-manager functionality is implied inside the Android app.

For a restricted QA environment that cannot follow GitHub's artifact-storage redirects, a manual workflow dispatch can enable `visual_report=true`, or a deliberate `[visual-qa]` commit-message marker can opt in on a branch that has not yet added its workflow to the default branch. It emits only the small fixture-workspace JPEG as machine-readable check notices; normal push CI only uploads the original PNG artifact. Never enable screenshot transport for a run using private user projects or credentials.
