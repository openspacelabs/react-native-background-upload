// The fake must load without react-native, so a Jest factory that mocks
// react-native can require it. This factory fails the suite if anything
// requires react-native at runtime.
jest.mock('react-native', () => {
  throw new Error('testing.ts must not load react-native at runtime');
});

import type { EnqueueEntry } from '../registry';
import { createFakeNative, type SettledEvent } from '../testing';
import type { RequestRow } from '../types';

const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const entry = (overrides: Partial<EnqueueEntry> = {}): EnqueueEntry => ({
  id: 'a',
  key: 'item.create',
  varsJson: '{"n":1,"note":null}',
  descriptor: {
    url: 'https://x/1',
    method: 'PATCH',
    dataJson: '{"status":null}',
    headers: { Authorization: 'Bearer t' },
    expiresAt: 99,
  },
  ...overrides,
});

const row = (overrides: Partial<RequestRow> = {}): RequestRow => ({
  id: 'legacy',
  key: 'legacy.upload',
  vars: null,
  state: 'error',
  bytesSent: 0,
  totalBytes: 0,
  attempts: 3,
  updatedAt: 1,
  ...overrides,
});

const flush = async (): Promise<void> => {
  for (let i = 0; i < 3; i++) {
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
};

describe('enqueue', () => {
  it('stores the entry with vars and data parsed back, and resolves with the id', async () => {
    const fake = createFakeNative();
    const raw = entry();
    await expect(fake.enqueue(raw)).resolves.toBe('a');
    expect(fake.entries).toEqual([
      {
        id: 'a',
        key: 'item.create',
        vars: { n: 1, note: null },
        descriptor: {
          url: 'https://x/1',
          method: 'PATCH',
          data: { status: null },
          headers: { Authorization: 'Bearer t' },
          expiresAt: 99,
        },
        raw,
      },
    ]);
  });

  it('leaves data out for a bodiless entry', async () => {
    const fake = createFakeNative();
    await fake.enqueue(entry({ descriptor: { url: 'https://x' } }));
    expect(fake.entries[0]!.descriptor).toEqual({ url: 'https://x' });
  });

  it('adds a queued row and emits it as a state event', async () => {
    const fake = createFakeNative();
    const states: RequestRow[] = [];
    fake.onState((r) => {
      states.push(r as RequestRow);
    });
    await fake.enqueue(entry());
    const expected = expect.objectContaining({
      id: 'a',
      key: 'item.create',
      vars: { n: 1, note: null },
      state: 'queued',
      attempts: 0,
    });
    expect(fake.getRequests()).toEqual([expected]);
    expect(states).toEqual([expected]);
  });

  it('returns copies from getRequests', async () => {
    const fake = createFakeNative();
    await fake.enqueue(entry());
    (fake.getRequests()[0] as RequestRow).state = 'running';
    expect((fake.getRequests()[0] as RequestRow).state).toBe('queued');
  });
});

describe('queue controls', () => {
  it('records configure, setWifiOnly and updateHeaders', async () => {
    const fake = createFakeNative();
    fake.configure({ lifetimeMs: 5 });
    await fake.setWifiOnly(true);
    await fake.updateHeaders({ Authorization: 'Bearer u' });
    expect(fake.calls).toMatchObject({
      configure: [{ lifetimeMs: 5 }],
      setWifiOnly: [true],
      updateHeaders: [{ Authorization: 'Bearer u' }],
    });
  });

  it('pauses live rows and resumes them to queued', async () => {
    const fake = createFakeNative();
    await fake.enqueue(entry());
    fake.seedRows([row()]);
    await fake.pause();
    expect(fake.getRequests().map((r) => (r as RequestRow).state)).toEqual([
      'paused',
      'error',
    ]);
    await fake.resume();
    expect(fake.getRequests().map((r) => (r as RequestRow).state)).toEqual([
      'queued',
      'error',
    ]);
    expect(fake.calls.pause).toBe(1);
    expect(fake.calls.resume).toBe(1);
  });

  it('settles a live entry cancelled on cancel(), as native does', async () => {
    const fake = createFakeNative();
    const events: SettledEvent[] = [];
    fake.onSettled((e) => {
      events.push(e as SettledEvent);
    });
    await fake.enqueue(entry());
    await fake.cancel('a');
    expect(fake.calls.cancel).toEqual(['a']);
    expect(events).toEqual([
      expect.objectContaining({
        id: 'a',
        kind: 'cancelled',
        cancelReason: 'user',
        state: 'cancelled',
      }),
    ]);
    expect(fake.getRequests()).toEqual([
      expect.objectContaining({ state: 'cancelled' }),
    ]);
    await fake.ackEvents([events[0]!.eventId]);
    expect(fake.getRequests()).toEqual([]);
  });

  it('forgets a settled entry and its unacked outcomes on cancel()', async () => {
    const fake = createFakeNative();
    fake.seedRows([row()]);
    fake.seedUnacknowledged([
      fake.buildSettled('legacy', {
        kind: 'error',
        error: { errorKind: 'http' },
      }),
    ]);
    await fake.cancel('legacy');
    expect(fake.getRequests()).toEqual([]);
    await expect(fake.getUnacknowledgedEvents()).resolves.toEqual([]);
  });

  it('resolves cancel() of an unknown id as a no-op', async () => {
    const fake = createFakeNative();
    await expect(fake.cancel('nope')).resolves.toBeUndefined();
    expect(fake.calls.cancel).toEqual(['nope']);
  });
});

describe('emitters', () => {
  it('delivers emit helpers to subscribers and stops after remove()', () => {
    const fake = createFakeNative();
    const progress = jest.fn();
    const notification = jest.fn();
    const sub = fake.onProgress(progress);
    fake.onNotification(notification);
    expect(fake.listenerCount('progress')).toBe(1);
    fake.emit.progress({ id: 'a', bytesSent: 1, totalBytes: 2 });
    fake.emit.notification();
    sub.remove();
    fake.emit.progress({ id: 'a', bytesSent: 2, totalBytes: 2 });
    expect(progress).toHaveBeenCalledTimes(1);
    expect(notification).toHaveBeenCalledTimes(1);
    expect(fake.listenerCount('progress')).toBe(0);
  });

  it('removes one subscription at a time for a handler registered twice', () => {
    const fake = createFakeNative();
    const handler = jest.fn();
    const first = fake.onAttempt(handler);
    fake.onAttempt(handler);
    first.remove();
    first.remove();
    expect(fake.listenerCount('attempt')).toBe(1);
  });
});

describe('buildSettled', () => {
  it('fills eventId, key, vars, url and method from the stored entry', async () => {
    const fake = createFakeNative();
    await fake.enqueue(entry());
    const event = fake.buildSettled('a', { kind: 'completed' });
    expect(event.eventId).toMatch(UUID);
    expect(event).toEqual({
      eventId: event.eventId,
      id: 'a',
      key: 'item.create',
      vars: { n: 1, note: null },
      at: expect.any(Number),
      attempts: 1,
      deliveries: 1,
      state: 'completed',
      bytesSent: 0,
      totalBytes: 0,
      url: 'https://x/1',
      method: 'PATCH',
      kind: 'completed',
      response: { status: 200, bodyTruncated: false },
    });
  });

  it('uses the last part for a chunked upload and sets no status', async () => {
    const fake = createFakeNative();
    await fake.enqueue(
      entry({
        descriptor: {
          method: 'PUT',
          file: '/f',
          parts: [
            { url: 'https://s3/1', range: { start: 0, end: 5 } },
            { url: 'https://s3/2', range: { start: 5, end: 9 } },
          ],
        },
      }),
    );
    const event = fake.buildSettled('a', { kind: 'completed' });
    expect(event).toMatchObject({
      url: 'https://s3/2',
      method: 'PUT',
      partIndex: 1,
      response: { bodyTruncated: false },
    });
    expect(event.kind === 'completed' && event.response.status).toBe(undefined);
  });

  it('applies overrides and fills error defaults', async () => {
    const fake = createFakeNative();
    await fake.enqueue(entry());
    const event = fake.buildSettled(
      'a',
      {
        kind: 'error',
        error: { errorKind: 'http', response: { status: 409 } },
      },
      { attempts: 4, deliveries: 2, requestId: 'r1' },
    );
    expect(event).toMatchObject({
      attempts: 4,
      deliveries: 2,
      requestId: 'r1',
      state: 'error',
      kind: 'error',
      error: {
        errorKind: 'http',
        message: 'http',
        response: { status: 409, bodyTruncated: false },
      },
    });
  });

  it('mints a new eventId every time, across fakes', async () => {
    const one = createFakeNative();
    const two = createFakeNative();
    await one.enqueue(entry());
    await two.enqueue(entry());
    const ids = new Set([
      one.buildSettled('a', { kind: 'completed' }).eventId,
      one.buildSettled('a', { kind: 'completed' }).eventId,
      two.buildSettled('a', { kind: 'completed' }).eventId,
    ]);
    expect(ids.size).toBe(3);
  });

  it('throws for an id it has never seen', () => {
    const fake = createFakeNative();
    expect(() => fake.buildSettled('nope', { kind: 'completed' })).toThrow(
      /no entry with id "nope"/,
    );
  });

  it('falls back to a seeded row for key and vars', () => {
    const fake = createFakeNative();
    fake.seedRows([row({ vars: { v9: true } })]);
    expect(fake.buildSettled('legacy', { kind: 'cancelled' })).toMatchObject({
      key: 'legacy.upload',
      vars: { v9: true },
      url: '',
    });
  });
});

describe('settle', () => {
  it('journals, emits, moves the row, and resolves after the ack', async () => {
    const fake = createFakeNative();
    await fake.enqueue(entry());
    const received: SettledEvent[] = [];
    fake.onSettled((e) => {
      received.push(e as SettledEvent);
      void fake.ackEvents([(e as SettledEvent).eventId]);
    });
    const event = await fake.settle('a', { kind: 'completed' });
    expect(received).toEqual([event]);
    expect(fake.ackedEventIds).toEqual([event.eventId]);
    expect(fake.getRequests()).toEqual([]);
    await expect(fake.getUnacknowledgedEvents()).resolves.toEqual([]);
  });

  it('keeps an acked error row', async () => {
    const fake = createFakeNative();
    await fake.enqueue(entry());
    fake.onSettled((e) => {
      void fake.ackEvents([(e as SettledEvent).eventId]);
    });
    await fake.settle('a', { kind: 'error', error: { errorKind: 'network' } });
    expect(fake.getRequests()).toEqual([
      expect.objectContaining({ id: 'a', state: 'error' }),
    ]);
  });

  it('journals with deliveries 0 when no listener exists, and replay counts 1', async () => {
    const fake = createFakeNative({ ackTimeoutMs: 50 });
    await fake.enqueue(entry());
    const pending = fake.settle('a', { kind: 'completed' });
    const [replayed] = await fake.getUnacknowledgedEvents();
    expect(replayed).toMatchObject({ id: 'a', deliveries: 1 });
    await fake.ackEvents([(replayed as SettledEvent).eventId]);
    await expect(pending).resolves.toMatchObject({ id: 'a' });
  });

  it('rejects with a clear message when no ack comes in time', async () => {
    const fake = createFakeNative({ ackTimeoutMs: 10 });
    await fake.enqueue(entry());
    fake.onSettled(() => {});
    await expect(fake.settle('a', { kind: 'completed' })).rejects.toThrow(
      /was not acknowledged within 10 ms/,
    );
  });
});

describe('failNext', () => {
  it('rejects the next call once with the native code', async () => {
    const fake = createFakeNative();
    fake.failNext('enqueue', 'E_RUNNING', 'running');
    await expect(fake.enqueue(entry())).rejects.toMatchObject({
      code: 'E_RUNNING',
      message: 'running',
    });
    expect(fake.entries).toEqual([]);
    await expect(fake.enqueue(entry())).resolves.toBe('a');
  });

  it('makes getRequests throw', () => {
    const fake = createFakeNative();
    fake.failNext('getRequests', 'E_STORAGE');
    expect(() => fake.getRequests()).toThrow('E_STORAGE');
    expect(fake.getRequests()).toEqual([]);
  });
});

describe('reset', () => {
  it('clears the records and keeps the subscriptions', async () => {
    const fake = createFakeNative();
    const settled = jest.fn();
    fake.onSettled(settled);
    await fake.enqueue(entry());
    fake.configure({});
    await fake.pause();
    const before = fake.buildSettled('a', { kind: 'completed' }).eventId;
    fake.failNext('cancel', 'E_STORAGE');
    fake.reset();
    await flush();
    expect(fake.entries).toEqual([]);
    expect(fake.getRequests()).toEqual([]);
    expect(fake.ackedEventIds).toEqual([]);
    expect(fake.calls).toEqual({
      configure: [],
      pause: 0,
      resume: 0,
      cancel: [],
      setWifiOnly: [],
      updateHeaders: [],
    });
    await expect(fake.cancel('a')).resolves.toBeUndefined();
    expect(fake.listenerCount('settled')).toBe(1);
    await fake.enqueue(entry());
    expect(fake.buildSettled('a', { kind: 'completed' }).eventId).not.toBe(
      before,
    );
  });

  it('resolves a pending settle() so it does not reject after reset', async () => {
    jest.useFakeTimers();
    try {
      const fake = createFakeNative({ ackTimeoutMs: 50 });
      await fake.enqueue(entry());
      fake.onSettled(() => {});
      const pending = fake.settle('a', { kind: 'completed' });
      fake.reset();
      expect(jest.getTimerCount()).toBe(0);
      jest.advanceTimersByTime(100);
      await expect(pending).resolves.toMatchObject({ id: 'a' });
    } finally {
      jest.useRealTimers();
    }
  });
});
