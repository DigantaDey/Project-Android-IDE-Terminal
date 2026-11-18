/** Tiny event bus for cross-module signals (log, build, debug). */
type Handler<T> = (payload: T) => void;

export class Bus<Events extends object> {
  private map = new Map<string, Set<Handler<never>>>();

  on<K extends keyof Events>(event: K, handler: Handler<Events[K]>): () => void {
    let set = this.map.get(event as string);
    if (!set) { set = new Set(); this.map.set(event as string, set); }
    set.add(handler as Handler<never>);
    return () => set!.delete(handler as Handler<never>);
  }

  emit<K extends keyof Events>(event: K, payload: Events[K]): void {
    const set = this.map.get(event as string);
    if (!set) return;
    for (const h of Array.from(set)) {
      try { (h as Handler<Events[K]>)(payload); } catch (e) { console.error(e); }
    }
  }
}

export interface IdeEvents {
  'open-file': { path: string; line?: number; col?: number };
  'toast': { text: string; kind?: 'info' | 'error' | 'success' };
  'run-file': { path: string; debug?: boolean };
  'save-all': Record<string, never>;
  'bottom-tab': { tab: string };
  'editor-reveal': { line: number; col?: number };
  'debug-changed': Record<string, never>;
  'git-changed': Record<string, never>;
  'fs-changed': Record<string, never>;
}

export const bus = new Bus<IdeEvents>();
