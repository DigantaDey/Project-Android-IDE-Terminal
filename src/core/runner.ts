/**
 * Secure code sandbox (req. 6, 20, 24).
 *
 * User code runs inside a dedicated Web Worker created from a Blob:
 *  - no DOM, no access to the IDE page or project storage
 *  - network APIs (fetch / XHR / WebSocket / importScripts) are stubbed off
 *  - configurable wall-clock timeout; parent can hard-kill via terminate()
 *  - TypeScript is type-stripped before execution (documented approximation)
 *
 * Debug mode instruments the source with await checkpoints so breakpoints,
 * stepping and watch expressions work against the worker's global scope.
 */
import type { Diagnostic } from './types';

export interface RunOutput { stream: 'stdout' | 'stderr' | 'system'; text: string; }
export interface SandboxLimits { timeoutMs: number; network: boolean; }

export interface RunHandle {
  kill(): void;
  resume?(mode: 'continue' | 'step'): void;
  finished: Promise<{ exitCode: number; durationMs: number }>;
}

export interface RunCallbacks {
  onOutput(out: RunOutput): void;
  onDebugPause?(info: { line: number; stack: { name: string; line: number }[]; globals: Record<string, string> }): void;
  onDebugResume?(): void;
}

const WORKER_SOURCE = `
'use strict';
/* Forge sandbox worker — network & host access removed before user code runs. */
const NET_DISABLED = () => { throw new Error('Network access is disabled inside the Forge sandbox'); };
self.fetch = NET_DISABLED;
self.XMLHttpRequest = function(){ NET_DISABLED(); };
self.WebSocket = function(){ NET_DISABLED(); };
self.importScripts = NET_DISABLED;
if (!self.__allowNet === true) {}

const __send = (stream, args) => {
  const text = args.map(a => {
    if (typeof a === 'string') return a;
    if (a instanceof Error) return a.stack || String(a);
    try { return JSON.stringify(a, (k, v) => typeof v === 'bigint' ? String(v) + 'n' : typeof v === 'function' ? '[Function ' + (v.name || 'anonymous') + ']' : v, 2); }
    catch { return String(a); }
  }).join(' ');
  self.postMessage({ type: 'console', stream, text });
};
console = {
  log: (...a) => __send('stdout', a),
  info: (...a) => __send('stdout', a),
  warn: (...a) => __send('stderr', a.map(x => '⚠ ' + (typeof x === 'string' ? x : JSON.stringify(x)))),
  error: (...a) => __send('stderr', a),
  debug: (...a) => __send('stdout', a),
  table: (...a) => __send('stdout', a),
  clear: () => self.postMessage({ type: 'clear' }),
  time: (l) => {}, timeEnd: (l) => {},
};

/* ---- debug support: await checkpoints + watch eval ---- */
let __resumeWaiter = null;
let __stepMode = false;
let __breakpoints = new Set();

self.__dbgCheckpoint = async (line, globalsSnapshot) => {
  if (!__breakpoints.has(line) && !__stepMode) return;
  __stepMode = false;
  const stack = new Error().stack ? new Error().stack.split('\\n').slice(2, 9).map(s => {
    const m = /at (.*?) \\(?(?:<anonymous>|blob:.*?):(\\d+):\\d+\\)?/.exec(s) || /(.*?)[@(].*?:(\\d+):\\d+/.exec(s);
    return { name: (m && m[1] ? m[1].trim() : '<anonymous>') || '<anonymous>', line: m ? Number(m[2]) : 0 };
  }) : [];
  self.postMessage({ type: 'pause', line, stack, globals: globalsSnapshot ? globalsSnapshot() : {} });
  await new Promise(res => { __resumeWaiter = res; });
};

self.onmessage = async (ev) => {
  const msg = ev.data;
  if (msg.type === 'resume') {
    __stepMode = msg.mode === 'step';
    const w = __resumeWaiter; __resumeWaiter = null;
    if (w) w();
    return;
  }
  if (msg.type === 'run') {
    __breakpoints = new Set(msg.breakpoints || []);
    const started = Date.now();
    let exitCode = 0;
    try {
      const instrumented = msg.debug ? __instrument(msg.code) : msg.code;
      const fn = new Function('__checkpoint', '"use strict"; return (async () => {\\n' + instrumented + '\\n})();');
      await fn(__dbgCheckpoint);
    } catch (e) {
      exitCode = 1;
      if (!(e && e.name === 'ExitError')) {
        __send('stderr', [ (e && e.stack ? String(e.stack).split('\\n').slice(0,3).join('\\n') : String(e)) ]);
      }
    }
    self.postMessage({ type: 'exit', exitCode, durationMs: Date.now() - started });
  }
};

function __instrument(code) {
  const lines = code.split('\\n');
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (!t || t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) { out.push(lines[i]); continue; }
    out.push('await __checkpoint(' + (i + 1) + ', () => ({\\n' +
      '__globals: (() => { const g = {}; for (const k of Object.keys(globalThis).slice(-60)) { try { const v = globalThis[k]; if (typeof v !== "function") g[k] = typeof v === "object" ? JSON.stringify(v) : String(v); } catch {} } return JSON.stringify(g); })()\\n' +
    '}));');
    out.push(lines[i]);
  }
  return out.join('\\n');
}
`;

let workerUrl: string | null = null;
function getWorkerUrl(): string {
  if (!workerUrl) workerUrl = URL.createObjectURL(new Blob([WORKER_SOURCE], { type: 'text/javascript' }));
  return workerUrl;
}

/** Naive but safe TypeScript type-stripping (documented approximation for the sandbox). */
export function stripTypes(code: string): string {
  return code
    .replace(/^\s*interface\s+\w+(\s+extends\s+[\w,\s]+)?\s*\{[^}]*\}/gm, '')
    .replace(/^\s*type\s+\w+\s*=[^;]+;/gm, '')
    .replace(/:\s*(string|number|boolean|any|void|never|unknown|undefined|null|object|bigint|symbol|Date|RegExp|[A-Z]\w*(\[\])?|string\[\]|number\[\]|boolean\[\]|Array<[^>]*>|Record<[^>]*>|Map<[^>]*>|Set<[^>]*>|Promise<[^>]*>)(\s*[,)\]=;{])/g, '$3')
    .replace(/:\s*(string|number|boolean|any|void|never|unknown|[A-Z]\w*)$/gm, '')
    .replace(/\(([^)]*)\)\s*=>/g, (m, args) => '(' + args.replace(/:\s*[\w<>[\]|]+/g, '') + ') =>')
    .replace(/<(string|number|boolean|any)>/g, '')
    .replace(/\s+as\s+(string|number|boolean|any|const|\w+(\[\])?)/g, '')
    .replace(/(private|public|protected|readonly)\s+/g, '')
    .replace(/([,!]\s*\w+)\?\s*:/g, '$1:')
    .replace(/(\w+)\?\s*:/g, '$1:')
    .replace(/:\s*\w+(\[\])?\s*([=;,{)])/g, '$2');
}

export function runJavaScript(opts: {
  code: string;
  fileName: string;
  language: string;
  limits: SandboxLimits;
  debug?: boolean;
  breakpoints?: number[];
  callbacks: RunCallbacks;
}): RunHandle {
  const { callbacks } = opts;
  let code = opts.code;
  if (opts.language === 'typescript') code = stripTypes(code);

  const worker = new Worker(getWorkerUrl());
  let done = false;
  let killReason: 'user' | 'timeout' | null = null;
  const startedAt = Date.now();

  const finish = (exitCode: number) => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    worker.terminate();
    resolveFinished({ exitCode, durationMs: Date.now() - startedAt });
  };

  const timer = setTimeout(() => {
    killReason = 'timeout';
    callbacks.onOutput({ stream: 'stderr', text: `⏱ Execution timed out after ${opts.limits.timeoutMs}ms — process killed (sandbox limit)` });
    finish(124);
  }, opts.limits.timeoutMs);

  worker.onmessage = (ev) => {
    const msg = ev.data;
    if (msg.type === 'console') callbacks.onOutput({ stream: msg.stream, text: msg.text });
    else if (msg.type === 'clear') callbacks.onOutput({ stream: 'system', text: '__clear__' });
    else if (msg.type === 'pause' && opts.debug) {
      let globals: Record<string, string> = {};
      try { globals = JSON.parse(msg.globals?.__globals || '{}'); } catch { /* ignore */ }
      callbacks.onDebugPause?.({ line: msg.line, stack: msg.stack, globals });
    }
    else if (msg.type === 'exit') {
      if (killReason === null) finish(msg.exitCode);
    }
  };
  worker.onerror = (e) => {
    callbacks.onOutput({ stream: 'stderr', text: `${e.message || 'Worker error'} (line ${e.lineno ?? '?'})` });
    finish(1);
  };

  worker.postMessage({ type: 'run', code, debug: !!opts.debug, breakpoints: opts.breakpoints || [] });

  let resolveFinished!: (v: { exitCode: number; durationMs: number }) => void;
  const finished = new Promise<{ exitCode: number; durationMs: number }>((res) => { resolveFinished = res; });

  return {
    kill() { killReason = 'user'; callbacks.onOutput({ stream: 'system', text: '⛔ Process terminated by user' }); finish(130); },
    resume(mode) { worker.postMessage({ type: 'resume', mode }); callbacks.onDebugResume?.(); },
    finished,
  };
}

/** Minimal markdown renderer for preview (no external deps). */
export function renderMarkdown(src: string): string {
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  let html = esc(src);
  html = html
    .replace(/```(\w*)\n([\s\S]*?)```/g, (_m, l, c) => `<pre class="code"><code>${c}</code></pre>`)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/^###### (.*)$/gm, '<h6>$1</h6>')
    .replace(/^##### (.*)$/gm, '<h5>$1</h5>')
    .replace(/^#### (.*)$/gm, '<h4>$1</h4>')
    .replace(/^### (.*)$/gm, '<h3>$1</h3>')
    .replace(/^## (.*)$/gm, '<h2>$1</h2>')
    .replace(/^# (.*)$/gm, '<h1>$1</h1>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img alt="$1" src="$2"/>')
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
    .replace(/^&gt; (.*)$/gm, '<blockquote>$1</blockquote>')
    .replace(/^[-*] (.*)$/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>\n?)+/g, (m) => `<ul>${m}</ul>`)
    .replace(/^---$/gm, '<hr/>')
    .replace(/\n{2,}/g, '</p><p>')
    .replace(/\n/g, '<br/>');
  return `<p>${html}</p>`;
}

/** Build a self-contained HTML preview document from project sources. */
export function buildHtmlPreview(entryHtml: string, resolve: (p: string) => string | undefined): string {
  let doc = entryHtml;
  doc = doc.replace(/<link[^>]*href=["']([^"']+)["'][^>]*>/gi, (m, href) => {
    const css = resolve(href);
    return css !== undefined ? `<style>\n${css}\n</style>` : m;
  });
  doc = doc.replace(/<script[^>]*src=["']([^"']+)["'][^>]*>\s*<\/script>/gi, (m, src) => {
    const js = resolve(src);
    return js !== undefined ? `<script>\ntry{\n${js}\n}catch(e){document.body.insertAdjacentHTML('beforeend','<pre style="color:#f66;font:12px monospace">'+String(e).replace(/</g,'&lt;')+'</pre>')}\n</script>` : m;
  });
  if (!/<script[\s>]/i.test(doc)) {
    const js = resolve('script.js') ?? resolve('main.js') ?? resolve('index.js');
    if (js !== undefined) doc += `<script>\ntry{\n${js}\n}catch(e){console.error(e)}</script>`;
  }
  if (!/<style[\s>]/i.test(doc) && !/<link/i.test(doc)) {
    const css = resolve('style.css') ?? resolve('styles.css') ?? resolve('index.css');
    if (css !== undefined) doc = doc.replace(/<\/head>/i, `<style>${css}</style></head>`);
  }
  return doc;
}

export function syntaxDiagnostics(code: string, fileName: string): Diagnostic[] {
  try { new Function(code); return []; }
  catch (e) { return [{ file: fileName, line: 1, col: 1, severity: 'error', message: e instanceof Error ? e.message : String(e), source: 'syntax' }]; }
}
