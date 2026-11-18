/** Persistent key-value storage with JSON + corruption guard. */

const PREFIX = 'forge.ide.v1.';

export function loadJSON<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch (e) {
    // Corruption-resistant: back up the broken blob instead of crashing (req. 19)
    try {
      const raw = localStorage.getItem(PREFIX + key);
      if (raw) localStorage.setItem(PREFIX + key + '.corrupt.' + Date.now(), raw);
    } catch { /* ignore */ }
    console.error('[storage] failed to parse', key, e);
    return fallback;
  }
}

export function saveJSON(key: string, value: unknown): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch (e) {
    console.error('[storage] quota exceeded while saving', key, e);
  }
}

export function removeKey(key: string): void {
  try { localStorage.removeItem(PREFIX + key); } catch { /* ignore */ }
}

export function storageUsage(): { used: number; keys: number } {
  let used = 0, keys = 0;
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)!;
      if (k.startsWith(PREFIX)) {
        keys++;
        used += (localStorage.getItem(k) || '').length * 2;
      }
    }
  } catch { /* ignore */ }
  return { used, keys };
}

export function formatBytes(n: number): string {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(2) + ' MB';
}
