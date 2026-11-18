/** Core domain types shared across the IDE. */

export type LangId =
  | 'javascript' | 'typescript' | 'python' | 'c' | 'cpp' | 'java'
  | 'html' | 'css' | 'json' | 'xml' | 'markdown' | 'sql' | 'shell' | 'plaintext';

/** How a language can be executed on-device (honest capability model — req. 24). */
export type RunCapability =
  | { kind: 'local'; description: string }              // runs fully on-device now
  | { kind: 'preview'; description: string }            // rendered preview (HTML/CSS)
  | { kind: 'toolchain'; description: string; plugin: string }; // needs a toolchain plugin

export interface FileNode {
  id: string;
  name: string;
  type: 'file' | 'folder';
  children?: FileNode[];
}

export interface ProjectFile {
  path: string;
  content: string;
  language: LangId;
  updatedAt: number;
}

export interface RunConfig {
  id: string;
  name: string;
  file: string;           // entry file
  args: string;
  env: Record<string, string>;
  cwd: string;
  buildCmd: string;
  runCmd: string;
  debugCmd: string;
  language?: LangId;
}

export interface Diagnostic {
  file: string;
  line: number;
  col: number;
  endLine?: number;
  endCol?: number;
  severity: 'error' | 'warning' | 'info';
  message: string;
  source: string;
}

export interface Breakpoint {
  id: string;
  file: string;
  line: number;
  enabled: boolean;
  condition?: string;
}

export interface GitCommit {
  id: string;
  message: string;
  author: string;
  time: number;
  files: Record<string, string>; // path -> content snapshot
  parents: string[];
  branch: string;
}

export interface GitRemote { name: string; url: string; }

export interface GitState {
  initialized: boolean;
  commits: GitCommit[];
  branches: Record<string, string>; // branch -> commit id
  HEAD: string;                     // current branch name
  staged: string[];                 // staged paths
  remotes: GitRemote[];
  stash: { id: string; message: string; time: number; files: Record<string, string> }[];
}

export interface Project {
  id: string;
  name: string;
  createdAt: number;
  root: FileNode;
  files: Record<string, ProjectFile>;
  runConfigs: RunConfig[];
  activeRunConfigId?: string;
  git?: GitState;
  settingsOverride?: Partial<Settings>;
}

export interface Settings {
  theme: 'dark' | 'light' | 'system';
  fontSize: number;
  fontFamily: string;
  tabSize: number;
  useTabs: boolean;
  wordWrap: boolean;
  minimap: boolean;
  autosave: boolean;
  formatOnSave: boolean;
  lineNumbers: boolean;
  highlightActiveLine: boolean;
  codeFolding: boolean;
  autoCloseBrackets: boolean;
  autocomplete: boolean;
  keyboardBar: boolean;
  uiScale: number;
  sandbox: { timeoutMs: number; memoryMb: number; network: boolean; storage: boolean };
  gitUser: string;
  gitEmail: string;
}

export interface BuildRecord {
  id: string;
  projectId: string;
  file: string;
  kind: 'run' | 'build' | 'debug' | 'format' | 'lint' | 'install';
  startedAt: number;
  endedAt?: number;
  exitCode?: number;
  status: 'running' | 'success' | 'failed' | 'cancelled';
  output: { stream: 'stdout' | 'stderr' | 'system'; text: string; time: number }[];
}

export interface TermLine { id: number; text: string; kind: 'in' | 'out' | 'err' | 'sys'; }

export interface TerminalSession {
  id: string;
  name: string;
  cwd: string;
  env: Record<string, string>;
  history: string[];
  lines: TermLine[];
  createdAt: number;
}

export interface DebugState {
  status: 'idle' | 'running' | 'paused';
  file?: string;
  line?: number;
  callStack: { name: string; file: string; line: number }[];
  watches: string[];
  watchValues: Record<string, string>;
  scopes: Record<string, string>;
}

export const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
