// One in-memory log for the whole app. It lives at module scope, because
// handlers and listeners can fire before the screen mounts: the boot replay
// of journaled outcomes starts right after configure().

export type LogKind = 'action' | 'attempt' | 'state' | 'outcome' | 'warn';

export type LogEntry = {
  seq: number;
  at: number;
  kind: LogKind;
  text: string;
  /** An outcome that native journaled before this launch. */
  journaled?: boolean;
};

const MAX_ENTRIES = 400;

let entries: LogEntry[] = [];
let seq = 0;
const listeners = new Set<() => void>();

const notify = () => listeners.forEach(l => l());

export const log = (
  kind: LogKind,
  text: string,
  extra?: {journaled?: boolean},
): void => {
  seq += 1;
  entries = [
    {seq, at: Date.now(), kind, text, journaled: extra?.journaled},
    ...entries,
  ].slice(0, MAX_ENTRIES);
  // Metro shows these lines too, so a long run can be read on the Mac.
  console.log(`[rnbgu] ${kind} ${text}`);
  notify();
};

export const clearLog = (): void => {
  entries = [];
  notify();
};

export const getLog = (): LogEntry[] => entries;

export const subscribeLog = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

export const clock = (ms: number): string =>
  new Date(ms).toISOString().slice(11, 23);
