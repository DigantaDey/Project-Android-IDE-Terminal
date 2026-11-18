# User guide

## Start and navigate

The **Welcome** project opens a real JavaScript file, not a static demo screen. Tap **▶** / **F5** to run it. The result is collected from an isolated Android service; stopping the service does not close the editor.

- Project title: create, switch, import, export, or clone a project.
- Left rail: explorer, project search, Git, terminal, debugger, settings.
- Top bar: command palette, save-all, run, and more commands.
- Tabs: tap to select; long-press for file actions; × closes with a save/discard prompt when needed.
- Bottom panels: Terminal, Output, Problems, Debugger, Search, Git, Logs. Tap a tab to reopen a hidden panel. Drag the panel divider to resize.
- Explorer folders: tap to expand; long-press for folder operations. Long-press a file for rename/move, duplicate, trash, share, export, and history actions. Explorer renames preserve open buffers, recovery paths, the active tab, and breakpoint conditions; duplication keeps the original intact.
- **Split / unsplit editor** shares the actual document/undo history. Tap a pane to focus it. Split panes do not create independent copies of the file. Delayed formatting/search/replacement results are checked against both document revision and the editor’s bound document; switching or closing a tab cannot redirect an old result into a new buffer.

On Android 8–10, the system status/navigation frame stays dark with light icons for consistent contrast; newer inset controllers follow the editor palette. Narrow windows start with the explorer hidden. Wider windows show it alongside the editor. Orientation and window-size changes recreate the native layout while preserving process-lifetime documents and undo history.

## Editing

Typing uses the native Sora editor, with selection handles, hardware keyboard support, auto-closing pairs, indentation, lexical completion, and line numbers. The accessory row provides coding symbols without switching soft-keyboard layouts.

Use **Settings → Appearance** for theme, syntax palette, font family/size, wrapping, UI scale, and minimap. Editing settings control indentation and automatic pairs. `.forge/settings.json` contains optional project overrides; reopen the project to apply them. Project settings cannot weaken global strict-isolation, time, or output ceilings.

The command palette exposes line comments, duplicate/move/join line, local declarations, symbols, textual references, wrapping/formatting tools, breakpoints, and read-only folded overview. With language-server switches off, lexical navigation is intentionally labeled. Enable a bundled semantic server separately for type-aware completion, hover, definition/references, symbols, formatting and diagnostics where its negotiated capabilities support them. See [the semantic guide](LANGUAGE_SERVERS.md).

Above 512 KiB the app enters large-file mode and skips whole-file lexical analysis. UTF-8 text larger than 8 MiB, binary data, and invalid UTF-8 are not editable. These checks avoid silently corrupting files or blocking the UI on oversized analysis.

### Default shortcuts

| Keys | Action |
| --- | --- |
| Ctrl+Shift+P | Command palette |
| Ctrl+P | Quick open |
| Ctrl+N / Ctrl+W | New file / close tab |
| Ctrl+S | Save all |
| Ctrl+Tab / Ctrl+Shift+Tab | Next / previous tab |
| Ctrl+F / Ctrl+H | Find / replace in document |
| Ctrl+Shift+F | Project search / replace preview |
| Ctrl+G / Ctrl+Shift+O | Go to line / document symbols |
| F12 / Shift+F12 | Local declaration / textual project references |
| Ctrl+Z / Ctrl+Shift+Z | Undo / redo |
| Ctrl+/ / Ctrl+D | Comment lines / duplicate line |
| Alt+Up / Alt+Down | Move line |
| Ctrl+Alt+L | Format JSON / clean trailing whitespace |
| Ctrl+\ | Split / unsplit |
| Ctrl+` | Toggle terminal |
| F5 / Shift+F5 | Run / debug current JS file |
| Ctrl+Shift+B | Build availability / JavaScript syntax check |
| F9 / Shift+F2 | Toggle breakpoint / stop |
| Ctrl+, | Settings |

**Settings → Keyboard shortcuts** stores JSON overrides by command ID. Defaults and palette actions use the same command registry.

## Search and safe replacement

Document find supports literal/regex, case sensitivity, whole words, next/previous, replace, and replace-all. Regex evaluation has a work/time budget; enumerated matches/captures also have memory limits (20,000 enumerated results, 100 capture groups, and bounded captured text). Counting and replace-all stream rather than allocating every match. Oversized replacements are rejected before changing the document; pathological patterns fail instead of scanning forever. Java regex replacement syntax supports `$1` and `${name}`; escape a literal dollar with a backslash.

Project search is line-scoped and bounded. Results link to source. It skips private tool metadata, Git, trash, dependency/build trees, binary/non-UTF-8 files, and files larger than 2 MiB. The UI shows at most 1,000 matches, so replacement only affects the previewed results.

Project replacement requires confirmation and closed affected tabs. Each file’s previewed ranges and replacement templates are revalidated against the original line context, constructed in a bounded forward pass, digest-checked before writing, and backed up in `.forge/replace-backups`. Replacement is atomic per file, **not across the whole project**. If cancelled or a later file changes, inspect the error and retained backups before retrying.

## Projects, Android storage, and recovery

All active projects are under app-private storage. Importing a folder through Android's document picker copies it into a private project. Subsequent edits do **not** synchronize back to the selected folder. Use export/share to write out a deliberate copy.

Individual provider-file imports are staged and published only after the bounded stream succeeds; failures and conflicting destinations do not truncate or delete an existing file. ZIP import has traversal, duplicate-entry, depth, entry-count, and extracted-size limits. It only imports into a new empty project. ZIP export is a source/project backup, not a Git mirror or dependency cache backup: `.git`, trash, generated build/dependency trees, and replace-backup trees are excluded.

Files moved to **project trash** retain restoration metadata separate from their original names. Use **Restore deleted file** in the palette. Restore refuses to overwrite a new file at the old path.

Autosave is configurable (15 seconds by default). Independently, edits schedule a recovery journal after a short debounce (600 ms). Recovery journals live outside projects and contain checksums, the last known disk digest, and the unsaved text. Failed/conflicting saves preserve recovery data and report the error; they do not silently replace an externally changed file.

Recovery is automatic when a project is reopened. A damaged journal is reported and retained, while healthy journals continue to recover. The newest journals are restored within the 20-tab limit; overflow remains on disk with a warning. Save/close restored tabs and reopen the project to recover remaining entries. **Storage & recovery** lists journal/storage status. Export important work regularly: a device reset, uninstall, storage failure, or Android kill before a journal flush can still lose work. Recovery is not a cloud backup.

## Running JavaScript (isolated Rhino)

The default JavaScript provider is Rhino. If you install the optional companion
and enable Node.js, ordinary **Run current file** selects Node instead. The
`javascript` run-configuration provider and the existing debugger still select
Rhino explicitly; the `node` provider selects real Node.js. These are different
runtimes, not interchangeable standard libraries.

Available globals: `print`, `console.log/info/warn/error/debug`, `arguments`, `env`, and `readLine()`. Standard safe Rhino ES6 objects are available. Node.js/browser/Android/Java APIs are not.

```javascript
const name = arguments[0] || 'developer';
print('Hello, ' + name);
print('Setting:', env.MODE);
print('Input:', readLine());
```

**Run project configuration** uses the `file` and `toolchain` fields and refuses unavailable or language-mismatched providers instead of silently substituting one. **Run current file** runs the selected file and uses `.forge/run.json` for launch options. The first enabled compatible provider is chosen for the current file's language (native Node takes priority when enabled). Example:

```json
{
  "name": "Example",
  "toolchain": "javascript",
  "file": "main.js",
  "workingDirectory": "",
  "arguments": ["developer"],
  "environment": {"MODE": "offline"},
  "stdin": "hello\n",
  "buildCommand": [],
  "runCommand": [],
  "debugCommand": []
}
```

Do not put credentials in source, environment, or launch JSON. Run data is intentionally visible to the program. Terminal `run path.js arg1 …` passes its session arguments, environment, and cwd to the launch. Working directory must name an existing project-relative directory, but the bundled interpreters expose no filesystem API. Non-empty command arrays require another compatible provider and are refused by the built-ins.

Execution defaults to 10 seconds and 256 KiB of output, configurable within safe ranges. Debugging obeys the same effective global/project time and output ceilings, **including pauses**. Increase the global execution timeout (up to 60 seconds) explicitly when you need longer debug sessions; debugging cannot bypass a stricter project/global policy. The service watchdog also stops work outside interpreter checks. Exit codes include 0 (success), 1 (program/runtime error), 124 (deadline/output limit), 130 (user cancellation), and 137 (service/Android termination). Read the accompanying message; Android may terminate an isolated process under resource pressure.

## Static HTML/CSS preview

Run an HTML file to view its local, offline static preview. Scripts, external requests, file/content URLs, forms and native bridges stay disabled. This uses Android’s system WebView, not a bundled browser. If the device has no enabled/usable WebView provider, a native error screen explains how to enable or update Android System WebView and return to the editor; Forge never silently enables downloads or opens a less-confined external browser. A renderer failure is also recoverable without closing your project.

## Running SQL

Create/open a `.sql` file and choose Run. Each run creates a **new in-memory SQLite database**:

```sql
CREATE TABLE sample (id INTEGER PRIMARY KEY, name TEXT);
INSERT INTO sample (name) VALUES ('Forge');
SELECT * FROM sample;
```

Statements, quoted semicolons, comments, and trigger bodies are split without invoking a shell. Queries print column headings and bounded rows/cells. `ATTACH`/`DETACH` are refused. Database changes do not survive the run. No interactive SQL debugger is installed.

## Debugging JavaScript

Set breakpoints at the cursor (F9), optionally add conditions, then choose **Debug current JavaScript file**. The debugger initially pauses on an executable line. The panel provides continue, pause, step into/over/out, watch evaluation, and stop. Stack/variable snapshots are bounded; source navigation selects the stopped line, including after Activity recreation. Resume events show running rather than leaving a stale paused label.

Breakpoint definitions are passed at launch; restart to apply changed definitions. Watch expressions run inside the isolated program and can have side effects. Do not use watches as a credential store. If the process dies, the editor/recovery state remains separate from the program.

## Project shell

The Terminal panel has up to six persistent sessions. It supports quoted arguments, `$NAME` expansion, `export`/`unset`, cwd, history, output search/selection, and real project-scoped commands:

```text
help pwd cd ls cat echo touch mkdir cp mv rm find head tail grep wc env export unset clear history
```

`run` launches through the isolated provider. Read-only Git commands use the IDE's trusted Git service. State-changing/network Git operations use the Git UI so consent remains visible. Unsupported commands return an error, never host-shell execution.

The scoped project shell is **not a PTY**: pipes, redirects, native jobs and package managers are unavailable in that panel. Use the separate opt-in native Bash window below for those capabilities. `rm` moves files to recoverable project trash. Shell mutations refuse paths backing an open editor buffer; close affected tabs first or use the explorer to rename an open document safely. Cwd/history/output persist; native processes cannot be restored. Terminal environment values are session-local and are not written into the persisted history state. Treat transcripts as project data, not a password manager.

## Native compilers, Python, Node/npm and Bash

Install **both** APKs from one successful offline bundle, then open **Settings →
Toolchains & native runtime switches**. Clang, CPython, Node.js, npm and Bash each
have a global on/off control; all default off. npm also needs Node. Disabling a
tool stops the active native session and blocks its Forge launcher/normal command
discovery; it does not uninstall bundled files or prohibit absolute paths and
interpreters implemented by enabled native code.

Run a `.c`/`.cpp`, `.py`, `.js`, or `.sh` file with the corresponding tool enabled.
Clang compiles and executes real C/C++; Python and Node expose their real standard
libraries. These tools receive a bounded disposable project copy in a **separate
Android app UID**, never the IDE's live project, vault or storage grants. Ordinary
project files may themselves contain secrets: review them before running code.
There is no automatic write-back. File-run copies disappear at exit.

Choose **Open native Bash PTY** from the palette, terminal toolbar or tool settings
for an ANSI terminal with pipelines, redirects, interactive input and native job
control. Use its **^C** button to interrupt a foreground command. The terminal
retains its state across rotation while the IDE process lives. **Back to editor**
leaves it running; reopen the native terminal to return. **Stop & discard** kills
its jobs and removes the project copy. Before stopping, use **terminal menu →
Export project copy as ZIP…** if you want to preserve native-generated files.
Interrupt mutating commands before exporting, and check export succeeded.

The native **network** and **npm install scripts** controls are separate and
default off. `npm install` is an explicit package-install action; offline local
packages need no connection, but registry downloads require network consent.
Package scripts are arbitrary code and should remain off for untrusted packages.
Bash/native code is programmable, so the per-tool/script flags are consent and
launch policy, not separate language-level security containers.

The runtime companion deliberately uses legacy target-28 execution compatibility;
the IDE stays target 35. This is a sideloaded developer bundle, not a Play-ready
app. Only primary-user arm64-v8a/x86_64 installations are supported. Java SDK,
pip, extra build systems, Java/Kotlin/etc. semantic servers and DAP/debug servers are not bundled. TypeScript and seven offline language servers now ship in the companion; enable their independent switches under **Bundled semantic language servers**. See [versions, safe edits, lifecycle and boundaries](LANGUAGE_SERVERS.md).
See [installation, storage sizes and the native threat model](NATIVE_RUNTIME.md).

## Git / GitHub

The Git panel supports initialize, commit-all, status/diff, history, branches/checkout/create, merge, stash/apply, origin configuration, and explicit HTTPS sync. Save/close/reload as prompted around worktree changes. Merge conflicts remain editable in files; use the conflict actions, inspect the result, and commit when resolved.

Connect GitHub **inside the app's secure credential dialog**, never in code or chat. Use a fine-grained token limited to the intended repositories/permissions; account verification and repository creation require the appropriate GitHub access. Verification happens before replacing the stored token. Disconnect deletes the encrypted stored value. Tokens are device-bound and excluded from project exports.

Only HTTPS remotes are accepted. Redirects, credential-bearing URLs, SSH/native hooks, external object stores, and unsafe repository integrations are rejected. The editor does not promise anonymous access to private remotes or that every server supports the chosen Java Git transport.

## Troubleshooting

- **Native tool off/missing:** install both APKs from the same offline bundle and enable it in Settings → Toolchains & native runtime switches. Allow internal space for the first verified unpack. Desktop binaries are not assumed Android-compatible; nothing is silently installed/downloaded.
- **Strict policy blocked execution:** Android's built-in provider cannot enforce hard memory/CPU-share quotas. Leave strict mode on for untrusted workloads that require those guarantees.
- **File changed on disk:** keep the tab/recovery copy, inspect the disk version, then save a separate copy or explicitly reload. Do not retry by deleting the recovery journal.
- **Sandbox stopped:** inspect Output/Logs for deadline, output, Android memory pressure, or service errors. Reduce workload, then rerun.
- **Git rejected config:** imported metadata may request host execution/files. Remove unsupported integrations in a separately trusted environment; Forge will not relax its process boundary.
- **Storage/provider error:** retain private/recovery copies and retry an explicit export after checking free space/document-provider access.
