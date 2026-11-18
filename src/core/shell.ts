/**
 * Forge shell — a POSIX-flavoured command interpreter over the project VFS
 * (req. 5). Runs entirely inside the sandbox boundary: it can only touch the
 * active project's virtual filesystem, never the host device.
 */
import type { GitState, Project, TerminalSession } from './types';
import * as vfs from './vfs';
import * as git from './git';
import { languageForPath, languageById } from './languages';

export interface ShellHost {
  getProject(): Project;
  applyProject(patch: Partial<Project>): void;
  runFile(path: string): void;
  openFile(path: string): void;
  formatFile(path: string): Promise<void>;
  lintProject(): void;
  toast(text: string, kind?: 'info' | 'error' | 'success'): void;
}

export interface ExecResult { lines: { text: string; kind: 'out' | 'err' | 'sys' }[]; }

const TOOLCHAIN_BINS: Record<string, string> = {
  gcc: 'C compilation requires the clang toolchain plugin (WASM/NDK). See Toolchains panel.',
  'g++': 'C++ compilation requires the clang toolchain plugin (WASM/NDK). See Toolchains panel.',
  clang: 'C/C++ compilation requires the clang toolchain plugin. See Toolchains panel.',
  javac: 'Java compilation requires the ECJ+ART toolchain plugin. See Toolchains panel.',
  java: 'Java execution requires the ART runtime plugin. See Toolchains panel.',
  python3: 'Python requires the Pyodide WASM toolchain plugin. See Toolchains panel.',
  pip: 'Package management requires the matching toolchain plugin (pip arrives with Pyodide).',
  npm: 'npm requires the Node toolchain plugin. JavaScript itself runs natively via `node <file>` here.',
  gradle: 'Gradle builds require the Android build toolchain plugin.',
  mvn: 'Maven requires the JVM toolchain plugin.',
  make: 'Make requires the build-essential toolchain plugin.',
  cmake: 'CMake requires the native build toolchain plugin.',
};

const HELP = `Forge shell — sandboxed command environment
  Files:      ls cd pwd cat touch mkdir rm mv cp head tail wc find grep write
  Code:       run <file>   node <file.js>   open <file>   format <file>   lint
  Git:        git init|status|add|commit|branch|checkout|log|diff|stash|merge|remote
  Session:    env export history clear date whoami echo neofetch help exit
Redirection:  echo "text" > file.txt      command >> file.txt
All paths are confined to the active project sandbox.`;

export function execCommand(session: TerminalSession, input: string, host: ShellHost): ExecResult {
  const out: ExecResult['lines'] = [];
  const say = (text: string, kind: 'out' | 'err' | 'sys' = 'out') => out.push({ text, kind });
  const project = host.getProject();

  let line = input.trim();
  if (!line) return { lines: [] };

  // redirect parsing: cmd > file | cmd >> file
  let redirect: { path: string; append: boolean } | null = null;
  const redir = /\s*(>>|>)\s*(\S+)\s*$/.exec(line);
  if (redir && !line.startsWith('echo') && !/^cat\s*$/.test(line)) {
    redirect = { path: redir[2], append: redir[1] === '>>' };
    line = line.slice(0, redir.index);
  } else if (redir && line.startsWith('echo')) {
    redirect = { path: redir[2], append: redir[1] === '>>' };
    line = line.slice(0, redir.index);
  }

  const tokens = tokenize(line);
  const cmd = tokens[0]?.toLowerCase();
  const args = tokens.slice(1).map((a) => expandEnv(a, session.env));

  const resolve = (p: string) => vfs.normalizePath(p.startsWith('/') ? p : vfs.joinPath(session.cwd, p));

  try {
    switch (cmd) {
      case 'help': case '?': HELP.split('\n').forEach((l) => say(l)); break;
      case 'clear': say('__clear__', 'sys'); break;
      case 'exit': say('Terminal sessions are managed by the IDE — use the tab bar to close.', 'sys'); break;
      case 'pwd': say(session.cwd); break;
      case 'whoami': say(session.env.USER || 'forge'); break;
      case 'date': say(new Date().toString()); break;
      case 'history': session.history.slice(-30).forEach((h, i) => say(`  ${session.history.length - Math.min(30, session.history.length) + i + 1}  ${h}`)); break;
      case 'env': Object.entries(session.env).sort().forEach(([k, v]) => say(`${k}=${v}`)); break;
      case 'export': {
        for (const a of args) {
          const eq = a.indexOf('=');
          if (eq > 0) { session.env[a.slice(0, eq)] = a.slice(eq + 1); say(`export ${a.slice(0, eq)}`, 'sys'); }
        }
        break;
      }
      case 'echo': {
        const text = tokens.slice(1).join(' ').replace(/^["']|["']$/g, '');
        if (redirect) writeToFile(resolve(redirect.path), (redirect.append ? readFile(resolve(redirect.path)) : '') + expandEnv(text, session.env) + '\n');
        else say(expandEnv(text, session.env));
        break;
      }
      case 'neofetch': {
        const files = Object.keys(project.files).length;
        say('      ▄▄▄▄▄      forge@android', 'sys');
        say('    ▄▄▄▄▄▄▄▄▄    ─────────────────────', 'sys');
        say('    ▄▄▄▄▄▄▄▄▄    OS: Forge Sandbox 1.0 (Android)', 'sys');
        say('    ▄▄▄▄▄▄▄▄▄    Shell: forge-sh 2.1', 'sys');
        say('      ▀▀▀▀▀      Project: ' + project.name, 'sys');
        say(`                 Files: ${files} · JS runtime: worker-jit`, 'sys');
        break;
      }
      case 'ls': {
        const dir = args[0] ? resolve(args[0]) : session.cwd;
        const items = vfs.listDir(project, dir);
        if (!vfs.exists(project, dir)) { say(`ls: ${dir}: No such directory`, 'err'); break; }
        if (items.length === 0) { say('(empty)', 'sys'); break; }
        items.forEach((n) => say(n.type === 'folder' ? n.name + '/' : n.name));
        break;
      }
      case 'cd': {
        const dir = args[0] ? resolve(args[0]) : '/';
        const node = vfs.nodeAt(project, dir);
        if (!node || node.type !== 'folder') { say(`cd: ${dir}: Not a directory`, 'err'); break; }
        session.cwd = dir;
        break;
      }
      case 'cat': case 'head': case 'tail': case 'wc': {
        if (!args[0]) { say(`${cmd}: missing file operand`, 'err'); break; }
        const p = resolve(args[0]);
        const f = project.files[p];
        if (!f) { say(`${cmd}: ${p}: No such file`, 'err'); break; }
        const lines = f.content.split('\n');
        if (cmd === 'wc') say(`${lines.length} ${f.content.split(/\s+/).filter(Boolean).length} ${f.content.length} ${vfs.basename(p)}`);
        else if (cmd === 'head') lines.slice(0, 10).forEach((l) => say(l));
        else if (cmd === 'tail') lines.slice(-10).forEach((l) => say(l));
        else lines.forEach((l) => say(l));
        break;
      }
      case 'touch': {
        if (!args[0]) { say('touch: missing operand', 'err'); break; }
        const p = resolve(args[0]);
        if (vfs.exists(project, p)) break;
        const r = vfs.createFile(project, vfs.dirname(p), vfs.basename(p));
        if ('error' in r) say(`touch: ${r.error}`, 'err');
        else host.applyProject({ root: r.vfs.root, files: r.vfs.files });
        break;
      }
      case 'mkdir': {
        if (!args[0]) { say('mkdir: missing operand', 'err'); break; }
        const p = resolve(args[0]);
        const r = vfs.createFolder(project, vfs.dirname(p), vfs.basename(p));
        if ('error' in r) say(`mkdir: ${r.error}`, 'err');
        else host.applyProject({ root: r.vfs.root, files: r.vfs.files });
        break;
      }
      case 'rm': {
        const target = args.find((a) => !a.startsWith('-'));
        if (!target) { say('rm: missing operand', 'err'); break; }
        const p = resolve(target);
        const node = vfs.nodeAt(project, p);
        if (!node) { say(`rm: ${p}: No such file or directory`, 'err'); break; }
        if (node.type === 'folder' && !args.includes('-r') && !args.includes('-rf')) { say(`rm: ${p}: is a directory (use rm -r)`, 'err'); break; }
        const r = vfs.deleteNode(project, p);
        if ('error' in r) say(`rm: ${r.error}`, 'err');
        else host.applyProject({ root: r.vfs.root, files: r.vfs.files });
        break;
      }
      case 'mv': case 'cp': {
        if (args.length < 2) { say(`${cmd}: need source and destination`, 'err'); break; }
        const src = resolve(args[0]);
        let dst = resolve(args[1]);
        if (vfs.nodeAt(project, dst)?.type === 'folder') dst = vfs.joinPath(dst, vfs.basename(src));
        if (cmd === 'mv') {
          const r = vfs.moveNode(project, src, vfs.dirname(dst));
          if ('error' in r) { say(`mv: ${r.error}`, 'err'); break; }
          // rename if target name differs
          const movedTo = vfs.joinPath(vfs.dirname(dst), vfs.basename(src));
          let root = r.vfs.root, files = r.vfs.files;
          if (movedTo !== dst) {
            const rr = vfs.renameNode({ ...project, root, files }, movedTo, vfs.basename(dst));
            if ('error' in rr) { say(`mv: ${rr.error}`, 'err'); break; }
            root = rr.vfs.root; files = rr.vfs.files;
          }
          host.applyProject({ root, files });
        } else {
          const f = project.files[src];
          if (!f) { say(`cp: ${src}: No such file`, 'err'); break; }
          const r = vfs.createFile(project, vfs.dirname(dst), vfs.basename(dst), f.content);
          if ('error' in r) say(`cp: ${r.error}`, 'err');
          else host.applyProject({ root: r.vfs.root, files: r.vfs.files });
        }
        break;
      }
      case 'write': {
        // write <file> <text...>
        if (args.length < 2) { say('usage: write <file> <text>', 'err'); break; }
        writeToFile(resolve(args[0]), args.slice(1).join(' ') + '\n');
        break;
      }
      case 'find': {
        const pat = args.find((a) => !a.startsWith('-')) || '';
        const matches = vfs.allFiles(project).filter((p) => p.toLowerCase().includes(pat.replace(/\*/g, '').toLowerCase()));
        matches.slice(0, 100).forEach((m) => say(m));
        say(`(${matches.length} match${matches.length === 1 ? '' : 'es'})`, 'sys');
        break;
      }
      case 'grep': {
        const q = args[0];
        const target = args[1] ? [resolve(args[1])] : vfs.allFiles(project);
        if (!q) { say('usage: grep <pattern> [file]', 'err'); break; }
        let hits = 0;
        for (const p of target) {
          const f = project.files[p];
          if (!f) continue;
          f.content.split('\n').forEach((l, i) => {
            if (l.toLowerCase().includes(q.toLowerCase())) { say(`${p}:${i + 1}: ${l.trim()}`); hits++; }
          });
        }
        if (!hits) say(`grep: no matches for '${q}'`, 'sys');
        break;
      }
      case 'open': case 'edit': {
        if (!args[0]) { say('usage: open <file>', 'err'); break; }
        const p = resolve(args[0]);
        if (!project.files[p]) { say(`open: ${p}: No such file`, 'err'); break; }
        host.openFile(p);
        say(`Opened ${p} in editor`, 'sys');
        break;
      }
      case 'run': case 'node': {
        const target = args.find((a) => !a.startsWith('-'));
        if (!target) { say(`${cmd}: missing file`, 'err'); break; }
        const p = resolve(target);
        const f = project.files[p];
        if (!f) { say(`${cmd}: ${p}: No such file`, 'err'); break; }
        const lang = languageById(f.language);
        if (cmd === 'node' && f.language !== 'javascript' && f.language !== 'typescript') {
          say(`node: cannot run ${lang.name} files`, 'err'); break;
        }
        if (lang.run.kind === 'toolchain') { say(TOOLCHAIN_BINS[lang.id] || lang.run.description, 'err'); break; }
        host.runFile(p);
        say(`▶ Running ${vfs.basename(p)}… (output → Run panel)`, 'sys');
        break;
      }
      case 'python': {
        say(TOOLCHAIN_BINS.python3, 'err'); break;
      }
      case 'format': {
        const p = args[0] ? resolve(args[0]) : null;
        if (!p || !project.files[p]) { say('usage: format <file>', 'err'); break; }
        void host.formatFile(p).then(() => say(`Formatted ${vfs.basename(p)}`, 'sys'));
        break;
      }
      case 'lint': host.lintProject(); say('Linting workspace… (see Problems panel)', 'sys'); break;
      case 'git': gitCommand(args, session, host, say); break;
      default: {
        if (TOOLCHAIN_BINS[cmd]) { say(`${cmd}: ${TOOLCHAIN_BINS[cmd]}`, 'err'); break; }
        say(`${cmd}: command not found — type 'help'`, 'err');
      }
    }
  } catch (e) {
    say(`error: ${e instanceof Error ? e.message : String(e)}`, 'err');
  }

  if (redirect && cmd !== 'echo') {
    const text = out.filter((l) => l.kind === 'out').map((l) => l.text).join('\n') + '\n';
    writeToFile(resolve(redirect.path), (redirect.append ? readFile(resolve(redirect.path)) : '') + text);
    return { lines: [{ text: `→ wrote ${redirect.path}`, kind: 'sys' }] };
  }

  function readFile(p: string): string { return host.getProject().files[p]?.content ?? ''; }
  function writeToFile(p: string, content: string) {
    const proj = host.getProject();
    if (proj.files[p]) {
      host.applyProject({ files: vfs.writeFile(proj, p, content) });
    } else {
      const r = vfs.createFile(proj, vfs.dirname(p), vfs.basename(p), content);
      if ('error' in r) { out.push({ text: `write: ${r.error}`, kind: 'err' }); return; }
      host.applyProject({ root: r.vfs.root, files: r.vfs.files });
    }
  }

  return { lines: out };
}

function gitCommand(args: string[], session: TerminalSession, host: ShellHost, say: (t: string, k?: 'out' | 'err' | 'sys') => void) {
  const project = host.getProject();
  const sub = (args[0] || 'status').toLowerCase();
  const g = git.ensureGit(project);
  const updateGit = (ng: GitState, extra?: Partial<Project>) =>
    host.applyProject({ ...(extra || {}), git: ng });
  switch (sub) {
    case 'init': {
      if (g.initialized) { say('Reinitialized existing Forge git repository'); break; }
      updateGit(git.initRepo(project));
      say(`Initialized empty git repository in ${project.name}/.forge-git`, 'sys');
      break;
    }
    case 'status': {
      if (!g.initialized) { say('fatal: not a git repository (git init)', 'err'); break; }
      say(`On branch ${g.HEAD}`, 'sys');
      const st = git.status(project);
      if (!st.length) { say('nothing to commit, working tree clean'); break; }
      st.forEach((s) => {
        const tag = s.status === 'new' ? 'new file' : s.status === 'modified' ? 'modified' : 'deleted';
        say(`${s.staged ? '●' : '○'} ${tag}: ${s.path}`, s.staged ? 'out' : 'err');
      });
      break;
    }
    case 'add': {
      const paths = args.slice(1);
      if (!paths.length || paths[0] === '.' || paths[0] === '-A' || paths[0] === '--all') {
        updateGit(git.stage(g, vfs.allFiles(project)));
        say('Staged all changes', 'sys');
      } else {
        updateGit(git.stage(g, paths.map((p) => vfs.normalizePath(p.startsWith('/') ? p : vfs.joinPath(session.cwd, p)))));
        say(`Staged ${paths.length} path(s)`, 'sys');
      }
      break;
    }
    case 'commit': {
      const mi = args.indexOf('-m');
      const msg = mi >= 0 ? args.slice(mi + 1).join(' ').replace(/^["']|["']$/g, '') : '';
      const r = git.commit(project, msg || 'update', `${'forge'} <local@forge.dev>`);
      if ('error' in r) { say(`git commit: ${r.error}`, 'err'); break; }
      updateGit(r.git);
      say(`[${r.commit.branch} ${r.commit.id}] ${r.commit.message}`, 'sys');
      say(`${Object.keys(r.commit.files).length} files in tree`, 'sys');
      break;
    }
    case 'branch': {
      const name = args[1];
      if (!name) {
        Object.keys(g.branches).forEach((b) => say(`${b === g.HEAD ? '* ' : '  '}${b}`, b === g.HEAD ? 'sys' : 'out'));
        if (!Object.keys(g.branches).length) say('(no branches — commit first)', 'sys');
        break;
      }
      const r = git.createBranch(g, name);
      if ('error' in r) { say(`git branch: ${r.error}`, 'err'); break; }
      updateGit(r);
      say(`Created branch '${name}'`, 'sys');
      break;
    }
    case 'checkout': case 'switch': {
      let name = args[1];
      if (name === '-b' || name === '-c') {
        const br = git.createBranch(g, args[2] || '');
        if ('error' in br) { say(`git checkout: ${br.error}`, 'err'); break; }
        name = args[2];
        const rr = git.checkout({ ...project, git: br }, name);
        if ('error' in rr) { say(`git checkout: ${rr.error}`, 'err'); break; }
        host.applyProject({ git: rr.git, files: rr.files });
        say(`Switched to a new branch '${name}'`, 'sys');
        break;
      }
      if (!name) { say('usage: git checkout <branch>', 'err'); break; }
      const r = git.checkout(project, name);
      if ('error' in r) { say(`git checkout: ${r.error}`, 'err'); break; }
      host.applyProject({ git: r.git, files: r.files });
      say(`Switched to branch '${name}' — working tree updated`, 'sys');
      break;
    }
    case 'log': {
      const entries = git.log(g, 20);
      if (!entries.length) { say('No commits yet', 'sys'); break; }
      entries.forEach((c) => {
        say(`commit ${c.id} (${c.branch})`, 'sys');
        say(`  Author: ${c.author}`);
        say(`  Date:   ${new Date(c.time).toLocaleString()}`);
        say(`  ${c.message}`);
      });
      break;
    }
    case 'diff': {
      const target = args[1];
      const files = target ? [vfs.normalizePath(vfs.joinPath(session.cwd, target))] : vfs.allFiles(project);
      let any = false;
      for (const p of files) {
        const d = git.diffAgainstHead(project, p);
        if (d.some((l) => l.type === 'add' || l.type === 'del')) {
          any = true;
          say(`diff --git a${p} b${p}`, 'sys');
          d.forEach((l) => say((l.type === 'add' ? '+ ' : l.type === 'del' ? '- ' : '  ') + l.text, l.type === 'add' ? 'out' : l.type === 'del' ? 'err' : 'out'));
        }
      }
      if (!any) say('No changes vs HEAD', 'sys');
      break;
    }
    case 'stash': {
      if (args[1] === 'pop') {
        const r = git.stashPop(project);
        if ('error' in r) { say(`git stash: ${r.error}`, 'err'); break; }
        host.applyProject({ git: r.git, files: r.files });
        say('Applied and dropped stash@{0}', 'sys');
      } else {
        const r = git.stashPush(project, args.slice(2).join(' '));
        if ('error' in r) { say(`git stash: ${r.error}`, 'err'); break; }
        host.applyProject({ git: r.git, files: r.files });
        say('Saved working directory to stash@{0}', 'sys');
      }
      break;
    }
    case 'merge': {
      const r = git.merge(project, args[1] || '');
      if ('error' in r) { say(`git merge: ${r.error}`, 'err'); break; }
      host.applyProject({ git: r.git, files: r.files });
      if (r.conflicts.length) say(`Merged with ${r.conflicts.length} conflict(s): ${r.conflicts.join(', ')}`, 'err');
      else say(`Merged '${args[1]}' into ${g.HEAD}`, 'sys');
      break;
    }
    case 'remote': {
      if (args[1] === 'add' && args[2] && args[3]) {
        updateGit({ ...g, remotes: [...g.remotes.filter((r) => r.name !== args[2]), { name: args[2], url: args[3] }] });
        say(`Remote '${args[2]}' → ${args[3]}`, 'sys');
      } else {
        g.remotes.forEach((r) => say(`${r.name}\t${r.url}`));
        if (!g.remotes.length) say('(no remotes)', 'sys');
      }
      break;
    }
    case 'push': case 'pull': case 'fetch': case 'clone': {
      say(`git ${sub}: ${git.REMOTE_HELP}`, 'err');
      break;
    }
    default: say(`git: '${sub}' is not supported yet — try init/status/add/commit/branch/checkout/log/diff/stash/merge/remote`, 'err');
  }
}

export function tokenize(line: string): string[] {
  const out: string[] = [];
  let cur = '', quote: string | null = null;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quote) { if (ch === quote) quote = null; else cur += ch; continue; }
    if (ch === '"' || ch === "'") { quote = ch; continue; }
    if (/\s/.test(ch)) { if (cur) { out.push(cur); cur = ''; } continue; }
    cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}

function expandEnv(s: string, env: Record<string, string>): string {
  return s.replace(/\$\{?(\w+)\}?/g, (m, k) => env[k] ?? m);
}

export function shellLanguageOf(path: string) { return languageForPath(path); }
