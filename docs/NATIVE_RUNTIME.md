# Offline native runtime companion

**Sideloaded developer build, not a Play-ready distribution.** The IDE still targets
API 35. The optional companion targets API 28 deliberately to use Android's legacy
native-executable compatibility policy. Do not change the IDE's target SDK to run
code in its own UID. No root, shared UID, shared external storage, accessibility,
or broad storage permission is used.

## Install and enable

1. Download `forge-ide-offline-bundle` from a successful CI run. Extract it.
2. Install **both** `forge-ide.apk` and `forge-native-runtime.apk` from that same
   bundle with Android's package installer. Android requires explicit sideload
   approval. Do not install an unrelated APK with the same package name.
3. In Forge, open **Settings → Toolchains & native runtime switches**.
4. Enable **Clang**, **Python**, **Node.js**, **npm**, or **Bash** individually.
   All default off. npm also enables Node; disabling Node disables npm.
5. Open a C/C++, Python, JavaScript, or Shell/Bash file and use Run. Enabled Node
   takes priority for ordinary JavaScript runs; the existing isolated Rhino
   debugger remains separate. A configured `javascript` provider still means Rhino.
6. Use **Open native Bash PTY** in the palette, tool settings, or terminal toolbar
   for a real ANSI terminal, pipes, interactive stdin, job control, compiler
   commands, and npm. Ctrl-C interrupts the foreground job. Resize and keyboard
   input are forwarded to the companion PTY, not to an IDE-owned process.

Turning a switch off stops the native session and blocks future Forge launches.
It removes that tool family from normal PTY command discovery and blocks Forge's
entry point. It does **not** shrink an installed APK, revoke absolute-path access
to companion-owned files, or make other interpreters impossible to implement
inside an enabled shell. These are user-consent/launch controls, not DRM
or separate security sandboxes for each language.

## Offline contents

The companion contains Android/bionic arm64-v8a and x86_64 builds of Clang/Clang++,
LLD, C/C++ headers/runtime, Make, CPython, Node.js, npm, Bash, core file utilities,
and their pinned dependencies. See `native-package-lock.json` and
`runtime-catalog.json` in the bundle for exact versions, hashes and unpacked sizes.
There is no on-device runtime download. The first native launch unpacks and checks
the appropriate ABI from the APK; allow substantial internal storage and a longer
first launch. Only the primary Android user is supported by this prefix-based
compatibility bundle. 32-bit-only and secondary/work-profile installations fail
explicitly. arm64 binaries are packaged, but x86_64 emulator checks are not a
physical ARM-device certification.

Java SDK, TypeScript, pip, CMake, semantic LSP/DAP servers, and native debuggers are
not bundled by this extension. npm native add-ons may need additional build tools
or Android-specific patches; bundling npm does not make every desktop package
Android compatible.

## Project copies and lifetime

Native execution receives a **disposable copy**, not a live mount, of the selected
private project. `.git`, `.forge`, recovery/trash and replacement-backup stores
are excluded. Ordinary project files can still contain secrets: inspect them
before running untrusted code. Copies are bounded to 32 MiB and 2,000 entries.
There is no silent import or automatic write-back to the IDE. Native file runs
remove the copy when they finish.

One native file run **or** native PTY can be active at once. The six existing
scoped project-shell sessions are unaffected. A PTY keeps its cwd, environment,
installed packages and files only for that session. Use **terminal menu → Export
project copy as ZIP** before exit/stop. Exports are explicit SAF writes; no storage
grant is passed to the native child. A failed export can leave an incomplete ZIP;
check its success before discarding the session. Ctrl-C a mutating command before
exporting. Native sessions and their changes are not crash-recovery journals.

The terminal survives Activity recreation while the IDE process lives, but not an
Android process kill, force-stop, deadline, or device restart. Its maximum lifetime
is 30 minutes and output is capped at 1 MiB. File runs use the configured execution
time/output limits. Native stdout/stderr are merged by the PTY.

## Network and npm consent

Native networking and npm lifecycle installation scripts default **off**, with
separate explicit warnings in Settings. With network off, the launcher installs
an inherited seccomp filter that denies socket creation/connect/send-address
operations and sets npm offline. Local preconnected socketpair/pipe I/O remains
available for child-process communication; new UNIX socket clients are blocked
too, preventing resolver/proxy bypasses.
If restrictions cannot be installed, it refuses execution. With network enabled,
native code can use the companion's INTERNET permission. Only run code you trust.
Changing either preference stops the current native session.

Typing `npm install` explicitly requests dependency installation. Offline local
packages work without network; online registry access needs the network setting.
Lifecycle scripts remain disabled by default even then. Native launch environment
variables cannot replace Forge's loader/PATH or inject NODE_OPTIONS, PYTHONPATH,
BASH_ENV or npm policy through imported run configuration. An enabled programmable
shell can deliberately choose other behavior; only the OS identity/network
boundary is a security guarantee.

## Security boundary

The companion package is `com.forgex`, separate from `com.forgeide.app`. Its exact
length supports relocating upstream's compiled-in prefix without corrupting ELF
files. Signature permission, calling UID, protocol and bundle-ID checks protect
the private Binder protocol. Install both APKs from one build. No app credential,
GitHub token, Keystore key, live project FD, or SAF authority is passed to a child.
The child inherits only its PTY descriptors and an explicit sanitized environment.
Runtime-generated data and output are untrusted.

This is a **separate regular app UID**, not Android `isolatedProcess`. Native tools
can create processes and access their companion UID's own files. They are not
isolated from one another. The app's stricter JS/SQL isolated service is unchanged.
Native children cannot ptrace/process_vm-read their service parent, issue Android
Binder transactions, or use io_uring to bypass the syscall policy. Basic process,
open-FD and individual-file resource limits apply, but no hard aggregate memory,
disk-volume or CPU-share quota is claimed. Strict hard-limit policy refuses native
execution. Stop/deadline kills companion-UID native jobs, including job-control
process groups; no IDE-owned native execution fallback is allowed. OEM execution
policies may still reject compatibility binaries, which is an error, not a
successful run.

## Build and upstream provenance

`python3 scripts/native-bundle.py build` fetches **build-time only**, SHA256-locked
Termux `.deb` inputs and constructs deterministic APK assets under ignored
`build/native/`. Catalog refresh is a separate operation: `... lock` first verifies
InRelease with the vendored public upstream keys. Package maintainer scripts are
not executed. Prefix relocation and generated per-file/payload hashes are checked
before the atomic install. Do not put binary payloads into Git.

The package recipes/patches originate from `termux/termux-packages`; inputs retain
their respective upstream licenses, not a blanket Forge license. The native APK
and compiled code have substantial transitive licensing/distribution obligations;
see `THIRD_PARTY_NOTICES.md`. Download **forge-native-corresponding-sources** from
the same CI run for the verified copyleft source inputs, GNU patches, complete
recipe tree, license inventory and this integration's source. This development
build is not a certification of production store policies or every distribution/
relinking obligation.
