/**
 * The client's in-memory copy of the native queue rows, for display. It is
 * read from native when the first listener arrives and patched by each
 * `state` event, which carries the full row. A second view overlays the bytes
 * of `progress` events. The library closes the gaps where native changes a
 * row with no event, because its own JS makes those calls: the ack of a
 * completed or cancelled outcome, and cancel() on a settled entry.
 *
 * Nothing here sends, acknowledges, or cancels anything. A failure here can
 * only make a row show late or stay longer.
 */
import { useMemo, useSyncExternalStore } from 'react';
import type {
  AddListener,
  Json,
  ProgressEvent,
  RequestRow,
  RequestsFilter,
  RequestState,
  StateEvent,
} from './types';

type Listener = () => void;

const LIVE_STATES: readonly RequestState[] = [
  'queued',
  'running',
  'awaiting-auth',
  'paused',
];

/** Deep equality for JSON values, as `vars` and rows hold them. */
export const jsonEqual = (a: unknown, b: unknown): boolean => {
  if (a === b) {
    return true;
  }
  if (
    typeof a !== 'object' ||
    typeof b !== 'object' ||
    a === null ||
    b === null
  ) {
    return false;
  }
  if (Array.isArray(a) !== Array.isArray(b)) {
    return false;
  }
  if (Array.isArray(a)) {
    const other = b as unknown[];
    return (
      a.length === other.length && a.every((v, i) => jsonEqual(v, other[i]))
    );
  }
  const left = a as Record<string, Json | undefined>;
  const right = b as Record<string, Json | undefined>;
  const leftKeys = Object.keys(left).filter((k) => left[k] !== undefined);
  const rightKeys = Object.keys(right).filter((k) => right[k] !== undefined);
  return (
    leftKeys.length === rightKeys.length &&
    leftKeys.every((k) => jsonEqual(left[k], right[k]))
  );
};

const sameItems = <T>(a: readonly T[], b: readonly T[]): boolean =>
  a.length === b.length && a.every((item, i) => item === b[i]);

export const matches = (row: RequestRow, filter: RequestsFilter): boolean =>
  (filter.includeCancelled === true || row.state !== 'cancelled') &&
  (filter.key === undefined || row.key === filter.key) &&
  (filter.id === undefined || row.id === filter.id);

/**
 * A read of `source()` narrowed by `filter`. It returns the same array while
 * the rows it keeps are the same objects, so a row it leaves out can change
 * without a new result. `useSyncExternalStore` then re-renders only when the
 * filtered rows change.
 */
export const createSelector = (
  source: () => readonly RequestRow[],
  filter: RequestsFilter,
): (() => readonly RequestRow[]) => {
  let lastSource: readonly RequestRow[] | undefined;
  let lastResult: readonly RequestRow[] | undefined;
  return () => {
    const rows = source();
    if (rows === lastSource && lastResult) {
      return lastResult;
    }
    const next = rows.filter((row) => matches(row, filter));
    lastSource = rows;
    if (!lastResult || !sameItems(next, lastResult)) {
      lastResult = next;
    }
    return lastResult;
  };
};

type RequestCacheDeps = {
  /** The client's getRequests() without a filter. May throw. */
  readNative: () => RequestRow[];
  /** The client's addListener(), so unhandled-key rows arrive too. */
  addListener: AddListener;
  warn: (message: string, detail?: unknown) => void;
};

export type RequestCache = {
  /** Every cached row, cancelled ones included. Stable until a row changes. */
  rows: () => readonly RequestRow[];
  /** `rows()` with the bytes of the progress events since each row's last state. */
  rowsWithProgress: () => readonly RequestRow[];
  /** Called when a row appears, changes, or goes. Returns the unsubscribe. */
  subscribe: (listener: Listener) => () => void;
  /** Also called on every progress event for a cached row. */
  subscribeWithProgress: (listener: Listener) => () => void;
  /**
   * For reads outside React: with no listener, reads native first, so the
   * rows are never out of date.
   */
  read: () => readonly RequestRow[];
  /** The ack of an outcome of `id` resolved. */
  acked: (outcome: { id: string; kind: string }) => void;
  /** cancel(id) resolved. */
  cancelled: (id: string) => void;
};

export const createRequestCache = ({
  readNative,
  addListener,
  warn,
}: RequestCacheDeps): RequestCache => {
  // Undefined until the first read.
  let rows: readonly RequestRow[] | undefined;
  let withProgress: readonly RequestRow[] | undefined;
  // The bytes of the last progress event of each row, and the overlaid row
  // built from them, so an unchanged row keeps its identity.
  const progress = new Map<string, ProgressEvent>();
  const overlaid = new Map<
    string,
    { base: RequestRow; event: ProgressEvent; row: RequestRow }
  >();
  const rowListeners = new Set<Listener>();
  const progressListeners = new Set<Listener>();
  let subscriptions: Array<{ remove: () => void }> = [];
  // True from a native read until the next microtask. Native events arrive
  // as new tasks, so none can be missed between a read and a subscribe in
  // the same task, and the subscribe does not need to read again.
  let fresh = false;

  const notify = (listeners: Set<Listener>): void => {
    listeners.forEach((listener) => listener());
  };

  /** True when the row had progress to forget. */
  const forgetProgress = (id: string): boolean => {
    overlaid.delete(id);
    return progress.delete(id);
  };

  const commit = (next: readonly RequestRow[]): void => {
    if (next === rows) {
      return;
    }
    rows = next;
    withProgress = undefined;
    notify(rowListeners);
    notify(progressListeners);
  };

  /** Native's rows, or undefined when the read throws. */
  const readSafely = (): RequestRow[] | undefined => {
    try {
      const current = readNative();
      fresh = true;
      void Promise.resolve().then(() => {
        fresh = false;
      });
      return current;
    } catch (e) {
      warn(
        'requests: getRequests failed, so the cached rows were not checked against native',
        e,
      );
      return undefined;
    }
  };

  /**
   * Native's rows, with the cached object kept for each row that did not
   * change, and the cached array kept when no row changed.
   */
  const reconcile = (
    cachedRows: readonly RequestRow[],
    current: RequestRow[],
  ): readonly RequestRow[] => {
    const cached = new Map(cachedRows.map((row) => [row.id, row]));
    const next = current.map((row) => {
      const old = cached.get(row.id);
      return old && jsonEqual(old, row) ? old : row;
    });
    return sameItems(next, cachedRows) ? cachedRows : next;
  };

  /** Checks every row against native. Keeps the rows when the read fails. */
  const refresh = (): void => {
    const current = readSafely();
    if (!rows) {
      // The first read: nobody has seen rows yet, so nobody is notified.
      rows = current ?? [];
      return;
    }
    if (current) {
      commit(reconcile(rows, current));
    }
  };

  const ensureRows = (): readonly RequestRow[] => {
    if (!rows) {
      refresh();
    }
    return rows ?? [];
  };

  const remove = (id: string): void => {
    forgetProgress(id);
    if (rows?.some((row) => row.id === id)) {
      commit(rows.filter((row) => row.id !== id));
    }
  };

  // A state event carries the full row, so it replaces the cached one. Its
  // bytes are the row's own, so the progress seen before it is out of date.
  const onState = (event: StateEvent): void => {
    // The cached row is a plain row; the unhandled-key reason is the event's.
    const row: StateEvent = { ...event };
    delete row.reason;
    const hadProgress = forgetProgress(row.id);
    if (!rows) {
      return;
    }
    const index = rows.findIndex((cached) => cached.id === row.id);
    if (index < 0) {
      commit([...rows, row]);
      return;
    }
    if (jsonEqual(rows[index], row)) {
      // The row is the same, but the progress view showed newer bytes.
      if (hadProgress) {
        withProgress = undefined;
        notify(progressListeners);
      }
      return;
    }
    commit(rows.map((cached, i) => (i === index ? row : cached)));
  };

  const onProgress = (event: ProgressEvent): void => {
    if (!rows?.some((row) => row.id === event.id)) {
      return;
    }
    progress.set(event.id, event);
    withProgress = undefined;
    notify(progressListeners);
  };

  const listening = (): boolean =>
    rowListeners.size + progressListeners.size > 0;

  const start = (): void => {
    subscriptions = [
      addListener('state', onState),
      addListener('progress', onProgress),
    ];
    // Rows kept from an earlier listener can have missed events.
    if (!fresh || !rows) {
      refresh();
    }
  };

  // The rows stay, so a component that replaces the last listener in one
  // commit does not render twice. The next start() checks them against native.
  const stop = (): void => {
    subscriptions.forEach((subscription) => subscription.remove());
    subscriptions = [];
    // Whatever changes from here on is checked at the next start().
    fresh = false;
    progress.clear();
    overlaid.clear();
    withProgress = undefined;
  };

  const subscriber =
    (listeners: Set<Listener>) =>
    (listener: Listener): (() => void) => {
      const first = !listening();
      // A wrapper, so the same function subscribed twice is two entries.
      const entry = () => listener();
      listeners.add(entry);
      if (first) {
        start();
      }
      return () => {
        if (!listeners.delete(entry)) {
          return;
        }
        if (!listening()) {
          stop();
        }
      };
    };

  const rowsWithProgress = (): readonly RequestRow[] => {
    if (withProgress) {
      return withProgress;
    }
    const base = ensureRows();
    const next = base.map((row) => {
      const event = progress.get(row.id);
      if (!event) {
        return row;
      }
      const memo = overlaid.get(row.id);
      if (memo && memo.base === row && memo.event === event) {
        return memo.row;
      }
      const merged = {
        ...row,
        bytesSent: event.bytesSent,
        totalBytes: event.totalBytes,
      };
      overlaid.set(row.id, { base: row, event, row: merged });
      return merged;
    });
    withProgress = sameItems(next, base) ? base : next;
    return withProgress;
  };

  const acked = ({ id, kind }: { id: string; kind: string }): void => {
    // Native forgets a row at the ack of its completed or cancelled outcome.
    // An error row stays until cancel() or a same-id mutate().
    if (kind !== 'completed' && kind !== 'cancelled') {
      return;
    }
    const row = rows?.find((cached) => cached.id === id);
    if (!row) {
      return;
    }
    if (row.state === kind) {
      remove(id);
      return;
    }
    // The row moved on, for example a cancelled entry that a same-id
    // mutate() reopened, whose old ack does not forget it. Ask native.
    const current = readSafely();
    if (!current) {
      return;
    }
    const native = current.find((candidate) => candidate.id === id);
    if (!native) {
      remove(id);
    } else if (!jsonEqual(native, row)) {
      onState(native);
    }
  };

  const cancelled = (id: string): void => {
    // A settled entry is forgotten at once, with no event. A live one
    // settles 'cancelled' with an event, and its ack forgets it.
    const row = rows?.find((cached) => cached.id === id);
    if (row && !LIVE_STATES.includes(row.state)) {
      remove(id);
    }
  };

  return {
    rows: ensureRows,
    rowsWithProgress,
    subscribe: subscriber(rowListeners),
    subscribeWithProgress: subscriber(progressListeners),
    read: () => {
      if (!listening()) {
        refresh();
      }
      return rows ?? [];
    },
    acked,
    cancelled,
  };
};

/**
 * The hook behind `useRequests()` and `useRequestsWithProgress()`. The
 * filter's fields, not the object, key the selector, so an inline filter
 * object does not make a new result each render.
 */
export const useRequestRows = (
  subscribe: (listener: Listener) => () => void,
  source: () => readonly RequestRow[],
  filter: RequestsFilter | undefined,
): readonly RequestRow[] => {
  const key = filter?.key;
  const id = filter?.id;
  const includeCancelled = filter?.includeCancelled === true;
  const getSnapshot = useMemo(
    () => createSelector(source, { key, id, includeCancelled }),
    [source, key, id, includeCancelled],
  );
  return useSyncExternalStore(subscribe, getSnapshot);
};
