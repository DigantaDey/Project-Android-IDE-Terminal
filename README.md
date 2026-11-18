# Forge — Android IDE & Terminal

A professional, offline-first IDE engineered for Android phones, tablets and foldables —
code editor, project workspace, integrated terminal, secure execution sandbox, debugger,
Git, and modular language toolchains in one clean interface.

> This repository contains the full IDE application (React + TypeScript + CodeMirror 6),
> built as a modular, extensible platform. It runs in the browser as a faithful
> implementation of the Android build's architecture: the same module boundaries
> (editor / VFS / language services / sandbox / shell / git) map 1:1 onto native
> components (Kotlin + JNI toolchains) without restructuring.

## Quick start

```bash
npm install
npm run dev        # http://localhost:5173
npm run build      # production bundle
npm test           # core engine unit tests
```

## Feature map

| Area | Status |
| --- | --- |
| Languages | C, C++, Java, JS, TS, Python, HTML, CSS, JSON, XML, Markdown, SQL, Shell — highlighting, completion, linting, folding, formatting |
| Editor | Tabs, minimap, breakpoints gutter, find/replace, folding, autosave, crash-safe drafts |
| Projects | Create / import (.zip) / export (.zip) / rename / move / duplicate, project-wide regex search & replace |
| Run | JS/TS execute in a **secure worker sandbox** (no DOM, no network, time-limited); HTML/CSS/MD live preview sandbox |
| Debug | Breakpoints, step, continue, call stack, scope inspection, watches (JS/TS) |
| Terminal | Multi-session Forge shell over the project VFS: `ls cd cat echo grep git node run …` with redirection |
| Git | init/status/add/commit/branch/checkout/log/diff/stash/merge — fully offline; remotes gated & honest |
| Toolchains | Pyodide / clang / ECJ+ART / SQLite / Node bridge documented as plugins with capability matrix |

## On-device execution honesty (req. 24)

Not every desktop toolchain runs natively on Android. Forge models each language's
execution capability explicitly (`local` / `preview` / `toolchain plugin`) instead of
pretending. Attempting to run e.g. C produces a clear toolchain state, not a fake result.

## Architecture

```
src/
├── core/          pure engines — no React
│   ├── vfs.ts        project file tree + search/replace
│   ├── languages.ts  extensible language registry (highlight/lint/format/run)
│   ├── runner.ts     worker sandbox + TS strip + debug instrumentation
│   ├── shell.ts      forge-sh command interpreter
│   ├── git.ts        offline mini-git (snapshots, diff, stash, merge)
│   ├── storage.ts    persistence with corruption guard
│   └── logger.ts     ring-buffer diagnostics
├── state/         central store (persistence, autosave, recovery) + seed projects
├── editor/        CodeMirror surface + canvas minimap
└── ui/            app bar, rail, panels, bottom panel, palette, settings, preview
```

New languages register a single `LanguageDef`; new toolchains implement one execution
interface. The core IDE never needs restructuring.

## Security model

1. IDE state & credentials — isolated storage, never exposed to executed code
2. Project VFS — the only filesystem the shell/tools can see
3. Sandbox worker — no DOM, no network (stubbed), hard time limit, instant kill
4. Preview iframe — `sandbox="allow-scripts"`, no same-origin access
