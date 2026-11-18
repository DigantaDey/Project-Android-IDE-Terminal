/**
 * Language registry — the extensibility seam of the IDE (req. 1, 22, 24).
 * Each language declares: detection, highlighting, execution capability,
 * linter and formatter. New languages register here without touching core.
 */
import type { Extension } from '@codemirror/state';
import type { Diagnostic, LangId, RunCapability } from './types';

export interface LanguageDef {
  id: LangId;
  name: string;
  extensions: string[];
  filenames?: string[];
  icon: string;             // short glyph used in the explorer
  color: string;
  run: RunCapability;
  /** lazily loaded CodeMirror language support */
  cm?: () => Promise<Extension>;
  keywords?: string[];
  linter?: (source: string, path: string) => Diagnostic[];
  formatter?: (source: string, tabSize: number, useTabs: boolean) => Promise<string> | string;
  commentToken: string;
}

const KW = {
  js: ['async','await','break','case','catch','class','const','continue','debugger','default','delete','do','else','export','extends','finally','for','from','function','if','import','in','instanceof','let','new','of','return','static','super','switch','this','throw','try','typeof','undefined','var','void','while','with','yield','console','Math','JSON','Promise','Array','Object','String','Number','Boolean','Map','Set','Date','RegExp','Error','setTimeout','setInterval','fetch','require','module','process'],
  ts: ['interface','type','enum','namespace','implements','readonly','public','private','protected','abstract','declare','as','keyof','never','unknown','any','string','number','boolean','void'],
  py: ['and','as','assert','async','await','break','class','continue','def','del','elif','else','except','finally','for','from','global','if','import','in','is','lambda','None','nonlocal','not','or','pass','raise','return','True','False','try','while','with','yield','print','len','range','str','int','float','list','dict','set','tuple','open','input','self','Exception','import'],
  cfamily: ['auto','break','case','char','const','continue','default','do','double','else','enum','extern','float','for','goto','if','int','long','register','return','short','signed','sizeof','static','struct','switch','typedef','union','unsigned','void','volatile','while','class','namespace','template','typename','using','virtual','public','private','protected','new','delete','this','nullptr','bool','true','false','include','define','printf','scanf','malloc','free','std','cout','cin','endl','vector','string'],
  java: ['abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while','String','System','out','println','main','true','false','null'],
  sh: ['if','then','else','elif','fi','for','while','until','do','done','case','esac','function','in','select','echo','cd','ls','pwd','export','local','return','exit','set','unset','shift','source','alias','grep','sed','awk','cat','mkdir','rm','cp','mv','chmod','git','npm','node','python'],
  sql: ['SELECT','FROM','WHERE','INSERT','INTO','VALUES','UPDATE','SET','DELETE','CREATE','TABLE','DROP','ALTER','ADD','JOIN','LEFT','RIGHT','INNER','OUTER','ON','GROUP','BY','ORDER','HAVING','LIMIT','OFFSET','AS','AND','OR','NOT','NULL','PRIMARY','KEY','FOREIGN','REFERENCES','INDEX','UNIQUE','DEFAULT','COUNT','SUM','AVG','MIN','MAX','DISTINCT','UNION','ALL','EXISTS','IN','BETWEEN','LIKE','IS'],
};

function jsonLinter(source: string, path: string): Diagnostic[] {
  if (!source.trim()) return [];
  try { JSON.parse(source); return []; }
  catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const m = /position (\d+)/.exec(msg);
    let line = 1, col = 1;
    if (m) {
      const pos = Number(m[1]);
      const upto = source.slice(0, pos).split('\n');
      line = upto.length; col = upto[upto.length - 1].length + 1;
    } else {
      const lm = /line (\d+) column (\d+)/i.exec(msg);
      if (lm) { line = Number(lm[1]); col = Number(lm[2]); }
    }
    return [{ file: path, line, col, severity: 'error', message: msg, source: 'json' }];
  }
}

function jsSyntaxLint(source: string, path: string): Diagnostic[] {
  if (!source.trim()) return [];
  try {
    // Parse check only — never executed here.
    new Function('"use strict";\n' + source.replace(/^#!.*\n/, ''));
    return [];
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    let line = 1, col = 1;
    const lm = /<anonymous>:(\d+):(\d+)/.exec((e as Error).stack || '') || /:(\d+):(\d+)/.exec((e as Error).stack || '');
    if (lm) { line = Math.max(1, Number(lm[1]) - 2); col = Number(lm[2]); }
    return [{ file: path, line, col, severity: 'error', message: msg, source: 'syntax' }];
  }
}

function braceBalanceLint(source: string, path: string, lang: string): Diagnostic[] {
  const out: Diagnostic[] = [];
  const stack: { ch: string; line: number; col: number }[] = [];
  const pairs: Record<string, string> = { ')': '(', ']': '[', '}': '{' };
  let inStr: string | null = null, esc = false, inLineComment = false, inBlockComment = false;
  const lines = source.split('\n');
  for (let li = 0; li < lines.length; li++) {
    inLineComment = false;
    const row = lines[li];
    for (let ci = 0; ci < row.length; ci++) {
      const ch = row[ci], next = row[ci + 1];
      if (inLineComment) break;
      if (inBlockComment) { if (ch === '*' && next === '/') { inBlockComment = false; ci++; } continue; }
      if (inStr) {
        if (esc) esc = false;
        else if (ch === '\\') esc = true;
        else if (ch === inStr) inStr = null;
        continue;
      }
      if (ch === '/' && next === '/' && lang !== 'plaintext') { inLineComment = true; break; }
      if (ch === '/' && next === '*') { inBlockComment = true; ci++; continue; }
      if (ch === '#' && (lang === 'python' || lang === 'shell')) break;
      if (ch === '"' || ch === "'" || ch === '`') { inStr = ch; continue; }
      if (ch === '(' || ch === '[' || ch === '{') stack.push({ ch, line: li + 1, col: ci + 1 });
      else if (ch === ')' || ch === ']' || ch === '}') {
        const top = stack.pop();
        if (!top || top.ch !== pairs[ch]) {
          out.push({ file: path, line: li + 1, col: ci + 1, severity: 'error', message: `Unmatched '${ch}'`, source: 'lint' });
          if (out.length > 20) return out;
        }
      }
    }
  }
  for (const s of stack.slice(-5)) {
    out.push({ file: path, line: s.line, col: s.col, severity: 'error', message: `Unclosed '${s.ch}'`, source: 'lint' });
  }
  return out;
}

function pyLint(source: string, path: string): Diagnostic[] {
  const out: Diagnostic[] = [];
  const lines = source.split('\n');
  const indentStack: number[] = [0];
  for (let i = 0; i < lines.length; i++) {
    const row = lines[i];
    if (!row.trim() || row.trim().startsWith('#')) continue;
    const indent = row.length - row.trimStart().length;
    if (/\t/.test(row.slice(0, indent)) && row.slice(0, indent).includes(' ')) {
      out.push({ file: path, line: i + 1, col: 1, severity: 'warning', message: 'Mixed tabs and spaces in indentation', source: 'pylint' });
    }
    if (/^\s*(def|class|if|elif|else|for|while|try|except|finally|with)\b[^:]*$/.test(row) && !row.trim().endsWith(':') && !row.trim().endsWith('\\')) {
      out.push({ file: path, line: i + 1, col: row.length, severity: 'error', message: `Expected ':' at end of statement`, source: 'pylint' });
    }
    indentStack.push(indent);
  }
  if (source.includes('\t') && source.includes('    ')) {
    out.push({ file: path, line: 1, col: 1, severity: 'info', message: 'File mixes tabs and spaces', source: 'pylint' });
  }
  return out;
}

// ---------- formatters ----------

async function prettierFormat(source: string, parser: string, tabSize: number, useTabs: boolean): Promise<string> {
  try {
    const prettier = await import('prettier/standalone');
    const plugins: unknown[] = [];
    if (parser === 'babel' || parser === 'typescript') {
      plugins.push(await import('prettier/plugins/babel'), await import('prettier/plugins/estree'));
    } else if (parser === 'html') {
      plugins.push(await import('prettier/plugins/html'));
    } else if (parser === 'css') {
      plugins.push(await import('prettier/plugins/postcss'));
    } else if (parser === 'markdown') {
      plugins.push(await import('prettier/plugins/markdown'));
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return await prettier.format(source, { parser, plugins: plugins as any, tabWidth: tabSize, useTabs, semi: true, singleQuote: true });
  } catch (e) {
    throw new Error('Format failed: ' + (e instanceof Error ? e.message.split('\n')[0] : String(e)));
  }
}

function indentFormat(source: string, tabSize: number, useTabs: boolean): string {
  // Generic bracket-driven re-indenter (clang-format style fallback for C-like langs).
  const unit = useTabs ? '\t' : ' '.repeat(tabSize);
  let depth = 0;
  const out: string[] = [];
  for (const raw of source.split('\n')) {
    const trimmed = raw.trim();
    if (!trimmed) { out.push(''); continue; }
    const closes = (trimmed.match(/^[}\])]/) ? 1 : 0) + (trimmed.startsWith('case ') || trimmed.startsWith('default:') ? 1 : 0);
    depth = Math.max(0, depth - closes);
    out.push(unit.repeat(depth) + trimmed);
    let opens = 0;
    let inStr: string | null = null, esc = false;
    for (let i = 0; i < trimmed.length; i++) {
      const ch = trimmed[i];
      if (inStr) { if (esc) esc = false; else if (ch === '\\') esc = true; else if (ch === inStr) inStr = null; continue; }
      if (ch === '"' || ch === "'" || ch === '`') { inStr = ch; continue; }
      if (ch === '{' || ch === '[' || ch === '(') opens++;
      if (ch === '}' || ch === ']' || ch === ')') opens--;
      if (trimmed.slice(i).startsWith('//')) break;
    }
    depth = Math.max(0, depth + opens + (trimmed.startsWith('case ') || trimmed.startsWith('default:') ? 1 : 0));
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n');
}

function jsonFormat(source: string, tabSize: number): string {
  return JSON.stringify(JSON.parse(source), null, tabSize);
}

// ---------- registry ----------

export const LANGUAGES: Record<LangId, LanguageDef> = {
  javascript: {
    id: 'javascript', name: 'JavaScript', extensions: ['js', 'mjs', 'cjs', 'jsx'], icon: 'JS', color: '#f0db4f',
    run: { kind: 'local', description: 'Runs on-device in the Forge secure worker sandbox' },
    cm: async () => (await import('@codemirror/lang-javascript')).javascript({ jsx: true }),
    keywords: KW.js, linter: (s, p) => [...jsSyntaxLint(s, p), ...braceBalanceLint(s, p, 'javascript')],
    formatter: (s, t, u) => prettierFormat(s, 'babel', t, u), commentToken: '//',
  },
  typescript: {
    id: 'typescript', name: 'TypeScript', extensions: ['ts', 'tsx', 'mts'], icon: 'TS', color: '#3178c6',
    run: { kind: 'local', description: 'Types stripped, executed in the worker sandbox' },
    cm: async () => (await import('@codemirror/lang-javascript')).javascript({ typescript: true, jsx: true }),
    keywords: [...KW.js, ...KW.ts], linter: (s, p) => braceBalanceLint(s, p, 'typescript'),
    formatter: (s, t, u) => prettierFormat(s, 'typescript', t, u), commentToken: '//',
  },
  python: {
    id: 'python', name: 'Python', extensions: ['py', 'pyw'], icon: 'PY', color: '#3572A5',
    run: { kind: 'toolchain', description: 'Requires the Pyodide (WASM CPython) toolchain plugin', plugin: 'pyodide' },
    cm: async () => (await import('@codemirror/lang-python')).python(),
    keywords: KW.py, linter: (s, p) => [...pyLint(s, p), ...braceBalanceLint(s, p, 'python')],
    formatter: undefined /* black-style via toolchain */, commentToken: '#',
  },
  c: {
    id: 'c', name: 'C', extensions: ['c', 'h'], icon: 'C', color: '#649ad3',
    run: { kind: 'toolchain', description: 'Requires a clang/WASM or NDK toolchain plugin', plugin: 'clang' },
    cm: async () => (await import('@codemirror/lang-cpp')).cpp(),
    keywords: KW.cfamily, linter: (s, p) => braceBalanceLint(s, p, 'c'),
    formatter: (s, t, u) => indentFormat(s, t, u), commentToken: '//',
  },
  cpp: {
    id: 'cpp', name: 'C++', extensions: ['cpp', 'cc', 'cxx', 'hpp', 'hh'], icon: 'C++', color: '#f34b7d',
    run: { kind: 'toolchain', description: 'Requires a clang/WASM or NDK toolchain plugin', plugin: 'clang' },
    cm: async () => (await import('@codemirror/lang-cpp')).cpp(),
    keywords: KW.cfamily, linter: (s, p) => braceBalanceLint(s, p, 'cpp'),
    formatter: (s, t, u) => indentFormat(s, t, u), commentToken: '//',
  },
  java: {
    id: 'java', name: 'Java', extensions: ['java'], icon: 'JV', color: '#e76f00',
    run: { kind: 'toolchain', description: 'Requires the ECJ compiler + ART runtime plugin', plugin: 'openjdk' },
    cm: async () => (await import('@codemirror/lang-java')).java(),
    keywords: KW.java, linter: (s, p) => braceBalanceLint(s, p, 'java'),
    formatter: (s, t, u) => indentFormat(s, t, u), commentToken: '//',
  },
  html: {
    id: 'html', name: 'HTML', extensions: ['html', 'htm'], icon: '<>', color: '#e34c26',
    run: { kind: 'preview', description: 'Rendered live in an isolated preview sandbox' },
    cm: async () => (await import('@codemirror/lang-html')).html(),
    keywords: ['html','head','body','div','span','script','style','link','meta','title','p','a','img','ul','ol','li','table','form','input','button','h1','h2','h3','section','article','header','footer','nav','main','canvas','video','audio'],
    formatter: (s, t, u) => prettierFormat(s, 'html', t, u), commentToken: '<!--',
  },
  css: {
    id: 'css', name: 'CSS', extensions: ['css', 'scss', 'less'], icon: '#', color: '#264de4',
    run: { kind: 'preview', description: 'Applied by the HTML preview sandbox' },
    cm: async () => (await import('@codemirror/lang-css')).css(),
    keywords: ['color','background','margin','padding','border','display','flex','grid','position','width','height','font-size','font-family','align-items','justify-content','media','keyframes','import'],
    linter: (s, p) => braceBalanceLint(s, p, 'css'),
    formatter: (s, t, u) => prettierFormat(s, 'css', t, u), commentToken: '/*',
  },
  json: {
    id: 'json', name: 'JSON', extensions: ['json', 'jsonc'], icon: '{}', color: '#cbcb41',
    run: { kind: 'local', description: 'Validated by the built-in JSON linter' },
    cm: async () => (await import('@codemirror/lang-json')).json(),
    linter: jsonLinter, formatter: (s, t) => jsonFormat(s, t), commentToken: '//',
  },
  xml: {
    id: 'xml', name: 'XML', extensions: ['xml', 'svg', 'gradle'], icon: '⌗', color: '#8bc34a',
    run: { kind: 'local', description: 'Parsed and validated on-device' },
    cm: async () => (await import('@codemirror/lang-xml')).xml(),
    keywords: ['xml','version','encoding','manifest','application','activity','uses-permission'],
    commentToken: '<!--',
  },
  markdown: {
    id: 'markdown', name: 'Markdown', extensions: ['md', 'markdown'], icon: 'M↓', color: '#42a5f5',
    run: { kind: 'preview', description: 'Rendered as formatted document' },
    cm: async () => (await import('@codemirror/lang-markdown')).markdown(),
    keywords: [], commentToken: '<!--',
  },
  sql: {
    id: 'sql', name: 'SQL', extensions: ['sql'], icon: 'DB', color: '#e38c00',
    run: { kind: 'toolchain', description: 'Requires the SQLite WASM engine plugin', plugin: 'sqlite' },
    cm: async () => (await import('@codemirror/lang-sql')).sql(),
    keywords: KW.sql, commentToken: '--',
  },
  shell: {
    id: 'shell', name: 'Shell', extensions: ['sh', 'bash', 'zsh'], filenames: ['.bashrc', '.profile'], icon: '>$', color: '#89e051',
    run: { kind: 'local', description: 'Executed by the Forge shell inside the sandbox VFS' },
    cm: async () => (await import('@codemirror/lang-javascript')).javascript(), /* closest stream parser */
    keywords: KW.sh, linter: (s, p) => braceBalanceLint(s, p, 'shell'), commentToken: '#',
  },
  plaintext: {
    id: 'plaintext', name: 'Plain Text', extensions: ['txt', 'log', 'gitignore', 'env'], icon: 'Tx', color: '#8b93a7',
    run: { kind: 'local', description: 'Not executable' }, keywords: [], commentToken: '#',
  },
};

export function languageForPath(path: string): LangId {
  const name = path.slice(path.lastIndexOf('/') + 1).toLowerCase();
  for (const lang of Object.values(LANGUAGES)) {
    if (lang.filenames?.includes(name)) return lang.id;
  }
  const ext = name.includes('.') ? name.split('.').pop()! : '';
  for (const lang of Object.values(LANGUAGES)) {
    if (lang.extensions.includes(ext)) return lang.id;
  }
  return 'plaintext';
}

export function languageById(id: LangId): LanguageDef {
  return LANGUAGES[id] ?? LANGUAGES.plaintext;
}

export const RUNNABLE_LOCAL = (Object.values(LANGUAGES) as LanguageDef[]).filter((l) => l.run.kind !== 'toolchain').map((l) => l.id);
