# Capability matrix

This document describes the code that is shipped, not a roadmap disguised as installed functionality. The app's **Toolchains & dependencies** screen uses the same conservative boundary.

## Language support

All languages below have offline tokenization/syntax colors, word/keyword completion, native selection/clipboard, search, and editing. Completion is lexical, not type-aware. File contents—not code execution—drive the local intelligence service.

| Language | Additional bundled behavior | Local execution / debugging |
| --- | --- | --- |
| C / C++ | Brace indentation/pairs, comment commands, heuristic declarations/folds | No Clang/GCC, native runtime, GDB, or LLDB installed |
| Java | Brace indentation/pairs, comment commands, heuristic declarations/folds | No Java compiler/runtime provider installed; the app's own Android runtime is not a user-code toolchain |
| JavaScript | Brace indentation/pairs, parser diagnostics/syntax check, heuristic symbols | Interpreted Rhino ES6 in an isolated service; real stepping, conditions, stack/variables, watches |
| TypeScript | JS-family tokenization and TS keywords, lexical declarations | No TS compiler, Node.js, module resolver, or TS language server installed |
| Python | Python tokenization, colon indentation, comments, heuristic declarations/folds | No Python interpreter, pip, debugpy, or semantic provider installed |
| HTML | Markup and embedded CSS/JS colors | Static offline preview when Android WebView is available; otherwise an explicit native unavailable state; scripts stay disabled |
| CSS | CSS tokens, brace indentation | Rendered by the static HTML preview; no Sass/PostCSS toolchain |
| JSON | Bounded validation and layout formatting that preserves string/number lexemes | Data only; duplicate keys, excessive nesting, and non-finite parsed numbers rejected |
| XML | Markup tokens | No schema validator, transformer, or external entity resolution |
| Markdown | Headings, links, emphasis, fenced code colors | Edited as text; no executable notebooks or HTML bridge |
| SQL | SQL keywords, quoted/comment-aware splitting (including trigger bodies) | bundled AndroidX SQLite, in-memory per run; no file database attach or interactive debugger |
| Shell / Bash | Shell tokens/keywords and comments | Shell files are editable, not executed; the project command panel is not Bash |
| Kotlin | Kotlin keywords, brace indentation | No Kotlin compiler/provider installed |

JSON formatting is full layout formatting. For other languages, **Format** only cleans trailing whitespace and reports that limitation. Local declarations and textual references can produce false positives and do not resolve scopes, imports, overloads, or types. Semantic completion, hover, rename, references, and diagnostics require a compatible LSP provider; the framed protocol client is implemented, but no server is installed.

## Workspace and editor

| Area | Available | Boundary |
| --- | --- | --- |
| Documents | Tabs, dirty indicators, retained native text/undo across Activity recreation, shared-document split view, close/save/discard prompts | Maximum 20 open documents; split layout is not a desktop docking system |
| Editing | Undo/redo, comments, line duplication/movement/join, selection, copy/cut/paste, go to line, wrapping, font/indentation settings, symbol key row | Multi-cursor editing and language-aware refactoring are not implemented |
| Search | Literal/regex/whole-word document search, replacement; bounded line-scoped project regex search with linked results | Project search skips generated/dependency/private metadata stores and files larger than 2 MiB; maximum 1,000 UI results |
| Replacement | Preview/confirmation, stale-content checks, original-file backups | Open affected tabs must be closed; multi-file operations are not a filesystem-wide transaction |
| Folding | Fold region calculation, indentation/block guides, a clearly read-only folded overview | Sora's block guides are not editable inline collapsed regions; inline folding is not claimed |
| Large files | UTF-8 text up to 8 MiB; plain large-file mode above 512 KiB avoids whole-file syntax analysis | Not a gigabyte-file editor; execution source has a separate 512 KiB limit |
| Projects | Templates, create/open/switch, file/folder operations, private projects, quick open, session restore, recoverable trash | Renames preserve open-buffer/recovery/breakpoint paths; projects are private copies, not live external SAF folder sync |
| Storage | SAF folder/ZIP import, export ZIP/file, content-URI open/share, checksummed recovery | ZIP backup excludes `.git`, trash, generated build/dependency trees, and replace-backup stores |
| Appearance | Light/dark/system, warm/cool/monochrome syntax palettes, font family/size, UI scaling, optional minimap, resizable panels | Native dialogs follow the Android theme; OEM rendering can vary |

## Execution and terminal

| Area | Available | Boundary |
| --- | --- | --- |
| JavaScript | Real interpreted code, argv, explicit environment object, initial `readLine()` input, stdout/stderr reporting, exit status, deadline/output bounds, cancellation | Rhino ES6 is not full modern browser JS or Node.js. No `require`, `process`, npm, modules, DOM, host filesystem, Java/Android interop, or network |
| SQLite | Real SQL statements and query results, foreign keys, memory-only temporary store, row/cell/output limits | Ephemeral database; no ATTACH/DETACH or project-file I/O; native SQLite memory work is bounded by watchdog, not a hard memory quota |
| Debugging | JS conditional breakpoints (set before launch), initial pause, step into/over/out, continue/pause, variables/stack, watch evaluation and source navigation | JS provider only; debug deadline includes pauses and cannot exceed the effective global/project ceiling. Watch expressions execute in the program's isolated scope and may have side effects |
| Run configs | `.forge/run.json`: file, arguments, environment, working-directory validation, initial stdin, command arrays | Built-in interpreters do not execute arbitrary command arrays. Configured providers must match the file language; cwd must exist but is logical only for bundled interpreters (no filesystem API) |
| Project shell | Up to six sessions; cwd/env, argument parsing, variable expansion, real scoped file commands, history/transcript/search, trusted Git read commands, isolated `run` | Not a PTY/ANSI terminal; no pipes, redirects, arbitrary executables, native job control, package managers, or restorable OS processes |
| Packages/tools | Availability and restrictions shown explicitly; modular provider interfaces | No silent installs, no built-in dependency installer, and no desktop binaries assumed runnable on Android |

## Isolation guarantees and limits

| Requirement | Built-in behavior |
| --- | --- |
| Separate app/code identity | Private non-exported Android `isolatedProcess` service; app permissions and app-private data are not inherited |
| Project access | Only a bounded source copy and explicit launch data cross IPC; no project directory, credential, or host-file authority is granted |
| Network | No inherited INTERNET permission and no JS/SQL network API. The app itself uses HTTPS for user-approved Git/GitHub requests |
| Host process execution | No exposed Java/JNI/native-process APIs or host shell fallback. Trusted Git also has an execution-denying filesystem adapter |
| Time/output | Interpreter checks plus an out-of-interpreter service watchdog and a host deadline; bounded output and one active execution |
| Storage/process quotas | No execution workspace or child-process API is exposed; this is not a configurable native-container filesystem |
| CPU-share/hard memory ceiling | Not available. Strict policy refuses execution. Android can kill the isolated process under resource pressure; a malicious allocation may affect device responsiveness before that happens |
| Immediate stop | Cancel kills the isolated process, unbinds, closes the source pipe, and resolves the run; old run callbacks are prevented from owning the next run |

An isolated interpreter is not equivalent to a rooted Linux container or a proof against Android/interpreter vulnerabilities. See [SECURITY.md](SECURITY.md) before enabling an additional runtime.

## Git and GitHub

Implemented: initialize, status, commit-all, working-tree diff, history/file history, branches, checkout/create, merge, stash/apply, conflict-marker parsing/editing, configure origin, HTTPS clone/fetch/pull/push, GitHub account verification and repository creation.

Not implemented: SSH keys/agent, signed commits, native hooks, LFS processes, credential helpers, Git submodule execution, or native Git. Network operations require user action/consent. GitHub tokens are device-bound and encrypted; they are not exported in ZIPs, terminal environments, run configurations, recovery journals, or debug IPC. Imported repository metadata is treated as untrusted and external object/config integrations are rejected.

## Verification scope

Core behavior has automated JUnit coverage; Android build/lint and API 26/29 phone + API 35 tablet emulator integration are separate CI checks. Device tests cover native editing/recreation/recovery, actual local Git and disabled hooks, Keystore round trips, runtime identity/permission behavior, deadlines, cancel/rerun, SQLite, and strict-policy refusal. Read the result of the **specific commit's CI run** rather than assuming a feature was tested on every phone, tablet, foldable, API level, or OEM ROM.
