import { createDelivery, type SettledEvent } from '../delivery';
import type { AnyDefinition } from '../registry';
import type { StateEvent } from '../types';

const flush = async (rounds = 5): Promise<void> => {
  for (let i = 0; i < rounds; i++) {
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
};

// Delivery yields one macrotask after a tracked mutate() settles, and Node
// does not run a setTimeout(0) inside a few setImmediate rounds. The wait is
// generous so a millisecond boundary between the two timers cannot reorder them.
const tick = (): Promise<void> =>
  new Promise<void>((resolve) => setTimeout(resolve, 20));

const deferred = <T = void>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

const completed = (over: Partial<SettledEvent> = {}): SettledEvent => ({
  eventId: 'e1',
  id: 'u1',
  key: 'k',
  vars: { n: 1 },
  at: 1000,
  attempts: 2,
  requestId: 'req-9',
  kind: 'completed',
  response: { status: 200, body: '{"ok":true}', bodyTruncated: false },
  state: 'completed',
  ...over,
});

const setup = (
  definitions: Record<string, AnyDefinition> = {},
  journal: SettledEvent[] = [],
  handlerWarningMs?: number,
) => {
  const handlers: Array<(e: unknown) => void> = [];
  const native = {
    getUnacknowledgedEvents: jest.fn(async () => journal),
    ackEvents: jest.fn(async () => true),
    onSettled: jest.fn((handler: (e: unknown) => void) => {
      handlers.push(handler);
      return { remove: jest.fn() } as never;
    }),
  };
  const stateEvents: StateEvent[] = [];
  const warn = jest.fn();
  const delivery = createDelivery({
    native,
    lookup: (key) => definitions[key],
    emitState: (e) => stateEvents.push(e),
    warn,
    handlerWarningMs,
  });
  const emit = (event: SettledEvent) => handlers.forEach((h) => h(event));
  return { ...delivery, native, emit, stateEvents, warn, handlers };
};

describe('replay', () => {
  it('does nothing before start()', async () => {
    const onSuccess = jest.fn();
    const { native } = setup(
      { k: { key: 'k', request: jest.fn(), onSuccess } },
      [completed()],
    );
    await flush();
    expect(native.getUnacknowledgedEvents).not.toHaveBeenCalled();
    expect(native.onSettled).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('drains the journal after start() and acks each delivered event', async () => {
    const onSuccess = jest.fn();
    const { start, native } = setup(
      { k: { key: 'k', request: jest.fn(), onSuccess } },
      [completed({ eventId: 'a' }), completed({ eventId: 'b', id: 'u2' })],
    );
    start();
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(2);
    expect(native.ackEvents).toHaveBeenCalledWith(['a']);
    expect(native.ackEvents).toHaveBeenCalledWith(['b']);
  });

  it('subscribes to onSettled once and does not replay on a second start()', async () => {
    const { start, native } = setup({}, []);
    start();
    start();
    await flush();
    expect(native.onSettled).toHaveBeenCalledTimes(1);
    expect(native.getUnacknowledgedEvents).toHaveBeenCalledTimes(1);
  });

  it('buffers live events that arrive during the drain until the journal has delivered', async () => {
    const order: string[] = [];
    const journalGate = deferred<SettledEvent[]>();
    const { start, native, emit } = setup({
      k: {
        key: 'k',
        request: jest.fn(),
        onSuccess: (_d: unknown, _v: unknown, meta: { id: string }) => {
          order.push(meta.id);
        },
      },
    });
    native.getUnacknowledgedEvents.mockReturnValueOnce(journalGate.promise);
    start();
    emit(completed({ eventId: 'live', id: 'live-id' }));
    await flush();
    expect(order).toEqual([]);
    journalGate.resolve([completed({ eventId: 'old', id: 'old-id' })]);
    await flush();
    expect(order).toEqual(['old-id', 'live-id']);
  });

  it('delivers a live event straight away once live', async () => {
    const onSuccess = jest.fn();
    const { start, emit, native } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess },
    });
    start();
    await flush();
    emit(completed({ eventId: 'x' }));
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(native.ackEvents).toHaveBeenCalledWith(['x']);
  });

  it('still goes live when the drain fails, and warns', async () => {
    const onSuccess = jest.fn();
    const { start, emit, native, warn } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess },
    });
    native.getUnacknowledgedEvents.mockRejectedValueOnce(new Error('disk'));
    start();
    await flush();
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/getUnacknowledgedEvents failed/),
      expect.any(Error),
    );
    emit(completed());
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });
});

describe('dedupe', () => {
  it('delivers one eventId once, even when journaled and emitted live', async () => {
    const onSuccess = jest.fn();
    const { start, emit, native } = setup(
      { k: { key: 'k', request: jest.fn(), onSuccess } },
      [completed({ eventId: 'same' })],
    );
    start();
    emit(completed({ eventId: 'same' }));
    await flush();
    emit(completed({ eventId: 'same' }));
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(native.ackEvents).toHaveBeenCalledTimes(1);
  });

  it('drops and warns on an event without an eventId', async () => {
    const onSuccess = jest.fn();
    const { start, emit, warn } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess },
    });
    start();
    await flush();
    emit({ ...completed(), eventId: undefined as unknown as string });
    await flush();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/without an eventId/),
      expect.anything(),
    );
  });
});

describe('ordering against mutate()', () => {
  it('waits for the in-flight mutate() of the same id before delivering', async () => {
    const onSuccess = jest.fn();
    const { start, emit, trackMutate } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess },
    });
    start();
    await flush();
    const enqueue = deferred<string>();
    trackMutate('u1', enqueue.promise);
    emit(completed());
    await flush();
    expect(onSuccess).not.toHaveBeenCalled();
    enqueue.resolve('u1');
    await tick();
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it('runs the handler after every continuation on the mutate() promise, not just after enqueue', async () => {
    // The registry registers trackMutate before mutate() awaits the same
    // promise, and the caller awaits mutate() after that. Both reactions are
    // microtasks that run before the handler.
    const order: string[] = [];
    const { start, emit, trackMutate } = setup({
      k: {
        key: 'k',
        request: jest.fn(),
        onSuccess: () => {
          order.push('handler');
        },
      },
    });
    start();
    await flush();
    const enqueue = deferred<string>();
    trackMutate('u1', enqueue.promise);
    const mutateResult = enqueue.promise.then((id) => ({ id }));
    void mutateResult.then(({ id }) => order.push(`caller got ${id}`));
    emit(completed());
    enqueue.resolve('u1');
    await mutateResult;
    // No flush between the resolve and this read: the caller's continuation
    // has to run first on its own.
    await tick();
    expect(order).toEqual(['caller got u1', 'handler']);
  });

  it('delivers once a tracked mutate() rejects', async () => {
    const onSuccess = jest.fn();
    const { start, emit, trackMutate } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess },
    });
    start();
    await flush();
    const enqueue = deferred<string>();
    trackMutate('u1', enqueue.promise);
    emit(completed());
    enqueue.reject(new Error('nope'));
    await tick();
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it('does not wait on a mutate() for a different id', async () => {
    const onSuccess = jest.fn();
    const { start, emit, trackMutate } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess },
    });
    start();
    await flush();
    trackMutate('other', deferred<string>().promise);
    emit(completed());
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });
});

describe('unknown key', () => {
  it('emits an unhandled-key state row and does not ack', async () => {
    const { start, emit, native, stateEvents, warn } = setup({});
    start();
    await flush();
    emit(
      completed({
        key: 'gone',
        state: 'completed',
        bytesSent: 10,
        totalBytes: 10,
      }),
    );
    await flush();
    expect(native.ackEvents).not.toHaveBeenCalled();
    expect(stateEvents).toEqual([
      {
        id: 'u1',
        key: 'gone',
        vars: { n: 1 },
        state: 'completed',
        bytesSent: 10,
        totalBytes: 10,
        attempts: 2,
        updatedAt: 1000,
        reason: 'unhandled-key',
      },
    ]);
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/no definition for key "gone"/),
    );
  });

  it('defaults the byte counters to 0 when the event has none', async () => {
    const { start, emit, stateEvents } = setup({});
    start();
    await flush();
    emit(completed({ key: 'gone' }));
    await flush();
    expect(stateEvents[0]).toMatchObject({ bytesSent: 0, totalBytes: 0 });
  });
});

describe('completed', () => {
  it('passes the RawResponse to onSuccess when there is no parser, with vars and meta', async () => {
    const onSuccess = jest.fn();
    const { start, emit } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess },
    });
    start();
    await flush();
    const event = completed();
    emit(event);
    await flush();
    expect(onSuccess).toHaveBeenCalledWith(
      event.response,
      { n: 1 },
      {
        id: 'u1',
        key: 'k',
        at: 1000,
        attempts: 2,
        requestId: 'req-9',
      },
    );
  });

  it('parses the JSON body, runs the parser, and passes its result to onSuccess', async () => {
    const onSuccess = jest.fn();
    const response = jest.fn((raw: unknown) => (raw as { ok: boolean }).ok);
    const { start, emit, native } = setup({
      k: { key: 'k', request: jest.fn(), response, onSuccess },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    expect(response).toHaveBeenCalledWith({ ok: true });
    expect(onSuccess).toHaveBeenCalledWith(true, { n: 1 }, expect.anything());
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });

  it('gives the parser undefined when the body is absent or empty', async () => {
    const response = jest.fn(() => 'parsed');
    const { start, emit } = setup({
      k: { key: 'k', request: jest.fn(), response },
    });
    start();
    await flush();
    emit(completed({ eventId: 'a', response: { bodyTruncated: false } }));
    emit(
      completed({ eventId: 'b', response: { body: '', bodyTruncated: false } }),
    );
    await flush();
    expect(response).toHaveBeenCalledTimes(2);
    expect(response).toHaveBeenNthCalledWith(1, undefined);
    expect(response).toHaveBeenNthCalledWith(2, undefined);
  });

  it('calls onError with errorKind truncated when a parser is set and the body was cut', async () => {
    const onSuccess = jest.fn();
    const onError = jest.fn();
    const response = jest.fn();
    const { start, emit, native } = setup({
      k: { key: 'k', request: jest.fn(), response, onSuccess, onError },
    });
    start();
    await flush();
    const raw = { status: 200, body: '{"partial', bodyTruncated: true };
    emit(completed({ response: raw }));
    await flush();
    expect(response).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(
      {
        errorKind: 'truncated',
        message: expect.stringMatching(/1 MB/),
        response: raw,
      },
      { n: 1 },
      expect.anything(),
    );
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });

  it('passes a truncated body to onSuccess untouched when there is no parser', async () => {
    const onSuccess = jest.fn();
    const onError = jest.fn();
    const { start, emit } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess, onError },
    });
    start();
    await flush();
    const raw = { status: 200, body: 'x', bodyTruncated: true };
    emit(completed({ response: raw }));
    await flush();
    expect(onSuccess).toHaveBeenCalledWith(raw, { n: 1 }, expect.anything());
    expect(onError).not.toHaveBeenCalled();
  });

  it('routes a parser throw to onError as unknown and still acks', async () => {
    const onSuccess = jest.fn();
    const onError = jest.fn();
    const { start, emit, native } = setup({
      k: {
        key: 'k',
        request: jest.fn(),
        response: () => {
          throw new Error('bad shape');
        },
        onSuccess,
        onError,
      },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(
      { errorKind: 'unknown', message: 'bad shape' },
      { n: 1 },
      expect.anything(),
    );
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });

  it('treats a body that is not JSON as a parser failure', async () => {
    const onError = jest.fn();
    const { start, emit } = setup({
      k: { key: 'k', request: jest.fn(), response: (x) => x, onError },
    });
    start();
    await flush();
    emit(completed({ response: { body: 'not json', bodyTruncated: false } }));
    await flush();
    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({ errorKind: 'unknown' }),
      { n: 1 },
      expect.anything(),
    );
  });

  it('acks a completed outcome whose definition has no onSuccess', async () => {
    const { start, emit, native } = setup({
      k: { key: 'k', request: jest.fn() },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });
});

describe('error', () => {
  it('calls onError with the outcome error, vars and meta, then acks', async () => {
    const onError = jest.fn();
    const onSuccess = jest.fn();
    const { start, emit, native } = setup({
      k: { key: 'k', request: jest.fn(), onError, onSuccess },
    });
    start();
    await flush();
    const error = {
      errorKind: 'http' as const,
      message: '422',
      response: { status: 422, body: '{}', bodyTruncated: false },
    };
    emit(
      completed({ kind: 'error', state: 'error', response: undefined, error }),
    );
    await flush();
    expect(onError).toHaveBeenCalledWith(
      error,
      { n: 1 },
      expect.objectContaining({ id: 'u1' }),
    );
    expect(onSuccess).not.toHaveBeenCalled();
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });
});

describe('cancelled', () => {
  it('calls no handler and acks', async () => {
    const onError = jest.fn();
    const onSuccess = jest.fn();
    const { start, emit, native } = setup({
      k: { key: 'k', request: jest.fn(), onError, onSuccess },
    });
    start();
    await flush();
    emit(
      completed({
        kind: 'cancelled',
        state: 'cancelled',
        response: undefined,
        cancelReason: 'user',
      }),
    );
    await flush();
    expect(onError).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });
});

describe('ack after the handler', () => {
  it('acks only after the handler promise resolves', async () => {
    const gate = deferred();
    const { start, emit, native } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess: () => gate.promise },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    expect(native.ackEvents).not.toHaveBeenCalled();
    gate.resolve();
    await flush();
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });

  it('does not ack when the handler rejects, and warns', async () => {
    const { start, emit, native, warn } = setup({
      k: {
        key: 'k',
        request: jest.fn(),
        onSuccess: async () => {
          throw new Error('handler down');
        },
      },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    expect(native.ackEvents).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/handler for u1 rejected/),
      expect.any(Error),
    );
  });

  it('does not ack when the handler throws synchronously', async () => {
    const { start, emit, native } = setup({
      k: {
        key: 'k',
        request: jest.fn(),
        onSuccess: () => {
          throw new Error('sync');
        },
      },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    expect(native.ackEvents).not.toHaveBeenCalled();
  });

  it('warns when ackEvents itself fails', async () => {
    const { start, emit, native, warn } = setup({
      k: { key: 'k', request: jest.fn() },
    });
    native.ackEvents.mockRejectedValueOnce(new Error('journal locked'));
    start();
    await flush();
    emit(completed());
    await flush();
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/ackEvents failed for e1/),
      expect.any(Error),
    );
  });
});

describe('slow handler warning', () => {
  beforeEach(() => {
    jest.useFakeTimers({ doNotFake: ['setImmediate', 'nextTick'] });
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('warns once at 30 s when the handler has not settled', async () => {
    const gate = deferred();
    const { start, emit, warn, native } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess: () => gate.promise },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    await jest.advanceTimersByTimeAsync(29_999);
    expect(warn).not.toHaveBeenCalled();
    await jest.advanceTimersByTimeAsync(1);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/"k" handler for u1 has not settled after 30 s/),
    );
    await jest.advanceTimersByTimeAsync(60_000);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(native.ackEvents).not.toHaveBeenCalled();
    gate.resolve();
    await flush();
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });

  it('does not warn when the handler settles in time', async () => {
    const { start, emit, warn } = setup({
      k: { key: 'k', request: jest.fn(), onSuccess: async () => undefined },
    });
    start();
    await flush();
    emit(completed());
    await flush();
    await jest.advanceTimersByTimeAsync(60_000);
    expect(warn).not.toHaveBeenCalled();
  });
});
