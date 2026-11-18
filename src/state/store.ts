/**
 * Central IDE store — single source of truth with persistence, autosave,
 * crash recovery and all cross-module actions (req. 19, 22).
 */
import { useSyncExternalStore } from 'react';
import type {
  Breakpoint, BuildRecord, DebugState, Diagnostic, Project, RunConfig,
  Settings, TerminalSession, TermLine, LangId,
} from '../core/types';
import { uid } from '../core/types';
import * as vfs from '../core/vfs';
import * as gitm from '../core/git';
import { languageById, languageForPath } from '../core/languages';
import { runJavaScript, buildHtmlPreview, renderMarkdown, type RunHandle } from '../core/runner';
import { execCommand, type ShellHost } from '../core/shell';
import { loadJSON, saveJSON } from '../core/storage';
import { log } from '../core/logger';
import { seedProjects } from './seed';

export type SidebarPanel = 'explorer' | 'search' | 'git' | 'run' | 'toolchains';
export type BottomTab = 'terminal' | 'output' | 'problems' | 'debug';

export interface AppState {
  ready: boolean;
  projects: Project[];
  activeProjectId: string | null;
  openFiles: string[];
  activeFile: string | null;
  drafts: Record<string, string>;
  dirty: string[];
  sidebarPanel: SidebarPanel;
  sidebarOpen: boolean;
  bottomTab: BottomTab;
  bottomOpen: boolean;
  bottomHeight: number;
  settings: Settings;
  settingsOpen: boolean;
  paletteOpen: boolean;
  previewOpen: boolean;
  previewHtml: string;
  previewTitle: string;
  diagnostics: Diagnostic[];
  breakpoints: Breakpoint[];
  terminals: TerminalSession[];
  activeTerminalId: string;
  builds: BuildRecord[];
  running: { id: string; label: string; file: string } | null;
  debug: DebugState;
  explorerExpanded: string[];
  gitCommitMsg: string;
  newProjectOpen: boolean;
}

export const DEFAULT_SETTINGS: Settings = {
  theme: 'dark', fontSize: 14, fontFamily: "'JetBrains Mono','Fira Code',Consolas,monospace",
  tabSize: 4, useTabs: false, wordWrap: true, minimap: true, autosave: true, formatOnSave: false,
  lineNumbers: true, highlightActiveLine: true, codeFolding: true, autoCloseBrackets: true,
  autocomplete: true, keyboardBar: true, uiScale: 1,
  sandbox: { timeoutMs: 8000, memoryMb: 256, network: false, storage: false },
  gitUser: 'Forge User', gitEmail: 'forge@local.dev',
};

const initialTerminals = (): TerminalSession[] => [{
  id: 'term-1', name: 'Terminal 1', cwd: '/', env: { PATH: '/sandbox/bin', HOME: '/sandbox', USER: 'forge', TERM: 'forge-term', SHELL: '/bin/forge-sh' },
  history: [], createdAt: Date.now(),
  lines: [{ id: 0, text: 'Forge shell 2.1 — sandboxed environment. Type `help` to begin.', kind: 'sys' }],
}];

function loadInitial(): AppState {
  const persisted = loadJSON<Partial<AppState> & { debugWatches?: string[] }>('state', {});
  const projects = (persisted.projects?.length ? persisted.projects : seedProjects()) as Project[];
  return {
    ready: true,
    projects,
    activeProjectId: persisted.activeProjectId && projects.some((p) => p.id === persisted.activeProjectId) ? persisted.activeProjectId : projects[0]?.id ?? null,
    openFiles: persisted.openFiles ?? [],
    activeFile: persisted.activeFile ?? null,
    drafts: persisted.drafts ?? {},
    dirty: persisted.dirty ?? [],
    sidebarPanel: 'explorer',
    sidebarOpen: typeof window !== 'undefined' ? window.innerWidth > 760 : true,
    bottomTab: 'terminal',
    bottomOpen: false,
    bottomHeight: 220,
    settings: { ...DEFAULT_SETTINGS, ...(persisted.settings ?? {}) },
    settingsOpen: false,
    paletteOpen: false,
    previewOpen: false,
    previewHtml: '',
    previewTitle: '',
    diagnostics: [],
    breakpoints: persisted.breakpoints ?? [],
    terminals: persisted.terminals?.length ? persisted.terminals : initialTerminals(),
    activeTerminalId: persisted.activeTerminalId ?? 'term-1',
    builds: [],
    running: null,
    debug: { status: 'idle', callStack: [], watches: persisted.debugWatches ?? [], watchValues: {}, scopes: {} },
    explorerExpanded: persisted.explorerExpanded ?? ['/'],
    gitCommitMsg: '',
    newProjectOpen: false,
  };
}

class Store {
  state: AppState;
  private listeners = new Set<() => void>();
  private persistTimer: ReturnType<typeof setTimeout> | null = null;
  private runHandle: RunHandle | null = null;

  constructor() {
    this.state = typeof window === 'undefined'
      ? ({ ready: false } as AppState)
      : loadInitial();
  }

  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => this.listeners.delete(fn); };
  getSnapshot = () => this.state;

  set(patch: Partial<AppState> | ((s: AppState) => Partial<AppState>)) {
    const p = typeof patch === 'function' ? patch(this.state) : patch;
    this.state = { ...this.state, ...p };
    this.listeners.forEach((l) => l());
    this.schedulePersist();
  }

  private schedulePersist() {
    if (this.persistTimer) clearTimeout(this.persistTimer);
    this.persistTimer = setTimeout(() => {
      const s = this.state;
      saveJSON('state', {
        projects: s.projects, activeProjectId: s.activeProjectId, openFiles: s.openFiles,
        activeFile: s.activeFile, drafts: s.drafts, dirty: s.dirty, settings: s.settings,
        breakpoints: s.breakpoints, terminals: s.terminals, activeTerminalId: s.activeTerminalId,
        explorerExpanded: s.explorerExpanded, debugWatches: s.debug.watches,
      });
    }, 350);
  }

  // ---------- selectors ----------
  project(): Project | null {
    return this.state.projects.find((p) => p.id === this.state.activeProjectId) ?? null;
  }

  activeProject(): Project | null { return this.project(); }

  fileContent(path: string): string {
    return this.state.drafts[path] ?? this.project()?.files[path]?.content ?? '';
  }

  isDirty(path: string): boolean { return this.state.dirty.includes(path); }

  // ---------- project ops ----------
  private applyProject(patch: Partial<Project>) {
    const id = this.state.activeProjectId;
    if (!id) return;
    this.set({
      projects: this.state.projects.map((p) => (p.id === id ? { ...p, ...patch } : p)),
    });
  }

  createProject(name: string, template: 'empty' | 'javascript' | 'web' | 'python'): void {
    const p = seedProjects(1, name, template)[0];
    this.set({
      projects: [...this.state.projects, p],
      activeProjectId: p.id, openFiles: [], activeFile: null, drafts: {}, dirty: [],
      explorerExpanded: ['/'], newProjectOpen: false, diagnostics: [],
    });
    log.info('project', `Created project '${name}' (${template})`);
  }

  switchProject(id: string) {
    if (id === this.state.activeProjectId) return;
    this.saveAll();
    this.set({ activeProjectId: id, openFiles: [], activeFile: null, drafts: {}, dirty: [], diagnostics: [], explorerExpanded: ['/'], debug: { ...this.state.debug, status: 'idle' } });
  }

  deleteProject(id: string) {
    const rest = this.state.projects.filter((p) => p.id !== id);
    this.set({ projects: rest, activeProjectId: rest[0]?.id ?? null, openFiles: [], activeFile: null, drafts: {}, dirty: [] });
  }

  renameProject(id: string, name: string) {
    this.set({ projects: this.state.projects.map((p) => (p.id === id ? { ...p, name } : p)) });
  }

  updateRunConfig(rc: RunConfig) {
    const p = this.project(); if (!p) return;
    const exists = p.runConfigs.some((r) => r.id === rc.id);
    this.applyProject({
      runConfigs: exists ? p.runConfigs.map((r) => (r.id === rc.id ? rc : r)) : [...p.runConfigs, rc],
      activeRunConfigId: rc.id,
    });
  }

  deleteRunConfig(id: string) {
    const p = this.project(); if (!p) return;
    this.applyProject({ runConfigs: p.runConfigs.filter((r) => r.id !== id) });
  }

  // ---------- file ops ----------
  openFile(path: string, line?: number) {
    const p = this.project(); if (!p) return;
    if (!p.files[path]) return;
    const openFiles = this.state.openFiles.includes(path) ? this.state.openFiles : [...this.state.openFiles, path];
    this.set({ openFiles, activeFile: path });
    if (line) setTimeout(() => import('../core/bus').then((m) => m.bus.emit('editor-reveal', { line })), 60);
  }

  closeFile(path: string) {
    if (this.isDirty(path)) this.saveFile(path); // never silently lose code (req. 19)
    const openFiles = this.state.openFiles.filter((f) => f !== path);
    const activeFile = this.state.activeFile === path ? openFiles[openFiles.length - 1] ?? null : this.state.activeFile;
    this.set({ openFiles, activeFile });
  }

  setActiveFile(path: string) { this.set({ activeFile: path }); }

  editActive(content: string) {
    const path = this.state.activeFile;
    if (!path) return;
    const drafts = { ...this.state.drafts, [path]: content };
    const base = this.project()?.files[path]?.content ?? '';
    const dirty = content !== base
      ? [...new Set([...this.state.dirty, path])]
      : this.state.dirty.filter((d) => d !== path);
    this.set({ drafts, dirty });
    if (this.state.settings.autosave) {
      clearTimeout((this as { _as?: ReturnType<typeof setTimeout> })._as);
      (this as { _as?: ReturnType<typeof setTimeout> })._as = setTimeout(() => this.saveFile(path), 1200);
    }
  }

  saveFile(path?: string) {
    const target = path ?? this.state.activeFile;
    if (!target) return;
    const p = this.project(); if (!p) return;
    const draft = this.state.drafts[target];
    if (draft === undefined) return;
    this.applyProject({ files: vfs.writeFile(p, target, draft) });
    const drafts = { ...this.state.drafts };
    delete drafts[target];
    this.set({ drafts, dirty: this.state.dirty.filter((d) => d !== target) });
    if (this.state.settings.formatOnSave) void this.formatFile(target);
    log.debug('save', `Saved ${target}`);
  }

  saveAll() {
    const p = this.project(); if (!p) return;
    let files = p.files;
    for (const [path, content] of Object.entries(this.state.drafts)) {
      files = vfs.writeFile({ ...p, files }, path, content);
    }
    this.applyProject({ files });
    this.set({ drafts: {}, dirty: [] });
    log.info('save', 'All files saved');
  }

  createFile(dir: string, name: string, content = ''): string | null {
    const p = this.project(); if (!p) return null;
    const r = vfs.createFile(p, dir, name, content);
    if ('error' in r) { log.warn('fs', r.error); return null; }
    this.applyProject({ root: r.vfs.root, files: r.vfs.files });
    this.openFile(r.path);
    return r.path;
  }

  createFolder(dir: string, name: string): boolean {
    const p = this.project(); if (!p) return false;
    const r = vfs.createFolder(p, dir, name);
    if ('error' in r) { log.warn('fs', r.error); return false; }
    this.applyProject({ root: r.vfs.root, files: r.vfs.files });
    return true;
  }

  renamePath(path: string, newName: string) {
    const p = this.project(); if (!p) return;
    const r = vfs.renameNode(p, path, newName);
    if ('error' in r) { log.warn('fs', r.error); return; }
    this.applyProject({ root: r.vfs.root, files: r.vfs.files });
    this.remapOpen(r.oldToNew);
  }

  deletePath(path: string) {
    const p = this.project(); if (!p) return;
    const r = vfs.deleteNode(p, path);
    if ('error' in r) { log.warn('fs', r.error); return; }
    this.applyProject({ root: r.vfs.root, files: r.vfs.files });
    const openFiles = this.state.openFiles.filter((f) => !r.removed.includes(f));
    const drafts = { ...this.state.drafts };
    r.removed.forEach((f) => delete drafts[f]);
    this.set({ openFiles, drafts, dirty: this.state.dirty.filter((d) => !r.removed.includes(d)), activeFile: this.state.activeFile && r.removed.includes(this.state.activeFile) ? openFiles[0] ?? null : this.state.activeFile });
  }

  movePath(src: string, destDir: string) {
    const p = this.project(); if (!p) return;
    const r = vfs.moveNode(p, src, destDir);
    if ('error' in r) { log.warn('fs', r.error); return; }
    this.applyProject({ root: r.vfs.root, files: r.vfs.files });
    this.remapOpen(r.oldToNew);
  }

  duplicatePath(path: string) {
    const p = this.project(); if (!p) return;
    const r = vfs.duplicateFile(p, path);
    if ('error' in r) { log.warn('fs', r.error); return; }
    this.applyProject({ root: r.vfs.root, files: r.vfs.files });
    this.openFile(r.path);
  }

  private remapOpen(oldToNew: [string, string][]) {
    if (!oldToNew.length) return;
    const map = new Map(oldToNew);
    const remap = (f: string) => map.get(f) ?? f;
    const drafts: Record<string, string> = {};
    for (const [k, v] of Object.entries(this.state.drafts)) drafts[remap(k)] = v;
    this.set({
      openFiles: this.state.openFiles.map(remap),
      activeFile: this.state.activeFile ? remap(this.state.activeFile) : null,
      drafts, dirty: this.state.dirty.map(remap),
      breakpoints: this.state.breakpoints.map((b) => ({ ...b, file: remap(b.file) })),
    });
  }

  // ---------- diagnostics / lint ----------
  lintFile(path: string) {
    const p = this.project(); if (!p) return;
    const content = this.fileContent(path);
    const lang = languageById(p.files[path]?.language ?? 'plaintext');
    const diags = lang.linter ? lang.linter(content, path) : [];
    this.set({ diagnostics: [...this.state.diagnostics.filter((d) => d.file !== path), ...diags] });
  }

  lintWorkspace() {
    const p = this.project(); if (!p) return;
    const diags: Diagnostic[] = [];
    for (const path of vfs.allFiles(p)) {
      const lang = languageById(p.files[path].language);
      if (lang.linter) diags.push(...lang.linter(this.fileContent(path), path));
    }
    this.set({ diagnostics: diags });
    log.info('lint', `Workspace lint complete: ${diags.length} problem(s)`);
  }

  // ---------- format ----------
  async formatFile(path?: string) {
    const target = path ?? this.state.activeFile;
    const p = this.project(); if (!p || !target) return;
    const lang = languageById(p.files[target]?.language ?? 'plaintext');
    if (!lang.formatter) { this.out(`No formatter available for ${lang.name}`, 'stderr'); return; }
    const src = this.fileContent(target);
    try {
      const formatted = await lang.formatter(src, this.state.settings.tabSize, this.state.settings.useTabs);
      if (this.state.drafts[target] !== undefined) {
        this.set({ drafts: { ...this.state.drafts, [target]: formatted }, dirty: [...new Set([...this.state.dirty, target])] });
      } else {
        this.applyProject({ files: vfs.writeFile(p, target, formatted) });
      }
      this.out(`✨ Formatted ${vfs.basename(target)} with ${lang.name === 'javascript' || lang.name === 'typescript' || lang.name === 'json' || lang.name === 'html' || lang.name === 'css' ? 'Prettier' : 'Forge ' + lang.name + ' formatter'}`, 'system');
      log.info('format', `Formatted ${target}`);
    } catch (e) {
      this.out(`Format failed: ${e instanceof Error ? e.message : String(e)}`, 'stderr');
    }
  }

  // ---------- build / run ----------
  private buildRec(kind: BuildRecord['kind'], file: string): BuildRecord {
    const rec: BuildRecord = { id: uid(), projectId: this.state.activeProjectId ?? '', file, kind, startedAt: Date.now(), status: 'running', output: [] };
    this.set({ builds: [rec, ...this.state.builds].slice(0, 60) });
    return rec;
  }

  private patchBuild(id: string, patch: Partial<BuildRecord>) {
    this.set({ builds: this.state.builds.map((b) => (b.id === id ? { ...b, ...patch } : b)) });
  }

  out(text: string, stream: 'stdout' | 'stderr' | 'system' = 'system') {
    const cur = this.state.builds[0];
    if (cur && cur.status === 'running') {
      this.patchBuild(cur.id, { output: [...cur.output, { stream, text, time: Date.now() }] });
    } else {
      const rec = this.buildRec('run', '—');
      this.patchBuild(rec.id, { output: [{ stream, text, time: Date.now() }], status: 'success', endedAt: Date.now() });
    }
  }

  async runActive(debug = false) {
    const p = this.project(); if (!p) return;
    const rc = p.runConfigs.find((r) => r.id === p.activeRunConfigId);
    const path = this.state.activeFile ?? rc?.file ?? Object.keys(p.files).find((f) => /main|index|app/.test(f)) ?? Object.keys(p.files)[0];
    if (!path) return;
    await this.runFile(path, debug);
  }

  async runFile(path: string, debug = false) {
    const p = this.project(); if (!p) return;
    this.saveAll();
    const proj = this.project()!;
    const file = proj.files[path];
    if (!file) return;
    const lang = languageById(file.language);

    this.set({ bottomOpen: true, bottomTab: debug ? 'debug' : 'output' });
    const rec = this.buildRec(debug ? 'debug' : 'run', path);

    if (lang.run.kind === 'toolchain') {
      this.patchBuild(rec.id, {
        status: 'failed', endedAt: Date.now(), exitCode: 126,
        output: [
          { stream: 'stderr', text: `Cannot execute ${lang.name} locally: ${lang.run.description}`, time: Date.now() },
          { stream: 'system', text: 'Open the Toolchains panel (wrench icon) to see installation options and the on-device capability matrix.', time: Date.now() },
        ],
      });
      return;
    }

    if (lang.id === 'html' || lang.id === 'markdown') {
      this.openPreview(path);
      this.patchBuild(rec.id, { status: 'success', endedAt: Date.now(), exitCode: 0, output: [{ stream: 'system', text: `Opened ${vfs.basename(path)} in the preview sandbox`, time: Date.now() }] });
      return;
    }

    if (lang.id !== 'javascript' && lang.id !== 'typescript' && lang.id !== 'shell' && lang.id !== 'json') {
      this.patchBuild(rec.id, { status: 'failed', endedAt: Date.now(), exitCode: 126, output: [{ stream: 'stderr', text: `${lang.name} is not executable`, time: Date.now() }] });
      return;
    }

    if (lang.id === 'json') {
      const diags = lang.linter ? lang.linter(file.content, path) : [];
      this.patchBuild(rec.id, {
        status: diags.length ? 'failed' : 'success', endedAt: Date.now(), exitCode: diags.length ? 1 : 0,
        output: diags.length
          ? diags.map((d) => ({ stream: 'stderr' as const, text: `${d.line}:${d.col} ${d.message}`, time: Date.now() }))
          : [{ stream: 'system', text: '✓ JSON is valid', time: Date.now() }],
      });
      return;
    }

    if (lang.id === 'shell') {
      const term = this.state.terminals.find((t) => t.id === this.state.activeTerminalId);
      if (term) {
        this.set({ bottomTab: 'terminal' });
        for (const line of file.content.split('\n')) {
          if (line.trim() && !line.trim().startsWith('#')) this.execInTerminal(term.id, line);
        }
      }
      this.patchBuild(rec.id, { status: 'success', endedAt: Date.now(), exitCode: 0, output: [{ stream: 'system', text: 'Script piped to Forge shell', time: Date.now() }] });
      return;
    }

    // JavaScript / TypeScript — real sandboxed execution
    const breakpoints = debug
      ? this.state.breakpoints.filter((b) => b.file === path && b.enabled).map((b) => b.line)
      : [];
    if (debug) this.set({ debug: { ...this.state.debug, status: 'running', file: path, line: undefined, callStack: [] } });

    const started = Date.now();
    this.set({ running: { id: rec.id, label: `Running ${vfs.basename(path)}`, file: path } });
    const handle = runJavaScript({
      code: file.content,
      fileName: path,
      language: lang.id,
      limits: { timeoutMs: this.state.settings.sandbox.timeoutMs, network: this.state.settings.sandbox.network },
      debug,
      breakpoints,
      callbacks: {
        onOutput: (o) => {
          const cur = this.state.builds.find((b) => b.id === rec.id);
          if (!cur) return;
          this.patchBuild(rec.id, { output: [...cur.output, { stream: o.stream, text: o.text, time: Date.now() }] });
        },
        onDebugPause: (info) => {
          this.set({
            debug: {
              ...this.state.debug, status: 'paused', line: info.line,
              callStack: info.stack.map((s) => ({ name: s.name, file: path, line: s.line })),
              scopes: info.globals,
            },
          });
        },
        onDebugResume: () => this.set({ debug: { ...this.state.debug, status: 'running', line: undefined } }),
      },
    });
    this.runHandle = handle;
    const result = await handle.finished;
    this.runHandle = null;
    this.set({ running: null, debug: debug ? { ...this.state.debug, status: 'idle', line: undefined, callStack: [] } : this.state.debug });
    const cur = this.state.builds.find((b) => b.id === rec.id);
    this.patchBuild(rec.id, {
      status: result.exitCode === 0 ? 'success' : 'failed',
      endedAt: Date.now(), exitCode: result.exitCode,
      output: [...(cur?.output ?? []), { stream: 'system', text: `— exited with code ${result.exitCode} in ${result.durationMs}ms (sandbox: worker-isolated, no network) —`, time: Date.now() }],
    });
    log.info('run', `${path} exited ${result.exitCode} in ${result.durationMs}ms`);
  }

  stopRunning() {
    if (this.runHandle) { this.runHandle.kill(); return; }
    if (this.state.builds[0]?.status === 'running') this.patchBuild(this.state.builds[0].id, { status: 'cancelled', endedAt: Date.now(), exitCode: 130 });
    this.set({ running: null });
  }

  debugResume(mode: 'continue' | 'step') { this.runHandle?.resume?.(mode); }

  openPreview(path?: string) {
    const p = this.project(); if (!p) return;
    const target = path ?? this.state.activeFile ?? Object.keys(p.files).find((f) => f.endsWith('.html'));
    if (!target) return;
    const file = p.files[target];
    if (!file) return;
    if (file.language === 'markdown') {
      this.set({ previewOpen: true, previewTitle: vfs.basename(target), previewHtml: `<html><head><style>body{font-family:system-ui;padding:24px;line-height:1.6;color:#1c2230;max-width:760px;margin:auto}pre{background:#f0f2f8;padding:12px;border-radius:8px;overflow:auto}code{background:#eef0f6;padding:2px 5px;border-radius:4px}blockquote{border-left:3px solid #ccd;padding-left:12px;color:#556}</style></head><body>${renderMarkdown(file.content)}</body></html>` });
      return;
    }
    const dir = vfs.dirname(target);
    const resolve = (rel: string): string | undefined => {
      const norm = vfs.normalizePath(vfs.joinPath(dir, rel));
      return p.files[norm]?.content;
    };
    const doc = buildHtmlPreview(file.content, resolve);
    this.set({ previewOpen: true, previewTitle: vfs.basename(target), previewHtml: doc });
  }

  // ---------- git ----------
  applyGit(patch: Partial<Project>) { this.applyProject(patch); }

  gitInit() { const p = this.project(); if (p) this.applyProject({ git: gitm.initRepo(p) }); }
  gitStage(paths: string[]) { const p = this.project(); if (p) this.applyProject({ git: gitm.stage(gitm.ensureGit(p), paths) }); }
  gitUnstage(paths: string[]) { const p = this.project(); if (p) this.applyProject({ git: gitm.unstage(gitm.ensureGit(p), paths) }); }
  gitCommit(message: string): string | null {
    const p = this.project(); if (!p) return 'No project';
    const r = gitm.commit(p, message, `${this.state.settings.gitUser} <${this.state.settings.gitEmail}>`);
    if ('error' in r) return r.error;
    this.applyProject({ git: r.git });
    log.info('git', `commit ${r.commit.id}: ${r.commit.message}`);
    return null;
  }
  gitBranch(name: string): string | null {
    const p = this.project(); if (!p) return 'No project';
    const r = gitm.createBranch(gitm.ensureGit(p), name);
    if ('error' in r) return r.error;
    this.applyProject({ git: r });
    return null;
  }
  gitCheckout(branch: string): string | null {
    const p = this.project(); if (!p) return 'No project';
    this.saveAll();
    const r = gitm.checkout(this.project()!, branch);
    if ('error' in r) return r.error;
    this.applyProject({ git: r.git, files: r.files });
    this.set({ drafts: {}, dirty: [], openFiles: this.state.openFiles.filter((f) => r.files[f]) });
    return null;
  }
  gitStashPush(): string | null {
    const p = this.project(); if (!p) return 'No project';
    this.saveAll();
    const r = gitm.stashPush(this.project()!, 'WIP from UI');
    if ('error' in r) return r.error;
    this.applyProject({ git: r.git, files: r.files });
    this.set({ drafts: {}, dirty: [] });
    return null;
  }
  gitStashPop(): string | null {
    const p = this.project(); if (!p) return 'No project';
    const r = gitm.stashPop(this.project()!);
    if ('error' in r) return r.error;
    this.applyProject({ git: r.git, files: r.files });
    return null;
  }
  gitMerge(branch: string): string | null {
    const p = this.project(); if (!p) return 'No project';
    this.saveAll();
    const r = gitm.merge(this.project()!, branch);
    if ('error' in r) return r.error;
    this.applyProject({ git: r.git, files: r.files });
    if (r.conflicts.length) return `Merged with conflicts in: ${r.conflicts.join(', ')}`;
    return null;
  }

  // ---------- breakpoints / watches ----------
  toggleBreakpoint(file: string, line: number) {
    const existing = this.state.breakpoints.find((b) => b.file === file && b.line === line);
    if (existing) {
      this.set({ breakpoints: this.state.breakpoints.filter((b) => b.id !== existing.id) });
    } else {
      this.set({ breakpoints: [...this.state.breakpoints, { id: uid(), file, line, enabled: true }] });
    }
  }

  setWatches(watches: string[]) { this.set({ debug: { ...this.state.debug, watches } }); }

  // ---------- terminal ----------
  execInTerminal(id: string, input: string) {
    const host = this.shellHost;
    this.set({
      terminals: this.state.terminals.map((t) => {
        if (t.id !== id) return t;
        const sess: TerminalSession = { ...t, env: { ...t.env }, lines: [...t.lines] };
        if (input.trim() === 'clear') {
          sess.lines = [];
          return sess;
        }
        const result = execCommand(sess, input, host);
        sess.history = input.trim() ? [...t.history, input] : t.history;
        let nid = sess.lines.length ? sess.lines[sess.lines.length - 1].id + 1 : 0;
        sess.lines.push({ id: nid++, text: `${promptOf(sess)} ${input}`, kind: 'in' });
        for (const l of result.lines) {
          if (l.text === '__clear__') { sess.lines = []; nid = 0; continue; }
          sess.lines.push({ id: nid++, text: l.text, kind: l.kind });
        }
        if (sess.lines.length > 600) sess.lines = sess.lines.slice(-600);
        return sess;
      }),
    });
  }

  newTerminal() {
    const n = this.state.terminals.length + 1;
    const t: TerminalSession = {
      id: uid(), name: `Terminal ${n}`, cwd: '/', createdAt: Date.now(), history: [],
      env: { PATH: '/sandbox/bin', HOME: '/sandbox', USER: 'forge', TERM: 'forge-term', SHELL: '/bin/forge-sh' },
      lines: [{ id: 0, text: 'Forge shell 2.1 — sandboxed environment. Type `help`.', kind: 'sys' }],
    };
    this.set({ terminals: [...this.state.terminals, t], activeTerminalId: t.id, bottomOpen: true, bottomTab: 'terminal' });
  }

  closeTerminal(id: string) {
    const rest = this.state.terminals.filter((t) => t.id !== id);
    if (!rest.length) { this.set({ terminals: [], activeTerminalId: '' }); return; }
    this.set({ terminals: rest, activeTerminalId: rest[0].id });
  }

  shellHost: ShellHost = {
    getProject: () => store.project()!,
    applyProject: (patch) => store.applyProject(patch),
    runFile: (path) => { void store.runFile(path); },
    openFile: (path) => store.openFile(path),
    formatFile: async (path) => { await store.formatFile(path); },
    lintProject: () => store.lintWorkspace(),
    toast: () => undefined,
  };
}

export function promptOf(t: TerminalSession): string {
  const dir = t.cwd === '/' ? '~' : vfs.basename(t.cwd) || '/';
  return `${t.env.USER || 'forge'}@forge ${dir} $`;
}

export const store = new Store();

export function useApp<T>(selector: (s: AppState) => T): T {
  return useSyncExternalStore(store.subscribe, () => selector(store.getSnapshot()));
}

export { languageForPath };
export type { LangId };
