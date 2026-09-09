import type { EventSubscription } from 'react-native';
import type { Spec } from './NativeRNFileUploader';
import type { AnyDefinition } from './registry';
import type {
  Json,
  Meta,
  Outcome,
  RawResponse,
  RequestState,
  StateEvent,
} from './types';

/**
 * The journaled terminal outcome that native emits on `onSettled` and returns
 * from `getUnacknowledgedEvents()`. Both carry the same shape, so a live event
 * and a replayed one take the same path here.
 */
export type SettledEvent = {
  eventId: string;
  id: string;
  key: string;
  vars: Json;
  /** Native outcome time, epoch ms. */
  at: number;
  attempts: number;
  requestId?: string;
  /** The entry's real state, for the unhandled-key row. */
  state: RequestState;
  bytesSent?: number;
  totalBytes?: number;
} & Outcome;

export const HANDLER_WARNING_MS = 30_000;

type DeliveryDeps = {
  native: Pick<Spec, 'getUnacknowledgedEvents' | 'ackEvents' | 'onSettled'>;
  lookup: (key: string) => AnyDefinition | undefined;
  /** Feeds the client's `state` listeners. */
  emitState: (event: StateEvent) => void;
  warn?: (message: string, ...rest: unknown[]) => void;
  handlerWarningMs?: number;
};

export type Delivery = {
  /** Subscribes, drains the journal, then goes live. A second call is a no-op. */
  start: () => void;
  /** Delivery for `id` waits until this promise has settled. */
  trackMutate: (id: string, pending: Promise<unknown>) => void;
};

const errorMessage = (e: unknown): string =>
  e instanceof Error ? e.message : String(e);

/**
 * Routes settled outcomes to the definitions' handlers and acknowledges them
 * afterwards. Rules: journal before emit is native's job; here it is dedupe by
 * eventId, drop a malformed event, run one id's outcomes in order, wait for
 * the id's in-flight mutate(), ack a cancelled outcome, look up the key, run
 * the handler, ack after its promise resolves. An unknown key or a rejected
 * handler leaves the outcome unacknowledged, so native redelivers it at the
 * next launch.
 */
export const createDelivery = ({
  native,
  lookup,
  emitState,
  warn = console.warn,
  handlerWarningMs = HANDLER_WARNING_MS,
}: DeliveryDeps): Delivery => {
  const seen = new Set<string>();
  const pendingMutates = new Map<string, Promise<void>>();
  // The tail of each id's delivery chain. Outcomes of one id run in order,
  // one handler at a time. Different ids run concurrently.
  const lanes = new Map<string, Promise<void>>();
  let subscription: EventSubscription | undefined;
  // Live events that arrive while the journal drains wait here, so replayed
  // outcomes deliver first.
  const buffer: SettledEvent[] = [];
  let live = false;

  const trackMutate = (id: string, pending: Promise<unknown>): void => {
    const settled = pending.then(
      () => undefined,
      () => undefined,
    );
    const prior = pendingMutates.get(id);
    const chain = prior ? prior.then(() => settled) : settled;
    pendingMutates.set(id, chain);
    void chain.then(() => {
      if (pendingMutates.get(id) === chain) {
        pendingMutates.delete(id);
      }
    });
  };

  const ack = async (eventId: string): Promise<void> => {
    try {
      await native.ackEvents([eventId]);
    } catch (e) {
      warn(`delivery: ackEvents failed for ${eventId}`, e);
    }
  };

  const unhandledRow = (event: SettledEvent): StateEvent => ({
    id: event.id,
    key: event.key,
    vars: event.vars,
    state: event.state,
    bytesSent: event.bytesSent ?? 0,
    totalBytes: event.totalBytes ?? 0,
    attempts: event.attempts,
    updatedAt: event.at,
    reason: 'unhandled-key',
  });

  const invoke = async (
    event: SettledEvent,
    definition: AnyDefinition,
    meta: Meta,
  ): Promise<void> => {
    const { vars } = event;
    if (event.kind === 'error') {
      await definition.onError?.(event.error, vars, meta);
      return;
    }
    if (event.kind !== 'completed') {
      return;
    }
    const response: RawResponse = event.response ?? { bodyTruncated: false };
    if (!definition.response) {
      await definition.onSuccess?.(response, vars, meta);
      return;
    }
    if (response.bodyTruncated) {
      await definition.onError?.(
        {
          errorKind: 'truncated',
          message:
            'the response body exceeded the 1 MB cap, so it was not parsed',
          response,
        },
        vars,
        meta,
      );
      return;
    }
    let data: unknown;
    try {
      const parsed: unknown =
        response.body === undefined || response.body === ''
          ? undefined
          : JSON.parse(response.body);
      data = definition.response(parsed);
    } catch (e) {
      await definition.onError?.(
        { errorKind: 'unknown', message: errorMessage(e) },
        vars,
        meta,
      );
      return;
    }
    await definition.onSuccess?.(data, vars, meta);
  };

  /** True when the handler settled, so the outcome may be acknowledged. */
  const runHandler = async (
    event: SettledEvent,
    definition: AnyDefinition,
  ): Promise<boolean> => {
    const meta: Meta = {
      id: event.id,
      key: event.key,
      at: event.at,
      attempts: event.attempts,
      requestId: event.requestId,
    };
    const timer = setTimeout(() => {
      warn(
        `delivery: the "${event.key}" handler for ${
          event.id
        } has not settled after ${
          handlerWarningMs / 1000
        } s. The outcome stays unacknowledged until it does.`,
      );
    }, handlerWarningMs);
    try {
      await invoke(event, definition, meta);
      return true;
    } catch (e) {
      warn(
        `delivery: the "${event.key}" handler for ${event.id} rejected. The outcome stays unacknowledged and redelivers at the next launch.`,
        e,
      );
      return false;
    } finally {
      clearTimeout(timer);
    }
  };

  /** Runs `task` after the previous delivery for `id` has settled. */
  const enqueueForId = (id: string, task: () => Promise<void>): void => {
    const prior = lanes.get(id) ?? Promise.resolve();
    const next = prior.then(task).catch((e) => {
      warn(`delivery: unexpected failure while delivering for ${id}`, e);
    });
    lanes.set(id, next);
    void next.then(() => {
      if (lanes.get(id) === next) {
        lanes.delete(id);
      }
    });
  };

  /** Delivery for `id` waits until the caller of mutate() has the id. */
  const waitForMutate = async (id: string): Promise<void> => {
    const pending = pendingMutates.get(id);
    if (!pending) {
      return;
    }
    await pending;
    // The enqueue promise settles before mutate()'s own await and before
    // the caller's continuation, both microtasks. A macrotask puts the
    // handler after them, so the caller has the id before any handler
    // sees it.
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  };

  /** The outcome's own steps, run inside its id's lane. */
  const route = async (event: SettledEvent): Promise<void> => {
    await waitForMutate(event.id);
    // A cancelled outcome has no handler, so it acks whether or not the key
    // is still defined.
    if (event.kind === 'cancelled') {
      await ack(event.eventId);
      return;
    }
    const definition = lookup(event.key);
    if (!definition) {
      warn(
        `delivery: no definition for key "${event.key}" (id ${event.id}). The outcome stays unacknowledged.`,
      );
      emitState(unhandledRow(event));
      return;
    }
    if (await runHandler(event, definition)) {
      await ack(event.eventId);
    }
  };

  const isWellFormed = (event: SettledEvent): boolean =>
    typeof event.key === 'string' &&
    typeof event.kind === 'string' &&
    typeof event.id === 'string';

  const deliver = (event: SettledEvent): void => {
    if (typeof event?.eventId !== 'string') {
      warn('delivery: dropped a settled event without an eventId', event);
      return;
    }
    if (seen.has(event.eventId)) {
      return;
    }
    seen.add(event.eventId);
    // A journal written by an older native build can carry another shape.
    // Such an entry is neither acked nor routed. The seen set keeps the
    // warning to one per eventId.
    if (!isWellFormed(event)) {
      warn(
        `delivery: dropped a malformed settled event ${event.eventId}. It has no string key, kind and id.`,
        event,
      );
      return;
    }
    enqueueForId(event.id, () => route(event));
  };

  const start = (): void => {
    if (subscription) {
      return;
    }
    // Subscribe first, so nothing that settles during the drain is missed.
    // The eventId dedupe absorbs an event that shows up in both.
    subscription = native.onSettled((raw) => {
      const event = raw as SettledEvent;
      if (live) {
        deliver(event);
      } else {
        buffer.push(event);
      }
    });
    void native
      .getUnacknowledgedEvents()
      .then(
        (events) => {
          (events as SettledEvent[]).forEach(deliver);
        },
        (e) => warn('delivery: getUnacknowledgedEvents failed', e),
      )
      .then(() => {
        live = true;
        buffer.splice(0).forEach(deliver);
      });
  };

  return { start, trackMutate };
};
