/** Ring-buffer logger for the Logs panel + troubleshooting (req. 19). */
export interface LogEntry { time: number; level: 'debug' | 'info' | 'warn' | 'error'; tag: string; message: string; }

const MAX = 800;
const entries: LogEntry[] = [];
const listeners = new Set<(e: LogEntry) => void>();

function push(level: LogEntry['level'], tag: string, message: string) {
  const e: LogEntry = { time: Date.now(), level, tag, message };
  entries.push(e);
  if (entries.length > MAX) entries.splice(0, entries.length - MAX);
  listeners.forEach((l) => { try { l(e); } catch { /* noop */ } });
}

export const log = {
  debug: (tag: string, m: string) => push('debug', tag, m),
  info: (tag: string, m: string) => push('info', tag, m),
  warn: (tag: string, m: string) => push('warn', tag, m),
  error: (tag: string, m: string) => push('error', tag, m),
  all: () => [...entries],
  subscribe: (fn: (e: LogEntry) => void) => { listeners.add(fn); return () => listeners.delete(fn); },
  clear: () => { entries.length = 0; },
};
