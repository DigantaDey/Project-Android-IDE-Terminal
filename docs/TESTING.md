# Build and test

## Standard build

Use JDK 17, an Android SDK with Platform 35, and Gradle's checked-in wrapper (8.10.2). Also install NDK 27.2.12479018, Python 3 and dpkg-deb for native bundle generation. The first build resolves AGP 8.7.3, AndroidX, Sora, Rhino, JGit, and test dependencies from their declared repositories.

```bash
python3 scripts/native-bundle.py build
./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :native-runtime:assembleDebug :native-runtime:lintDebug :terminal-ui:lintDebug
```

Build outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `native-runtime/build/outputs/apk/debug/native-runtime-debug.apk` (optional companion)
- `core/*/build/reports/tests/test/index.html`
- `app/build/reports/lint-results-debug.html`

Do not commit APKs, Gradle caches, SDKs, downloaded runtimes, credentials, or local build output.

## Device integration

Use a **disposable emulator/test installation without real projects or credentials**: instrumentation intentionally exercises fixture files and overwrites/deletes its fake GitHub-vault credential. Do not run it against a personal IDE installation.

Start a hardware-accelerated emulator (CI uses API 26/29 x86_64 phones and an API 35 x86_64 tablet), or connect an Android 8.0+ device with debugging enabled:

```bash
adb install -r native-runtime/build/outputs/apk/debug/native-runtime-debug.apk
./gradlew :app:connectedDebugAndroidTest
# Or assemble, install and run the same AndroidJUnitRunner directly, with logcat/screenshots:
bash scripts/device-tests.sh
```

Instrumentation exercises:

- Real native editor input, undo/redo, disk save, shared split views and terminal retention across Activity recreation, command controls, out-of-project recovery, dirty folder renames/journal/breakpoint migration, shell protection of open buffers, effective execution-policy ceilings, and a gated native Ctrl+Tab/format race that must not overwrite the newly selected buffer.
- Runtime identity, real kernel socket denial and private-file probes, real JavaScript/SQLite, interpreter/native-work deadlines, immediate cancel followed by rerun, old-token cancellation ownership, denied SQL attach/extensions, unsupported strict-policy refusal, and real debugger stepping/watch evaluation/resume events through Binder.
- Real local JGit commits/diffs/history/branches, repository TLS/redirect hardening and forbidden external cookie files, an executable hook witness that must not run, explicit process denial, and encrypted Keystore token round trips/deletion using fake fixture credentials.
- Real static HTML rendering with scripts disabled and confined resource/URL handling when an Android WebView provider exists. The API 26 AOSP image without a provider instead verifies the native unavailable state; the editor and runtimes must remain usable.
- A screenshot of the actual native workspace, written to the test app's external files directory, with a test-only automation capture in `/data/local/tmp` so runner uninstall cannot remove the CI copy. It is not a design mock; the production app exposes no shell automation API.

Native companion tests additionally compile and execute C and C++, use actual CPython with Unicode argv/stdin and private-file/socket denials, call Node CommonJS/filesystem APIs, install an offline local npm dependency without lifecycle scripts, check native launch switches, native deadlines/cancel/rerun and late-cancel ownership, and exercise actual PTY input/resize/pipelines/foreground-child Ctrl-C plus terminal Activity recreation. Additional native cases require signature-permission rejection of an untrusted shell caller, real UNIX-proxy/Binder syscall denial with network off/on, loopback network opt-in, metadata-filtered copies and explicit ZIP export of native-generated files without original-project mutation. The companion is required; missing APKs are failures, not skips.

No real GitHub token or external network dependency is used by device tests. Network authentication/remote server compatibility must be tested separately with a dedicated least-privilege test account; do not put credentials in CI logs or this repository.

## Native host regressions

`python3 -m unittest discover -s scripts -p 'test_*.py' -v` also compiles the exact
native seccomp policy on supported Linux hosts with `cc`. Fresh child processes
verify network-off INET/IPv6/UNIX-proxy and address-send denials, allowed local
socketpair/pipe/subprocess I/O, the network-on switch, Binder ioctl/ptrace/process_vm/
io_uring denials, and the x86 x32 guard. A JDK compiles and runs the actual JNI PTY
launcher against host Bash ten times, checking cold-start output, terminal size,
pipelines, foreground-job Ctrl-C, and a subsequent shell command. It never invokes
the Android companion's UID-wide kill sweep. CI runs the PTY fixture under its own
unprivileged UID so GitHub's runner/.NET/Node threads do not exhaust the actual
64-task per-UID Android limit; the shipped launcher is not relaxed for testing.
These Linux host cases supplement,
not replace, required Android native execution/instrumentation.

## Core regressions

The Java library suites cover the edit/undo model and 4,000 randomized edits; malformed-Unicode token tiling; indentation/pairs/folds; bounded catastrophic regex, zero-width/contextual replacements, Java replacement-template compatibility, large single-pass replacement, stale matches and oversized expansions; JSON validity/formatting lexemes; SQL splitting; path/symlink confinement, invalid UTF-8, checked-save conflicts and ZIP traversal/limits; recoverable trash and restore conflicts; project-search snapshots, contextual/named-group preview-only replacements, bounded expansion before backups, and cancellation immediately before publishing; cancellation races; UTF-8 output limits; recovery checksums, corrupt-entry isolation and bounded project restoration; staged provider imports/failures/conflicts; terminal confinement/history/open-buffer mutation guards; URL/conflict policy; actual JS execution/deadline/cancellation/debug watches; command registry; run configs/provider-language compatibility/build-log parsing; and real in-memory LSP/DAP framing/correlation/disconnect tests.

An optional offline ECJ/JUnit harness exists at `scripts/test-local.sh` for restricted environments. It needs ECJ, JUnit, Hamcrest, and Rhino jars in `.local/tools` and a working Java runtime (`JAVA=…`). Those jars are not vendored. The standard reproducible path is Gradle; the optional harness does not compile the Android app or replace Android lint/device tests.

## CI

`.github/workflows/ci.yml` runs the following verification stages:

1. Core Gradle tests, CI-helper Python regressions, Android app + test APK compilation, and Android lint.
2. Hardware-accelerated emulator instrumentation after a successful build: API 26 phone, API 29 phone, and API 35 tablet. Matrix failures do not cancel the other device cases.

3. A parallel corresponding-source job verifies/publishes the pinned copyleft source archives, GNU incremental patches, complete Termux recipe/patch tree, license inventory and this integration's exact source commit.

The build job generates/caches pinned offline payloads, builds/lints the IDE and companion, and uploads the offline two-APK bundle plus app/test APKs. Every device matrix entry downloads and installs those exact same three APK artifacts; it does not rebuild or resolve Maven dependencies independently. This avoids dependency rate limits and tests a single binary across the matrix. The direct AndroidJUnitRunner status protocol is converted into JUnit XML by a tested, fail-closed parser: missing cases, duplicate/unknown statuses, process death, an abnormal runner exit, or a partial run fail the job. ADB returning zero is not treated as proof of passing tests. The device stage also requires nonempty instrumentation XML and publishes the passed/failed/skipped count. A newer push cancels an older in-progress run. A cancelled emulator is not a passed emulator. Check the exact HEAD commit's run, not an older green build.

Artifacts: **forge-ide-offline-bundle**, **forge-native-corresponding-sources**, **forge-ide-debug-apk**, **forge-ide-instrumentation-apk**, **test-and-lint-reports**, and **device-test-reports-api-…**. Failure diagnostics are also emitted as GitHub check annotations, including instrumentation XML failures, so assertion messages remain visible if artifact download is unavailable.

## Manual release matrix

Automated emulator coverage is not a substitute for:

- API 26, 29, 33, and 35+ devices; low-memory device; OEM file providers/Keystore/WebView.
- Phone portrait/landscape, tablet, split-screen/freeform/foldable resize, large font/accessibility scaling, IME visible/hidden, RTL and TalkBack.
- Dirty split documents during rotation, close/discard, project switching, autosave, terminal/file/Git changes, force-stop/relaunch, low storage, and document-provider cancellation/revocation.
- Import/export/share with malicious paths, huge/deep/corrupt archives, binary/invalid UTF-8, symlinks, and conflicting destinations.
- Output floods, deep recursion, allocation pressure, cancel-before-bind, cancel/rerun, binder death, backgrounding, and debug evaluation with getters/side effects.
- HTTPS public/private clone/fetch/pull/push, unusual default branches, redirected/malicious remotes, conflicts, stash conflicts, offline errors, and credential rotation/disconnect.

Do not describe this manual matrix as passed unless it was actually run and recorded for the release.

## Source formatting

Host Node.js is only source-formatting/CI tooling, not required to compile with Gradle. The separately bundled optional on-device Node runtime is an unrelated Android/bionic binary.

```bash
npm ci --prefix tools/format --ignore-scripts
npm --prefix tools/format run check
# To apply the configured Java formatter:
npm --prefix tools/format run format
```

The lockfile pins Prettier and its Java plugin. The formatting toolchain does not install anything into the Android runtime; native npm is bundled and consented separately.

For a restricted QA environment that cannot follow GitHub's artifact-storage redirects, a manual workflow dispatch can enable `visual_report=true`, or a deliberate `[visual-qa]` commit-message marker can opt in on a branch that has not yet added its workflow to the default branch. It emits only the small fixture-workspace JPEG as machine-readable check notices. A test-only instrumentation flag records bounded thumbnail chunks under a dedicated log tag if runner uninstall prevents direct retrieval; the reporter rejects missing/incomplete data and publishes a byte-count/chunk-count/SHA-256 manifest. Thumbnails adapt to a 24,000-byte budget, and one manifest plus at most nine chunks fits GitHub’s ten-notice per-step ceiling. Notice chunks stay below the checks API’s 4 KiB message truncation limit; verify that manifest when reconstructing a thumbnail. Normal push CI only uploads the PNG artifact and does not log thumbnail bytes. Never enable screenshot transport for a run using private user projects or credentials.
