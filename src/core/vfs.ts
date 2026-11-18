/**
 * Virtual file system layered over a Project's FileNode tree + content map.
 * Pure functions — the store applies them and persists the resulting project.
 */
import type { FileNode, Project, ProjectFile } from './types';
import { languageForPath } from './languages';
import { uid } from './types';

export function normalizePath(p: string): string {
  const parts = p.split('/').filter(Boolean);
  const out: string[] = [];
  for (const part of parts) {
    if (part === '.') continue;
    if (part === '..') out.pop();
    else out.push(part);
  }
  return '/' + out.join('/');
}

export function dirname(p: string): string {
  const n = normalizePath(p);
  const i = n.lastIndexOf('/');
  return i <= 0 ? '/' : n.slice(0, i);
}

export function basename(p: string): string {
  const n = normalizePath(p);
  return n.slice(n.lastIndexOf('/') + 1);
}

export function joinPath(...parts: string[]): string {
  return normalizePath(parts.join('/'));
}

function findNode(node: FileNode, path: string): FileNode | null {
  const target = normalizePath(path);
  if (target === '/') return node;
  const parts = target.split('/').filter(Boolean);
  let cur = node;
  for (const part of parts) {
    if (!cur.children) return null;
    const next = cur.children.find((c) => c.name === part);
    if (!next) return null;
    cur = next;
  }
  return cur;
}

export function nodeAt(project: Project, path: string): FileNode | null {
  return findNode(project.root, path);
}

export function exists(project: Project, path: string): boolean {
  return findNode(project.root, path) !== null;
}

export function listDir(project: Project, path: string): FileNode[] {
  const node = findNode(project.root, path);
  if (!node || node.type !== 'folder') return [];
  return [...(node.children || [])].sort((a, b) =>
    a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'folder' ? -1 : 1,
  );
}

function cloneRoot(root: FileNode): FileNode {
  return JSON.parse(JSON.stringify(root)) as FileNode;
}

function uniqueName(project: Project, dir: string, name: string): string {
  if (!exists(project, joinPath(dir, name))) return name;
  const dot = name.lastIndexOf('.');
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot) : '';
  let i = 1;
  while (exists(project, joinPath(dir, `${stem} ${i}${ext}`))) i++;
  return `${stem} ${i}${ext}`;
}

export type VfsResult = { root: FileNode; files: Record<string, ProjectFile> };

export function createFile(project: Project, dir: string, name: string, content = ''): { vfs: VfsResult; path: string } | { error: string } {
  dir = normalizePath(dir);
  const parent = findNode(project.root, dir);
  if (!parent || parent.type !== 'folder') return { error: `Folder not found: ${dir}` };
  name = name.trim();
  if (!name || /[/\\]/.test(name)) return { error: 'Invalid file name' };
  if (exists(project, joinPath(dir, name))) return { error: `"${name}" already exists` };
  const root = cloneRoot(project.root);
  const target = findNode(root, dir)!;
  target.children = target.children || [];
  target.children.push({ id: uid(), name, type: 'file' });
  const path = joinPath(dir, name);
  const files = { ...project.files };
  files[path] = { path, content, language: languageForPath(path), updatedAt: Date.now() };
  return { vfs: { root, files }, path };
}

export function createFolder(project: Project, dir: string, name: string): { vfs: VfsResult; path: string } | { error: string } {
  dir = normalizePath(dir);
  const parent = findNode(project.root, dir);
  if (!parent || parent.type !== 'folder') return { error: `Folder not found: ${dir}` };
  name = name.trim();
  if (!name || /[/\\]/.test(name)) return { error: 'Invalid folder name' };
  if (exists(project, joinPath(dir, name))) return { error: `"${name}" already exists` };
  const root = cloneRoot(project.root);
  const target = findNode(root, dir)!;
  target.children = target.children || [];
  target.children.push({ id: uid(), name, type: 'folder', children: [] });
  return { vfs: { root, files: { ...project.files } }, path: joinPath(dir, name) };
}

export function writeFile(project: Project, path: string, content: string): Record<string, ProjectFile> {
  path = normalizePath(path);
  const prev = project.files[path];
  return {
    ...project.files,
    [path]: { path, content, language: prev?.language ?? languageForPath(path), updatedAt: Date.now() },
  };
}

export function renameNode(project: Project, path: string, newName: string): { vfs: VfsResult; oldToNew: [string, string][] } | { error: string } {
  path = normalizePath(path);
  if (path === '/') return { error: 'Cannot rename project root' };
  const node = findNode(project.root, path);
  if (!node) return { error: 'Not found' };
  newName = newName.trim();
  if (!newName || /[/\\]/.test(newName)) return { error: 'Invalid name' };
  const parent = dirname(path);
  const newPath = joinPath(parent, newName);
  if (exists(project, newPath)) return { error: `"${newName}" already exists` };
  const root = cloneRoot(project.root);
  findNode(root, path)!.name = newName;
  const files = { ...project.files };
  const oldToNew: [string, string][] = [];
  const remap = (oldBase: string, newBase: string) => {
    for (const key of Object.keys(files)) {
      if (key === oldBase || key.startsWith(oldBase + '/')) {
        const nk = key.replace(oldBase, newBase);
        files[nk] = { ...files[key], path: nk, language: languageForPath(nk) };
        delete files[key];
        oldToNew.push([key, nk]);
      }
    }
  };
  remap(path, newPath);
  return { vfs: { root, files }, oldToNew };
}

export function deleteNode(project: Project, path: string): { vfs: VfsResult; removed: string[] } | { error: string } {
  path = normalizePath(path);
  if (path === '/') return { error: 'Cannot delete project root' };
  const parent = findNode(project.root, dirname(path));
  if (!parent?.children) return { error: 'Not found' };
  const root = cloneRoot(project.root);
  const p = findNode(root, dirname(path))!;
  const name = basename(path);
  p.children = (p.children || []).filter((c) => c.name !== name);
  const files = { ...project.files };
  const removed: string[] = [];
  for (const key of Object.keys(files)) {
    if (key === path || key.startsWith(path + '/')) { delete files[key]; removed.push(key); }
  }
  return { vfs: { root, files }, removed };
}

export function moveNode(project: Project, src: string, destDir: string): { vfs: VfsResult; oldToNew: [string, string][] } | { error: string } {
  src = normalizePath(src); destDir = normalizePath(destDir);
  const node = findNode(project.root, src);
  const dest = findNode(project.root, destDir);
  if (!node) return { error: 'Source not found' };
  if (!dest || dest.type !== 'folder') return { error: 'Destination is not a folder' };
  if (src === destDir || destDir.startsWith(src + '/')) return { error: 'Cannot move a folder into itself' };
  const name = uniqueName(project, destDir, basename(src));
  const del = deleteNode(project, src);
  if ('error' in del) return del;
  // reattach node copy under destination
  const root = del.vfs.root;
  const target = findNode(root, destDir)!;
  const nodeCopy: FileNode = JSON.parse(JSON.stringify(node));
  const renameTree = (n: FileNode, newName: string) => { n.name = newName; };
  renameTree(nodeCopy, name);
  target.children = target.children || [];
  target.children.push(nodeCopy);
  const files = { ...del.vfs.files };
  const oldToNew: [string, string][] = [];
  // rebuild content entries from deleted snapshot
  const oldFiles = project.files;
  for (const key of Object.keys(oldFiles)) {
    if (key === src || key.startsWith(src + '/')) {
      const nk = joinPath(destDir, name) + key.slice(src.length);
      files[nk] = { ...oldFiles[key], path: nk, language: languageForPath(nk), updatedAt: Date.now() };
      oldToNew.push([key, nk]);
    }
  }
  return { vfs: { root, files }, oldToNew };
}

export function duplicateFile(project: Project, path: string): { vfs: VfsResult; path: string } | { error: string } {
  path = normalizePath(path);
  const f = project.files[path];
  if (!f) return { error: 'File not found' };
  const name = uniqueName(project, dirname(path), basename(path));
  return createFile({ ...project }, dirname(path), name, f.content);
}

export function allFiles(project: Project): string[] {
  return Object.keys(project.files).sort();
}

export interface SearchMatch { path: string; line: number; col: number; preview: string; length: number; }

export function searchProject(project: Project, query: string, opts: { regex?: boolean; caseSensitive?: boolean } = {}): SearchMatch[] {
  const results: SearchMatch[] = [];
  let re: RegExp | null = null;
  try {
    re = opts.regex
      ? new RegExp(query, opts.caseSensitive ? 'g' : 'gi')
      : new RegExp(escapeRegExp(query), opts.caseSensitive ? 'g' : 'gi');
  } catch { return []; }
  if (!query) return [];
  for (const [path, file] of Object.entries(project.files)) {
    const lines = file.content.split('\n');
    for (let i = 0; i < lines.length; i++) {
      re.lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = re.exec(lines[i]))) {
        results.push({ path, line: i + 1, col: m.index + 1, preview: lines[i].trim().slice(0, 160), length: m[0].length });
        if (m[0].length === 0) re.lastIndex++;
        if (results.length > 2000) return results;
      }
    }
  }
  return results;
}

export function replaceInProject(project: Project, query: string, replacement: string, opts: { regex?: boolean; caseSensitive?: boolean } = {}): { files: Record<string, ProjectFile>; count: number } {
  let re: RegExp;
  try {
    re = opts.regex
      ? new RegExp(query, opts.caseSensitive ? 'g' : 'gi')
      : new RegExp(escapeRegExp(query), opts.caseSensitive ? 'g' : 'gi');
  } catch { return { files: project.files, count: 0 }; }
  let count = 0;
  const files = { ...project.files };
  for (const [path, file] of Object.entries(files)) {
    const next = file.content.replace(re, () => { count++; return replacement; });
    if (next !== file.content) files[path] = { ...file, content: next, updatedAt: Date.now() };
  }
  return { files, count };
}

export function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function countNodes(node: FileNode): { files: number; folders: number } {
  let files = 0, folders = 0;
  const walk = (n: FileNode) => {
    if (n.type === 'file') files++;
    else { folders++; (n.children || []).forEach(walk); }
  };
  walk(node);
  return { files, folders };
}
