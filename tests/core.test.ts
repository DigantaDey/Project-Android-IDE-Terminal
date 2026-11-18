import { describe, it, expect } from 'vitest';
import * as vfs from '../src/core/vfs';
import * as git from '../src/core/git';
import { seedProjects } from '../src/state/seed';
import { stripTypes } from '../src/core/runner';
import type { Project } from '../src/core/types';

function proj(): Project {
  return seedProjects()[0];
}

describe('vfs', () => {
  it('normalizes paths', () => {
    expect(vfs.normalizePath('/a//b/../c')).toBe('/a/c');
    expect(vfs.joinPath('/', 'src', 'main.js')).toBe('/src/main.js');
    expect(vfs.dirname('/src/main.js')).toBe('/src');
    expect(vfs.basename('/src/main.js')).toBe('main.js');
  });

  it('creates, renames, moves, duplicates, deletes files', () => {
    let p = proj();
    const c = vfs.createFile(p, '/src', 'extra.js', 'console.log(1)');
    if ('error' in c) throw new Error(c.error);
    p = { ...p, root: c.vfs.root, files: c.vfs.files };
    expect(p.files['/src/extra.js'].content).toBe('console.log(1)');

    const r = vfs.renameNode(p, '/src/extra.js', 'renamed.js');
    if ('error' in r) throw new Error(r.error);
    p = { ...p, root: r.vfs.root, files: r.vfs.files };
    expect(p.files['/src/renamed.js']).toBeTruthy();
    expect(p.files['/src/extra.js']).toBeUndefined();

    const d = vfs.duplicateFile(p, '/src/renamed.js');
    if ('error' in d) throw new Error(d.error);
    p = { ...p, root: d.vfs.root, files: d.vfs.files };
    expect(d.path).toBe('/src/renamed 1.js');

    const m = vfs.moveNode(p, '/src/renamed.js', '/web');
    if ('error' in m) throw new Error(m.error);
    p = { ...p, root: m.vfs.root, files: m.vfs.files };
    expect(p.files['/web/renamed.js']).toBeTruthy();

    const del = vfs.deleteNode(p, '/web/renamed.js');
    if ('error' in del) throw new Error(del.error);
    p = { ...p, root: del.vfs.root, files: del.vfs.files };
    expect(p.files['/web/renamed.js']).toBeUndefined();
  });

  it('rejects invalid operations', () => {
    const p = proj();
    expect('error' in (vfs.createFile(p, '/src', 'main.js'))).toBe(true); // duplicate
    expect('error' in (vfs.createFile(p, '/nope', 'x.js'))).toBe(true);
    expect('error' in (vfs.deleteNode(p, '/'))).toBe(true);
  });

  it('searches with regex', () => {
    const p = proj();
    const hits = vfs.searchProject(p, 'fibonacci', {});
    expect(hits.length).toBeGreaterThan(0);
    expect(hits[0].path).toContain('main.js');
    const reHits = vfs.searchProject(p, 'fibon\\w+', { regex: true });
    expect(reHits.length).toBeGreaterThan(0);
  });

  it('replaces across files', () => {
    const p = proj();
    const { files, count } = vfs.replaceInProject(p, 'fibonacci', 'fib');
    expect(count).toBeGreaterThan(0);
    expect(files['/src/main.js'].content).toContain('fib(');
    expect(files['/src/main.js'].content).not.toContain('fibonacci');
  });
});

describe('git', () => {
  it('init → add → commit → status clean', () => {
    let p = proj();
    p = { ...p, git: git.initRepo(p) };
    const g1 = git.stage(p.git!, vfs.allFiles(p));
    p = { ...p, git: g1 };
    const c = git.commit(p, 'initial commit', 'T <t@t>');
    if ('error' in c) throw new Error(c.error);
    p = { ...p, git: c.git };
    expect(c.commit.branch).toBe('main');
    expect(git.status(p).length).toBe(0);
    expect(git.log(p.git!).length).toBe(1);
  });

  it('tracks modifications and diffs', () => {
    let p = proj();
    p = { ...p, git: git.initRepo(p) };
    p = { ...p, git: git.stage(p.git!, vfs.allFiles(p)) };
    const c = git.commit(p, 'init', 'T <t@t>');
    if ('error' in c) throw new Error(c.error);
    p = { ...p, git: c.git };
    p = { ...p, files: vfs.writeFile(p, '/src/main.js', p.files['/src/main.js'].content + '\n// change\n') };
    const st = git.status(p);
    expect(st.find((s) => s.path === '/src/main.js')?.status).toBe('modified');
    const diff = git.diffAgainstHead(p, '/src/main.js');
    expect(diff.some((l) => l.type === 'add' && l.text.includes('change'))).toBe(true);
  });

  it('branches, checkouts and merges', () => {
    let p = proj();
    p = { ...p, git: git.initRepo(p) };
    p = { ...p, git: git.stage(p.git!, vfs.allFiles(p)) };
    const c = git.commit(p, 'init', 'T');
    if ('error' in c) throw new Error(c.error);
    p = { ...p, git: c.git };
    const b = git.createBranch(p.git!, 'feature');
    if ('error' in b) throw new Error(b.error);
    p = { ...p, git: b };
    const co = git.checkout(p, 'feature');
    if ('error' in co) throw new Error(co.error);
    p = { ...p, git: co.git, files: co.files };
    expect(p.git!.HEAD).toBe('feature');
    p = { ...p, files: vfs.writeFile(p, '/feature.txt', 'new') };
    const c2 = git.commit(p, 'feature work', 'T');
    if ('error' in c2) throw new Error(c2.error);
    p = { ...p, git: c2.git };
    const back = git.checkout(p, 'main');
    if ('error' in back) throw new Error(back.error);
    p = { ...p, git: back.git, files: back.files };
    expect(p.files['/feature.txt']).toBeUndefined();
    const mg = git.merge(p, 'feature');
    if ('error' in mg) throw new Error(mg.error);
    p = { ...p, git: mg.git, files: mg.files };
    expect(p.files['/feature.txt']?.content).toBe('new');
  });

  it('stash push/pop roundtrips', () => {
    let p = proj();
    p = { ...p, git: git.initRepo(p) };
    p = { ...p, git: git.stage(p.git!, vfs.allFiles(p)) };
    const c = git.commit(p, 'init', 'T');
    if ('error' in c) throw new Error(c.error);
    p = { ...p, git: c.git };
    p = { ...p, files: vfs.writeFile(p, '/src/main.js', 'modified content') };
    const sp = git.stashPush(p, 'wip');
    if ('error' in sp) throw new Error(sp.error);
    p = { ...p, git: sp.git, files: sp.files };
    expect(p.files['/src/main.js'].content).not.toBe('modified content');
    const pop = git.stashPop(p);
    if ('error' in pop) throw new Error(pop.error);
    p = { ...p, git: pop.git, files: pop.files };
    expect(p.files['/src/main.js'].content).toBe('modified content');
  });
});

describe('runner.stripTypes', () => {
  it('strips TS annotations', () => {
    const ts = `interface A { x: number }\nfunction f(a: string, n: number): string { return a + n; }\nconst y: number[] = [1];`;
    const js = stripTypes(ts);
    expect(js).not.toContain('interface A');
    expect(js).not.toContain(': number');
    expect(js).toContain('function f(a, b'.replace('b', 'n'));
  });
});
