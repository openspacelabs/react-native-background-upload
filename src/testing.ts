/**
 * A fake native module for tests. Pass it to `createUploadClient({ native })`
 * and the real registry and delivery run on top of it: vars cap, header
 * merge, descriptor checks, eventId dedupe, per-id order, and ack after the
 * handler.
 *
 * This file imports only types, so it loads without react-native. A Jest
 * factory that mocks react-native or its TurboModuleRegistry can require it.
 *
 * The fake keeps a small model of the native queue. enqueue() adds a row,
 * settle() journals an outcome and emits it, and ackEvents() forgets a
 * completed or cancelled row. It emits a 'state' row for each of these
 * transitions, as native does. It never sends HTTP and never retries.
 *
 * The fake does not model the same-id rules of enqueue() (resume, replace,
 * E_RUNNING, re-emit). A second enqueue() on an id only overwrites its row.
 * It also does not model the header merge of updateHeaders(). It records the
 * patch and merges nothing. Script those cases with failNext() and
 * seedUnacknowledged().
 */
import type { SettledEvent } from './delivery';
import type { Spec } from './NativeRNFileUploader';
import type { EnqueueEntry } from './registry';
import type {
  AttemptEvent,
  CancelReason,
  ErrorKind,
  Json,
  OutcomeError,
  ProgressEvent,
  RawResponse,
  RequestDescriptor,
  RequestRow,
  RequestState,
} from './types';

export type { SettledEvent } from './delivery';
export type { EnqueueEntry } from './registry';

/** One enqueue() call, with `vars` and `data` parsed back from JSON. */
export type FakeEntry = {
  id: string;
  key: string;
  vars: Json;
  /** The descriptor as mutate() sent it, with `dataJson` parsed to `data`. */
  descriptor: RequestDescriptor;
  /** The entry exactly as it crossed to native. */
  raw: EnqueueEntry;
};

/**
 * The outcome to settle. Fields you omit get defaults: a completed response
 * is status 200 (none for a chunked upload) with `bodyTruncated: false`, an
 * error message is the errorKind, and a cancel reason is 'user'.
 */
export type FakeOutcome =
  | { kind: 'completed'; response?: Partial<RawResponse> }
  | {
      kind: 'error';
      error: {
        errorKind: ErrorKind;
        message?: string;
        response?: Partial<RawResponse>;
        partIndex?: number;
      };
    }
  | { kind: 'cancelled'; cancelReason?: CancelReason };

/** Fields of the SettledEvent to set instead of the defaults. */
export type SettledOverrides = Partial<
  Omit<SettledEvent, 'kind' | 'response' | 'error' | 'cancelReason'>
>;

/** The methods whose next call can be made to fail with failNext(). */
export type FailableMethod =
  | 'enqueue'
  | 'pause'
  | 'resume'
  | 'cancel'
  | 'setWifiOnly'
  | 'updateHeaders'
  | 'getRequests'
  | 'getUnacknowledgedEvents'
  | 'ackEvents';

export type FakeNativeOptions = {
  /**
   * How long settle() waits for the ack before it rejects. Default 2000 ms.
   * The wait uses setTimeout, so with Jest fake timers it only ends when the
   * timers advance.
   */
  ackTimeoutMs?: number;
};

export type FakeNative = Spec & {
  /** Every enqueue() call, in order. */
  readonly entries: FakeEntry[];
  /** The arguments of the calls that have no other record. */
  readonly calls: {
    configure: object[];
    pause: number;
    resume: number;
    cancel: string[];
    setWifiOnly: boolean[];
    updateHeaders: Record<string, string>[];
  };
  /** Every eventId passed to ackEvents(), in order. */
  readonly ackedEventIds: string[];
  /** Fires one event on the matching emitter. It does not change any row. */
  readonly emit: {
    state: (row: RequestRow) => void;
    progress: (event: ProgressEvent) => void;
    attempt: (event: AttemptEvent) => void;
    settled: (event: SettledEvent) => void;
    notification: () => void;
  };
  /** The number of live subscriptions on one emitter. */
  listenerCount: (
    event: 'state' | 'progress' | 'attempt' | 'settled' | 'notification',
  ) => number;
  /**
   * Builds the SettledEvent for the stored entry `id`: a new eventId, key and
   * vars from the entry, url and method from its descriptor, attempts 1, and
   * deliveries 1. It does not emit or journal the event.
   */
  buildSettled: (
    id: string,
    outcome: FakeOutcome,
    overrides?: SettledOverrides,
  ) => SettledEvent;
  /**
   * Journals the outcome of entry `id`, moves its row to the outcome state,
   * and emits it on onSettled when a listener exists. Resolves with the event
   * after delivery acks it. Rejects after `ackTimeoutMs` when no ack comes:
   * the handler rejected, the key has no definition, or configure() was not
   * called. To test those cases, create the fake with a short
   * `ackTimeoutMs` and expect settle() to reject.
   */
  settle: (
    id: string,
    outcome: FakeOutcome,
    overrides?: SettledOverrides,
  ) => Promise<SettledEvent>;
  /** Adds or replaces rows, for example legacy rows that native imported. */
  seedRows: (rows: RequestRow[]) => void;
  /**
   * Adds outcomes to the journal. getUnacknowledgedEvents() returns them, so
   * configure() replays them.
   */
  seedUnacknowledged: (events: SettledEvent[]) => void;
  /**
   * The next call of `method` rejects (getRequests throws) with an Error
   * that carries `code`, as a native rejection does.
   */
  failNext: (method: FailableMethod, code: string, message?: string) => void;
  /**
   * Clears entries, rows, journal, calls, acks and queued failures. A
   * settle() that still waits for its ack resolves now, so it cannot reject
   * later inside another test. Keeps the subscriptions, because a client subscribes one time, at its first
   * configure(). New eventIds never repeat old ones, so delivery's dedupe
   * stays correct.
   */
  reset: () => void;
};

/** The subscription a codegen event emitter returns. */
type Subscription = ReturnType<Spec['onState']>;

type Emitter<T> = {
  subscribe: (handler: (event: T) => void | Promise<void>) => Subscription;
  fire: (event: T) => void;
  count: () => number;
};

const makeEmitter = <T>(): Emitter<T> => {
  const handlers: Array<(event: T) => void | Promise<void>> = [];
  return {
    // Each subscription has its own entry, so remove() drops only that one.
    subscribe: (handler) => {
      const entry = (event: T) => handler(event);
      handlers.push(entry);
      const remove = () => {
        const index = handlers.indexOf(entry);
        if (index >= 0) {
          handlers.splice(index, 1);
        }
      };
      // RN's legacy typings add eventType, key and subscriber to the
      // subscription. A codegen emitter returns only remove() at runtime.
      return { remove } as unknown as Subscription;
    },
    fire: (event) => {
      handlers.slice().forEach((handler) => {
        handler(event);
      });
    },
    count: () => handlers.length,
  };
};

// Process-wide, so two fakes never mint the same eventId.
let eventCounter = 0;
const nextEventId = (): string => {
  eventCounter += 1;
  return `00000000-0000-4000-8000-${eventCounter
    .toString(16)
    .padStart(12, '0')}`;
};

const LIVE_STATES: RequestState[] = [
  'queued',
  'running',
  'awaiting-auth',
  'paused',
];

const nativeError = (code: string, message?: string): Error =>
  Object.assign(new Error(message ?? code), { code });

const toDescriptor = (raw: EnqueueEntry['descriptor']): RequestDescriptor => {
  const { dataJson, ...rest } = raw;
  return dataJson === undefined
    ? rest
    : { ...rest, data: JSON.parse(dataJson) as unknown };
};

const toResponse = (
  response: Partial<RawResponse> | undefined,
  defaultStatus: number | undefined,
): RawResponse => ({
  ...(defaultStatus === undefined || response?.status !== undefined
    ? {}
    : { status: defaultStatus }),
  bodyTruncated: false,
  ...response,
});

/** Builds an in-memory native module that implements the TurboModule spec. */
export const createFakeNative = (
  options: FakeNativeOptions = {},
): FakeNative => {
  const ackTimeoutMs = options.ackTimeoutMs ?? 2000;
  const entries: FakeEntry[] = [];
  const rows = new Map<string, RequestRow>();
  const journal: SettledEvent[] = [];
  const ackedEventIds: string[] = [];
  const calls: FakeNative['calls'] = {
    configure: [],
    pause: 0,
    resume: 0,
    cancel: [],
    setWifiOnly: [],
    updateHeaders: [],
  };
  const failures = new Map<FailableMethod, Error[]>();
  const ackWaiters = new Map<string, () => void>();

  const state = makeEmitter<RequestRow>();
  const progress = makeEmitter<ProgressEvent>();
  const attempt = makeEmitter<AttemptEvent>();
  const settled = makeEmitter<SettledEvent>();
  const notification = makeEmitter<object>();

  const takeFailure = (method: FailableMethod): Error | undefined => {
    const queue = failures.get(method);
    return queue?.shift();
  };

  /** Runs `body` unless a failure is queued for `method`. */
  const run = async <T>(method: FailableMethod, body: () => T): Promise<T> => {
    const failure = takeFailure(method);
    if (failure) {
      throw failure;
    }
    return body();
  };

  const setRow = (row: RequestRow): void => {
    rows.set(row.id, row);
    state.fire({ ...row });
  };

  const lastEntry = (id: string): FakeEntry | undefined => {
    for (let i = entries.length - 1; i >= 0; i--) {
      const entry = entries[i];
      if (entry?.id === id) {
        return entry;
      }
    }
    return undefined;
  };

  const buildSettled: FakeNative['buildSettled'] = (
    id,
    outcome,
    overrides = {},
  ) => {
    const entry = lastEntry(id);
    const row = rows.get(id);
    const key = overrides.key ?? entry?.key ?? row?.key;
    if (key === undefined) {
      throw new Error(
        `createFakeNative: no entry with id "${id}". Call mutate() or seedRows() first, or pass key in overrides.`,
      );
    }
    const descriptor = entry?.descriptor;
    const parts = descriptor?.parts;
    const lastPart = parts?.[parts.length - 1];
    const base = {
      eventId: nextEventId(),
      id,
      key,
      vars: entry?.vars ?? row?.vars ?? null,
      at: Date.now(),
      attempts: 1,
      deliveries: 1,
      state: outcome.kind,
      bytesSent: row?.bytesSent ?? 0,
      totalBytes: row?.totalBytes ?? 0,
      url: descriptor?.url ?? lastPart?.url ?? '',
      method: descriptor?.method ?? 'POST',
      ...(lastPart ? { partIndex: parts!.length - 1 } : {}),
    };
    const defaultStatus = parts ? undefined : 200;
    switch (outcome.kind) {
      case 'completed':
        return {
          ...base,
          ...overrides,
          kind: 'completed',
          response: toResponse(outcome.response, defaultStatus),
        };
      case 'error': {
        const { response, message, ...rest } = outcome.error;
        const error: OutcomeError = {
          ...rest,
          message: message ?? rest.errorKind,
          ...(response ? { response: toResponse(response, undefined) } : {}),
        };
        return { ...base, ...overrides, kind: 'error', error };
      }
      case 'cancelled':
        return {
          ...base,
          ...overrides,
          kind: 'cancelled',
          cancelReason: outcome.cancelReason ?? 'user',
        };
    }
  };

  /** Native's order: journal, move the row, then emit to live listeners. */
  const journalAndEmit = (event: SettledEvent): void => {
    const live = settled.count() > 0;
    const journaled: SettledEvent = live ? event : { ...event, deliveries: 0 };
    journal.push(journaled);
    const row = rows.get(event.id);
    if (row) {
      setRow({
        ...row,
        state: event.state,
        attempts: event.attempts,
        updatedAt: event.at,
      });
    }
    if (live) {
      settled.fire({ ...journaled });
    }
  };

  const settle: FakeNative['settle'] = (id, outcome, overrides) => {
    const event = buildSettled(id, outcome, overrides);
    const acked = new Promise<SettledEvent>((resolve, reject) => {
      const timer = setTimeout(() => {
        ackWaiters.delete(event.eventId);
        reject(
          new Error(
            `createFakeNative: outcome ${event.eventId} ("${event.key}", id ${id}) was not acknowledged within ${ackTimeoutMs} ms. The handler rejected or has not settled, the key has no definition, or configure() was not called.`,
          ),
        );
      }, ackTimeoutMs);
      ackWaiters.set(event.eventId, () => {
        clearTimeout(timer);
        resolve(event);
      });
    });
    journalAndEmit(event);
    return acked;
  };

  const fake: FakeNative = {
    entries,
    calls,
    ackedEventIds,

    configure: (config) => {
      calls.configure.push(config);
    },

    enqueue: (input) =>
      run('enqueue', () => {
        const raw = input as EnqueueEntry;
        const vars = JSON.parse(raw.varsJson) as Json;
        entries.push({
          id: raw.id,
          key: raw.key,
          vars,
          descriptor: toDescriptor(raw.descriptor),
          raw,
        });
        const prior = rows.get(raw.id);
        setRow({
          id: raw.id,
          key: raw.key,
          vars,
          state: 'queued',
          bytesSent: 0,
          totalBytes: prior?.totalBytes ?? 0,
          attempts: 0,
          updatedAt: Date.now(),
        });
        return raw.id;
      }),

    pause: () =>
      run('pause', () => {
        calls.pause += 1;
        rows.forEach((row) => {
          if (LIVE_STATES.includes(row.state) && row.state !== 'paused') {
            setRow({ ...row, state: 'paused', updatedAt: Date.now() });
          }
        });
      }),

    resume: () =>
      run('resume', () => {
        calls.resume += 1;
        rows.forEach((row) => {
          if (row.state === 'paused') {
            setRow({ ...row, state: 'queued', updatedAt: Date.now() });
          }
        });
      }),

    cancel: (id) =>
      run('cancel', () => {
        calls.cancel.push(id);
        const row = rows.get(id);
        if (!row) {
          return;
        }
        if (LIVE_STATES.includes(row.state)) {
          journalAndEmit(
            buildSettled(id, { kind: 'cancelled', cancelReason: 'user' }),
          );
          return;
        }
        // A settled entry is forgotten now, with its unacked outcomes.
        rows.delete(id);
        for (let i = journal.length - 1; i >= 0; i--) {
          if (journal[i]?.id === id) {
            journal.splice(i, 1);
          }
        }
      }),

    setWifiOnly: (enabled) =>
      run('setWifiOnly', () => {
        calls.setWifiOnly.push(enabled);
      }),

    updateHeaders: (patch) =>
      run('updateHeaders', () => {
        calls.updateHeaders.push({ ...(patch as Record<string, string>) });
      }),

    getRequests: () => {
      const failure = takeFailure('getRequests');
      if (failure) {
        throw failure;
      }
      return Array.from(rows.values(), (row) => ({ ...row }));
    },

    // Each replay counts as one more delivery, as native counts it.
    getUnacknowledgedEvents: () =>
      run('getUnacknowledgedEvents', () =>
        journal.map((event, index) => {
          const next = { ...event, deliveries: (event.deliveries ?? 0) + 1 };
          journal[index] = next;
          return { ...next };
        }),
      ),

    ackEvents: (ids) =>
      run('ackEvents', () => {
        ids.forEach((eventId) => {
          ackedEventIds.push(eventId);
          const index = journal.findIndex((e) => e.eventId === eventId);
          if (index < 0) {
            return;
          }
          const [event] = journal.splice(index, 1);
          const row = event ? rows.get(event.id) : undefined;
          // An acked completed or cancelled outcome forgets its row. An error
          // row stays until cancel() or a same-id enqueue().
          if (
            event &&
            row &&
            event.kind !== 'error' &&
            row.state === event.state
          ) {
            rows.delete(event.id);
          }
        });
        // Waiters resolve after the journal and rows are up to date.
        ids.forEach((eventId) => {
          const waiter = ackWaiters.get(eventId);
          if (waiter) {
            ackWaiters.delete(eventId);
            waiter();
          }
        });
      }),

    onState: (handler) =>
      state.subscribe(handler as (row: RequestRow) => void | Promise<void>),
    onProgress: (handler) => progress.subscribe(handler),
    onAttempt: (handler) =>
      attempt.subscribe(handler as (e: AttemptEvent) => void | Promise<void>),
    onSettled: (handler) =>
      settled.subscribe(handler as (e: SettledEvent) => void | Promise<void>),
    onNotification: (handler) => notification.subscribe(handler),

    emit: {
      state: (row) => state.fire(row),
      progress: (event) => progress.fire(event),
      attempt: (event) => attempt.fire(event),
      settled: (event) => settled.fire(event),
      notification: () => notification.fire({}),
    },

    listenerCount: (event) =>
      ({ state, progress, attempt, settled, notification }[event].count()),

    buildSettled,
    settle,

    seedRows: (seeded) => {
      seeded.forEach((row) => rows.set(row.id, { ...row }));
    },

    seedUnacknowledged: (events) => {
      journal.push(...events.map((event) => ({ ...event })));
    },

    failNext: (method, code, message) => {
      const queue = failures.get(method) ?? [];
      queue.push(nativeError(code, message));
      failures.set(method, queue);
    },

    reset: () => {
      entries.length = 0;
      rows.clear();
      journal.length = 0;
      ackedEventIds.length = 0;
      calls.configure.length = 0;
      calls.pause = 0;
      calls.resume = 0;
      calls.cancel.length = 0;
      calls.setWifiOnly.length = 0;
      calls.updateHeaders.length = 0;
      failures.clear();
      // Each waiter clears its own timer and resolves its settle().
      ackWaiters.forEach((waiter) => waiter());
      ackWaiters.clear();
    },
  };
  return fake;
};
