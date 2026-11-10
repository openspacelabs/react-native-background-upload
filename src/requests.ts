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

/**
 * Deep equality for JSON values, as `vars` and rows hold them. A field set to
 * `undefined` counts as absent. It allocates nothing, because it runs on
 * every state event and on every row of a native read.
 */
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
  const isArray = Array.isArray(a);
  if (isArray !== Array.isArray(b)) {
    return false;
  }
  if (isArray) {
    const left = a as unknown[];
    const right = b as unknown[];
    if (left.length !== right.length) {
      return false;
    }
    for (let i = 0; i < left.length; i++) {
      if (!jsonEqual(left[i], right[i])) {
        return false;
      }
    }
    return true;
  }
  const left = a as Record<string, unknown>;
  const right = b as Record<string, unknown>;
  // Count the defined fields of each side; equal counts plus every left
  // field equal means the same set of fields.
  let fields = 0;
  for (const k in left) {
    if (left[k] === undefined) {
      continue;
    }
    fields++;
    if (!jsonEqual(left[k], right[k])) {
      return false;
    }
  }
  for (const k in right) {
    if (right[k] !== undefined) {
      fields--;
    }
  }
  return fields === 0;
};

/**
 * Row equality. The scalar fields that change on most transitions are
 * compared first, so a changed row is found without walking its `vars`.
 * `vars` is always compared: a same-id mutate() can replace it.
 */
export const rowEqual = (a: RequestRow, b: RequestRow): boolean =>
  a === b ||
  (a.id === b.id &&
    a.state === b.state &&
    a.updatedAt === b.updatedAt &&
    a.attempts === b.attempts &&
    a.bytesSent === b.bytesSent &&
    a.totalBytes === b.totalBytes &&
    a.nextAttemptAt === b.nextAttemptAt &&
    a.key === b.key &&
    jsonEqual(a, b));

const sameItems = <T>(a: readonly T[], b: readonly T[]): boolean => {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      return false;
    }
  }
  return true;
};

export const matches = (row: RequestRow, filter: RequestsFilter): boolean =>
  (filter.includeCancelled === true || row.state !== 'cancelled') &&
  (filter.key === undefined || row.key === filter.key) &&
  (filter.id === undefined || row.id === filter.id);

// One id index per rows array. The cache makes a new array for each change,
// so every id-filtered selector of that change shares one build.
const indexes = new WeakMap<
  readonly RequestRow[],
  ReadonlyMap<string, RequestRow>
>();

/** The rows of `rows` by id, built once per array. */
export const indexById = (
  rows: readonly RequestRow[],
): ReadonlyMap<string, RequestRow> => {
  let index = indexes.get(rows);
  if (!index) {
    const built = new Map<string, RequestRow>();
    for (const row of rows) {
      built.set(row.id, row);
    }
    indexes.set(rows, built);
    index = built;
  }
  return index;
};

const EMPTY: readonly RequestRow[] = [];

/**
 * A read of `source()` narrowed by `filter`. It returns the same array while
 * the rows it keeps are the same objects, so a row it leaves out can change
 * without a new result. `useSyncExternalStore` then re-renders only when the
 * filtered rows change. With an `id`, it looks the row up in the shared
 * index and does not walk the rows.
 */
export const createSelector = (
  source: () => readonly RequestRow[],
  filter: RequestsFilter,
): (() => readonly RequestRow[]) => {
  let lastSource: readonly RequestRow[] | undefined;
  let lastResult: readonly RequestRow[] | undefined;
  if (filter.id !== undefined) {
    const { id } = filter;
    let lastRow: RequestRow | undefined;
    return () => {
      const rows = source();
      if (rows === lastSource && lastResult) {
        return lastResult;
      }
      lastSource = rows;
      const row = indexById(rows).get(id);
      const kept = row && matches(row, filter) ? row : undefined;
      if (!lastResult || kept !== lastRow) {
        lastRow = kept;
        lastResult = kept ? [kept] : EMPTY;
      }
      return lastResult;
    };
  }
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

// One selector per source and filter without an id, shared by every hook
// that asks for it, so all unfiltered or same-key hooks filter the rows once
// per change. The number of entries is the number of distinct keys used.
const shared = new WeakMap<
  () => readonly RequestRow[],
  Map<string, () => readonly RequestRow[]>
>();

/** `createSelector()` for a filter without an id, shared per source. */
export const sharedSelector = (
  source: () => readonly RequestRow[],
  filter: Omit<RequestsFilter, 'id'>,
): (() => readonly RequestRow[]) => {
  let bySource = shared.get(source);
  if (!bySource) {
    bySource = new Map();
    shared.set(source, bySource);
  }
  const includeCancelled = filter.includeCancelled === true;
  const name = `${includeCancelled ? 1 : 0}:${filter.key ?? ''}:${
    filter.key === undefined ? 0 : 1
  }`;
  let selector = bySource.get(name);
  if (!selector) {
    selector = createSelector(source, { key: filter.key, includeCancelled });
    bySource.set(name, selector);
  }
  return selector;
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
  let stateSubscription: { remove: () => void } | undefined;
  // Native progress is listened to only while a progress view is mounted.
  let progressSubscription: { remove: () => void } | undefined;

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
    // Progress of a row that left is not needed any more.
    if (progress.size > 0) {
      const index = indexById(next);
      progress.forEach((_event, id) => {
        if (!index.has(id)) {
          forgetProgress(id);
        }
      });
    }
    notify(rowListeners);
    notify(progressListeners);
  };

  /** Native's rows, or undefined when the read throws. */
  const readSafely = (): RequestRow[] | undefined => {
    try {
      return readNative();
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
    const cached = indexById(cachedRows);
    const next = current.map((row) => {
      const old = cached.get(row.id);
      return old && rowEqual(old, row) ? old : row;
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
    if (rows && indexById(rows).has(id)) {
      commit(rows.filter((row) => row.id !== id));
    }
  };

  /** Puts `row` in the cache, in place of the row with its id. */
  const upsert = (row: RequestRow): void => {
    const hadProgress = forgetProgress(row.id);
    if (!rows) {
      return;
    }
    const cached = indexById(rows).get(row.id);
    if (!cached) {
      commit([...rows, row]);
      return;
    }
    if (rowEqual(cached, row)) {
      // The row is the same, but the progress view showed newer bytes.
      if (hadProgress) {
        withProgress = undefined;
        notify(progressListeners);
      }
      return;
    }
    commit(rows.map((other) => (other === cached ? row : other)));
  };

  /** Asks native for one row: replaces it, or drops it when native has none. */
  const refreshId = (id: string): void => {
    const current = readSafely();
    if (!current) {
      return;
    }
    const row = current.find((candidate) => candidate.id === id);
    if (row) {
      upsert(row);
    } else {
      remove(id);
    }
  };

  // A state event carries the full row, so it replaces the cached one. Its
  // bytes are the row's own, so the progress seen before it is out of date.
  const onState = (event: StateEvent): void => {
    if (event.reason === 'unhandled-key') {
      // JS builds this row from the journaled outcome, not from the entry.
      // A same-id mutate() may have reopened the entry since, so ask native.
      if (rows) {
        refreshId(event.id);
      }
      return;
    }
    upsert(event);
  };

  const onProgress = (event: ProgressEvent): void => {
    if (!rows || !indexById(rows).has(event.id)) {
      return;
    }
    progress.set(event.id, event);
    withProgress = undefined;
    notify(progressListeners);
  };

  const listening = (): boolean =>
    rowListeners.size + progressListeners.size > 0;

  // These run inside native's event callbacks. A throw there would surface
  // as an uncaught error and stop the app's later listeners of that event.
  const guarded =
    <E>(name: string, handle: (event: E) => void) =>
    (event: E): void => {
      try {
        handle(event);
      } catch (e) {
        warn(`requests: updating the rows from a ${name} event threw`, e);
      }
    };
  const onStateGuarded = guarded('state', onState);
  const onProgressGuarded = guarded('progress', onProgress);

  const start = (): void => {
    stateSubscription = addListener('state', onStateGuarded);
    // Always check: an event emitted between an earlier read (a render) and
    // this subscribe went to no listener. reconcile() keeps the same array
    // when nothing changed, so this adds no render.
    refresh();
  };

  // The rows stay, so a component that replaces the last listener in one
  // commit does not render twice. The next start() checks them against native.
  const stop = (): void => {
    stateSubscription?.remove();
    stateSubscription = undefined;
  };

  const startProgress = (): void => {
    progressSubscription = addListener('progress', onProgressGuarded);
  };

  const stopProgress = (): void => {
    progressSubscription?.remove();
    progressSubscription = undefined;
    progress.clear();
    overlaid.clear();
    withProgress = undefined;
  };

  const subscriber =
    (listeners: Set<Listener>) =>
    (listener: Listener): (() => void) => {
      const first = !listening();
      const firstProgress =
        listeners === progressListeners && listeners.size === 0;
      // A wrapper, so the same function subscribed twice is two entries.
      const entry = () => listener();
      listeners.add(entry);
      if (firstProgress) {
        startProgress();
      }
      if (first) {
        start();
      }
      return () => {
        if (!listeners.delete(entry)) {
          return;
        }
        if (listeners === progressListeners && listeners.size === 0) {
          stopProgress();
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
    if (progress.size === 0) {
      withProgress = base;
      return base;
    }
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

  /**
   * With nothing listening, the rows may already be out of date and nobody
   * shows them. Drop them instead of patching them: the next subscribe or
   * read reads native. This keeps native reads out of the delivery lane
   * while the journal drains at launch.
   */
  const dropWhenIdle = (): boolean => {
    if (listening()) {
      return false;
    }
    rows = undefined;
    withProgress = undefined;
    return true;
  };

  const acked = ({ id, kind }: { id: string; kind: string }): void => {
    if (dropWhenIdle()) {
      return;
    }
    const row = rows ? indexById(rows).get(id) : undefined;
    if (!row) {
      return;
    }
    if (kind === 'error') {
      // Native keeps an error row. iOS can move a live row to its outcome
      // during the ack with no event, so a row not shown as an error is
      // checked.
      if (row.state !== 'error') {
        refreshId(id);
      }
      return;
    }
    if (kind !== 'completed' && kind !== 'cancelled') {
      return;
    }
    // Native forgets a row at the ack of its completed or cancelled outcome.
    if (row.state === kind) {
      remove(id);
      return;
    }
    // The row moved on, for example a cancelled entry that a same-id
    // mutate() reopened, whose old ack does not forget it; or a live row that
    // native settled and forgot during the ack. Ask native.
    refreshId(id);
  };

  const cancelled = (id: string): void => {
    if (dropWhenIdle()) {
      return;
    }
    // A settled entry is forgotten at once, with no event. A live one
    // settles 'cancelled' with an event, and its ack forgets it.
    const row = rows ? indexById(rows).get(id) : undefined;
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
      if (listening()) {
        return rows ?? [];
      }
      // Nothing listens, so nothing holds these rows: take native's as they
      // are. The next start() reconciles them.
      const current = readSafely();
      if (current) {
        rows = current;
        withProgress = undefined;
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
    () =>
      id === undefined
        ? sharedSelector(source, { key, includeCancelled })
        : createSelector(source, { key, id, includeCancelled }),
    [source, key, id, includeCancelled],
  );
  return useSyncExternalStore(subscribe, getSnapshot);
};
