# Security, privacy, and storage model

## Threat model

Projects, source programs, Git metadata/remotes, archives, regular expressions, shared content URIs, and future language-server output can be untrusted. The IDE itself, Android OS, bundled dependencies, and enabled provider implementations are trusted. Rooted/compromised devices, Android/interpreter vulnerabilities, malicious keyboards/accessibility services, and a user intentionally exporting a secret are outside the app's guarantees.

**Do not treat this app as a hardened native-code container.** The built-in interpreters have no project/host filesystem API. Adding arbitrary native executables requires a new security design, not permission to fall back to the app UID.

## Trust domains

1. **Trusted app UID:** private projects, editor state, settings, recovery, Git/network requests, Android Keystore. Its only manifest permission is INTERNET; there is no broad storage, root, camera, microphone, contacts, location, or all-files permission.
2. **Project command authority:** the virtual shell uses a confined `Workspace`. It does not receive the app's state/recovery/vault directories and never invokes a host shell. It is trusted IDE functionality operating on explicit project data—not an unrestricted native shell.
3. **Untrusted execution UID:** the non-exported `ExecutionService` has `isolatedProcess=true`. Android gives it a separate ephemeral UID without the app's inherited permissions or app-private storage. Only bounded source bytes and explicit launch data are passed to it. It verifies Binder callers against the app UID.
4. **Static WebView renderer:** no JavaScript, native bridge, file/content access, external network, geolocation, or database storage. Confined resources are intercepted under a synthetic HTTPS origin with a restrictive CSP. A renderer failure closes the preview rather than weakening settings.

## Execution restrictions

Rhino uses interpreted execution, safe standard objects, denied Java class access, bounded interpreter stack, an instruction observer, output limits, and a debugger scoped to the same program. These are defense-in-depth; the Android isolated UID is the permission boundary.

SQLite uses a fresh in-memory database, memory temp storage, foreign-key checks, quote/comment-aware splitting, and denied ATTACH/DETACH. Query results are bounded. SQL has no project-file API or interactive debugger. bundled AndroidX SQLite native operations still allocate native memory; an external watchdog bounds run duration.

The interpreter deadline, service main-thread watchdog, and host deadline are independent. Cancellation kills the service process, not just an interpreter flag. Each launch owns its binding, pipe, completion, and watchdog, preventing old callbacks from cleaning up a newer run. Android service/low-memory death is reported as termination, not success.

### Resource limits

- Execution source: 512 KiB; launch parcel: 128 KiB; at most 1,000 selected breakpoints.
- Time: bounded to 100–60,000 ms at the service boundary; normal settings default to 10 seconds. Debug pauses consume their deadline. Debug and ordinary execution share the same effective global/project ceilings; a debug launch cannot raise them.
- Output: bounded per run; default 256 KiB, configurable in the UI within supported limits.
- SQL query display: at most 1,000 rows and bounded cell text.
- Project editor: strict UTF-8, no NUL/binary content, at most 8 MiB per editable file; analysis disabled above 512 KiB.
- Archives: at most 256 MiB extracted, 20,000 entries, depth 64, normalized unique paths, and a new empty destination.
- Regex: bounded character access, time checks, enumeration/capture limits, and cancellation/interruption, instead of unbounded backtracking or match-metadata allocation. Replace-all is single-pass, validates expansion size before allocation, and applies no partial edit on invalid templates/oversized output.
- Git diff: 1 MiB text/binary threshold and a 256 KiB output buffer; repository metadata cannot disable TLS/redirect policy or use external credential/cookie/certificate files.

**Hard process memory ceilings, CPU-share quotas, and a general native syscall/container policy are not implemented.** Android may kill the isolated process under pressure, but a malicious allocation can still affect device responsiveness before termination. The strict hard-limit setting refuses execution rather than falsely promising a quota. Network/process/filesystem access cannot be enabled for the built-in providers merely by editing run JSON.

## Credentials and network

GitHub tokens are entered only in an in-app password-style dialog. Account verification occurs before replacing an existing stored token. The vault encrypts with AES-GCM using a non-exportable Android Keystore key. Disconnect deletes the ciphertext. Keystore errors are reported, not replaced with plaintext fallback. Tokens briefly exist in trusted process memory to make authorized HTTPS requests; encryption at rest does not make a compromised app process safe.

Credentials are never injected into execution IPC, terminal environments, project files, Git remote URLs, recovery journals, or ZIP backups. Obvious credential assignments are excluded from terminal history and redacted in echoed commands. This is not general-purpose secret scanning: do not type secrets into source/terminals, or store them in a project yourself.

GitHub API requests disable redirects and do not echo response bodies/tokens in errors. Git remotes must be HTTPS, without userinfo/query/fragment or nonstandard ports. GitHub credentials are supplied only when the actual challenged destination is GitHub HTTPS. JGit redirects are disabled for global and URL-specific HTTP configuration. Repository settings cannot turn off TLS verification. External cookie files, custom certificate/key files, and extra HTTP headers are rejected rather than granting repository metadata access to trusted app state. Other HTTPS hosts do not receive the vault token.

All network operations are user-initiated. No analytics, ads, automatic runtime/package installation, or background cloud synchronization is implemented.

## Git is trusted functionality, not repository code execution

The app uses pure-Java JGit. `NoExecFS` disables executable/symlink support and rejects both shell construction and process execution. Native hooks, credential helpers, signing tools, and arbitrary scripts are not run. Repository links, external object/worktree stores, and unsafe external configuration/integration sections are rejected before opening metadata. Worktree-changing operations require deliberate user action and preserve native JGit conflict/error behavior.

Imported metadata is not a permission grant. Private-repository authentication and HTTPS transport may fail for normal server/network reasons; such a failure must not trigger SSH/native-Git fallback.

## Storage, save, and recovery

A workspace rejects absolute paths, traversal outside its root, NUL/backslash paths, and symlink components. Active project files live in app-private storage. SAF import copies selected content into that storage rather than granting executed code a document-tree URI. Individual streams are bounded and staged before publication; an interrupted provider or destination conflict cannot delete/truncate the previous destination. Project shell mutations reject paths owned by open editor buffers.

Writes use same-directory temporary files, file fsync, and atomic rename where supported. Saves verify the last known disk digest and retain a checksummed recovery copy before replacing a file. If a file changed, save refuses; no silent overwrite is performed. Dirty state is revision-based to prevent stale background completions marking newer edits clean. Filesystem/OEM power-loss guarantees vary; this is not a guarantee against hardware corruption.

Recovery is outside project storage. Renames relocate open-buffer journals and breakpoint paths; the old journal is retained if writing its relocated copy fails. Recovery reports and retains damaged journals instead of letting one corrupt entry block healthy entries. Project-filtered recovery bounds the in-memory result set and reports retained overflow. The 600 ms debounce, storage errors, force-stop, abrupt power loss, uninstall, and Android background limits mean the last unflushed edits can still be lost. Keep portable backups. App backup is disabled to avoid accidentally syncing credential/state data.

Trash metadata and contents use separate, generated entry directories; restore cannot overwrite an existing destination. ZIP export excludes Git, trash, dependency/generated trees, and replace-backups. Export/share grants only the selected output URI through `FileProvider`/SAF. Imported projects are not automatically synced to their original external location.

## Reporting and release caveats

Report security issues privately to the repository owner; never post a real token, personal project, or recovery journal in a public issue. Include app version/API level, steps, and redacted logs. Rotate any exposed credentials through the provider immediately.

The current CI debug APK is not a signed production release. Passing tests/build/lint is evidence for those cases, not a penetration test, supply-chain audit, physical-device/OEM certification, or proof that every native/container capability exists. Review licenses, dependency advisories, release signing, and store policies before distribution.
