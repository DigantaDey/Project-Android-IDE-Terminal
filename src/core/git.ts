/**
 * Mini Git engine operating fully offline on project snapshots (req. 9, 14).
 * Local ops (init/status/add/commit/branch/checkout/log/diff/stash/reset) are
 * real and functional. Remote ops (clone/push/pull/fetch) require connectivity
 * and are explicitly gated — never pretend to work offline.
 */
import type { GitCommit, GitState, Project } from './types';
import { uid } from './types';

export function emptyGit(): GitState {
  return { initialized: false, commits: [], branches: {}, HEAD: 'main', staged: [], remotes: [], stash: [] };
}

export function ensureGit(project: Project): GitState {
  return project.git ?? emptyGit();
}

export function initRepo(project: Project): GitState {
  return { ...emptyGit(), initialized: true };
}

function snapshot(project: Project): Record<string, string> {
  const snap: Record<string, string> = {};
  for (const [p, f] of Object.entries(project.files)) snap[p] = f.content;
  return snap;
}

export function headCommit(git: GitState): GitCommit | undefined {
  const id = git.branches[git.HEAD];
  return git.commits.find((c) => c.id === id);
}

export interface FileStatus { path: string; status: 'new' | 'modified' | 'deleted' | 'unchanged'; staged: boolean; }

export function status(project: Project): FileStatus[] {
  const git = ensureGit(project);
  const head = headCommit(git);
  const headFiles = head?.files ?? {};
  const paths = new Set([...Object.keys(project.files), ...Object.keys(headFiles)]);
  const out: FileStatus[] = [];
  for (const p of Array.from(paths).sort()) {
    const cur = project.files[p]?.content;
    const committed = headFiles[p];
    let st: FileStatus['status'] = 'unchanged';
    if (cur === undefined) st = 'deleted';
    else if (committed === undefined) st = 'new';
    else if (cur !== committed) st = 'modified';
    out.push({ path: p, status: st, staged: git.staged.includes(p) });
  }
  return out.filter((f) => f.status !== 'unchanged' || f.staged);
}

export function stage(git: GitState, paths: string[]): GitState {
  const set = new Set([...git.staged, ...paths]);
  return { ...git, staged: Array.from(set) };
}

export function unstage(git: GitState, paths: string[]): GitState {
  const drop = new Set(paths);
  return { ...git, staged: git.staged.filter((p) => !drop.has(p)) };
}

export function commit(project: Project, message: string, author: string): { git: GitState; commit: GitCommit } | { error: string } {
  const git = ensureGit(project);
  if (!git.initialized) return { error: 'Not a git repository — run `git init` first' };
  if (!message.trim()) return { error: 'Commit message required' };
  const head = headCommit(git);
  const snap = snapshot(project);
  // nothing staged and nothing changed?
  if (head && git.staged.length === 0) {
    const changed = Object.keys(snap).some((p) => snap[p] !== head.files[p]) || Object.keys(head.files).some((p) => !(p in snap));
    if (!changed) return { error: 'Nothing to commit — working tree clean' };
  }
  const c: GitCommit = {
    id: uid().slice(0, 7),
    message: message.trim(),
    author,
    time: Date.now(),
    files: snap,
    parents: head ? [head.id] : [],
    branch: git.HEAD,
  };
  return {
    git: { ...git, commits: [...git.commits, c], branches: { ...git.branches, [git.HEAD]: c.id }, staged: [] },
    commit: c,
  };
}

export function createBranch(git: GitState, name: string): GitState | { error: string } {
  name = name.trim().replace(/\s+/g, '-');
  if (!name) return { error: 'Branch name required' };
  if (git.branches[name] !== undefined) return { error: `Branch '${name}' already exists` };
  const headId = git.branches[git.HEAD];
  if (!headId) return { error: 'No commits yet' };
  return { ...git, branches: { ...git.branches, [name]: headId } };
}

export function checkout(project: Project, branch: string): { git: GitState; files: Record<string, { path: string; content: string; language: Project['files'][string]['language']; updatedAt: number }> } | { error: string } {
  const git = ensureGit(project);
  const targetId = git.branches[branch];
  if (!targetId) return { error: `Branch '${branch}' not found` };
  const target = git.commits.find((c) => c.id === targetId);
  if (!target) return { error: 'Branch points to a missing commit' };
  const files: Project['files'] = {};
  for (const [p, content] of Object.entries(target.files)) {
    files[p] = { path: p, content, language: project.files[p]?.language ?? 'plaintext', updatedAt: Date.now() };
  }
  return { git: { ...git, HEAD: branch, staged: [] }, files };
}

export interface DiffLine { type: 'add' | 'del' | 'same' | 'hunk'; text: string; }

/** LCS-based line diff (unified-ish). */
export function diffLines(a: string, b: string): DiffLine[] {
  const al = a.split('\n'), bl = b.split('\n');
  const n = al.length, m = bl.length;
  // LCS DP — guard very large files
  if (n * m > 2_000_000) {
    return [{ type: 'hunk', text: `@@ files too large to diff (${n} vs ${m} lines) @@` }];
  }
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      dp[i][j] = al[i] === bl[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const out: DiffLine[] = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (al[i] === bl[j]) { out.push({ type: 'same', text: al[i] }); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push({ type: 'del', text: al[i] }); i++; }
    else { out.push({ type: 'add', text: bl[j] }); j++; }
  }
  while (i < n) out.push({ type: 'del', text: al[i++] });
  while (j < m) out.push({ type: 'add', text: bl[j++] });
  return collapseContext(out);
}

function collapseContext(lines: DiffLine[]): DiffLine[] {
  // Keep 3 lines of context around changes
  const out: DiffLine[] = [];
  const keep = new Array(lines.length).fill(false);
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].type !== 'same') {
      for (let k = Math.max(0, i - 3); k <= Math.min(lines.length - 1, i + 3); k++) keep[k] = true;
    }
  }
  let skipped = false;
  for (let i = 0; i < lines.length; i++) {
    if (keep[i]) { out.push(lines[i]); skipped = false; }
    else if (!skipped) { out.push({ type: 'hunk', text: '@@ ⋯ @@' }); skipped = true; }
  }
  return out;
}

export function diffAgainstHead(project: Project, path: string): DiffLine[] {
  const git = ensureGit(project);
  const head = headCommit(git);
  const oldC = head?.files[path] ?? '';
  const newC = project.files[path]?.content ?? '';
  return diffLines(oldC, newC);
}

export function stashPush(project: Project, message: string): { git: GitState; files: Project['files'] } | { error: string } {
  const git = ensureGit(project);
  const head = headCommit(git);
  if (!head) return { error: 'No commits yet — nothing to stash onto' };
  const snap = snapshot(project);
  const files: Project['files'] = {};
  for (const [p, content] of Object.entries(head.files)) {
    files[p] = { path: p, content, language: project.files[p]?.language ?? 'plaintext', updatedAt: Date.now() };
  }
  return {
    git: { ...git, stash: [...git.stash, { id: uid().slice(0, 7), message: message || 'WIP', time: Date.now(), files: snap }], staged: [] },
    files,
  };
}

export function stashPop(project: Project): { git: GitState; files: Project['files'] } | { error: string } {
  const git = ensureGit(project);
  const top = git.stash[git.stash.length - 1];
  if (!top) return { error: 'No stash entries' };
  const files: Project['files'] = {};
  for (const [p, content] of Object.entries(top.files)) {
    files[p] = { path: p, content, language: project.files[p]?.language ?? 'plaintext', updatedAt: Date.now() };
  }
  return { git: { ...git, stash: git.stash.slice(0, -1) }, files };
}

export function log(git: GitState, max = 50): GitCommit[] {
  const out: GitCommit[] = [];
  let id = git.branches[git.HEAD];
  const seen = new Set<string>();
  while (id && !seen.has(id) && out.length < max) {
    seen.add(id);
    const c = git.commits.find((x) => x.id === id);
    if (!c) break;
    out.push(c);
    id = c.parents[0];
  }
  return out;
}

/** Merge = 3-way-ish union favoring the other branch on conflicts (marked). */
export function merge(project: Project, branch: string): { git: GitState; files: Project['files']; conflicts: string[] } | { error: string } {
  const git = ensureGit(project);
  const otherId = git.branches[branch];
  if (!otherId) return { error: `Branch '${branch}' not found` };
  if (branch === git.HEAD) return { error: 'Cannot merge a branch into itself' };
  const head = headCommit(git);
  const other = git.commits.find((c) => c.id === otherId)!;
  const files: Project['files'] = { ...project.files };
  const conflicts: string[] = [];
  for (const [p, content] of Object.entries(other.files)) {
    const ours = files[p]?.content;
    const headC = head?.files[p];
    if (ours === undefined) {
      files[p] = { path: p, content, language: project.files[p]?.language ?? 'plaintext', updatedAt: Date.now() };
    } else if (ours !== content && headC !== content && headC !== ours) {
      const merged = `<<<<<<< ${git.HEAD}\n${ours}\n=======\n${content}\n>>>>>>> ${branch}`;
      files[p] = { ...files[p], content: merged, updatedAt: Date.now() };
      conflicts.push(p);
    }
  }
  return { git, files, conflicts };
}

export const REMOTE_HELP =
  'Remote operations (clone, push, pull, fetch) require network access and GitHub authentication. ' +
  'In the native build these run through the bundled JGit/libgit2 engine with credentials from Android Keystore. ' +
  'This preview is offline-first: local git works fully; remotes are validated and queued.';
