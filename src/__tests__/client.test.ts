// Define all mocks inside the factory (no outer references) to avoid the
// import-hoisting TDZ trap, then grab handles from the mocked module below.
// The library reaches native through TurboModuleRegistry.getEnforcing, so that
// is what has to be stubbed. The codegen event emitters are plain functions
// that take a handler and return a subscription; the mock records the handlers
// so a test can fire an event. remove() drops that one subscription, as RN's
// EventEmitter does, so the same handler registered twice stays once.
jest.mock('react-native', () => {
  const handlers: Record<string, Array<(e: unknown) => void>> = {};
  const emitter = (name: string) =>
    jest.fn((handler: (e: unknown) => void) => {
      const entry = (e: unknown) => handler(e);
      (handlers[name] ??= []).push(entry);
      return {
        remove: jest.fn(() => {
          handlers[name] = handlers[name]!.filter((h) => h !== entry);
        }),
      };
    });
  const nativeModule = {
    configure: jest.fn(),
    enqueue: jest.fn(async (entry: { id: string }) => entry.id),
    pause: jest.fn(async () => undefined),
    resume: jest.fn(async () => undefined),
    cancel: jest.fn(async () => undefined),
    setWifiOnly: jest.fn(async () => undefined),
    updateHeaders: jest.fn(async () => undefined),
    getRequests: jest.fn(() => []),
    getUnacknowledgedEvents: jest.fn(async () => []),
    ackEvents: jest.fn(async () => undefined),
    takeDiagnostics: jest.fn(async () => []),
    onState: emitter('state'),
    onProgress: emitter('progress'),
    onAttempt: emitter('attempt'),
    onSettled: emitter('settled'),
    onNotification: emitter('notification'),
    __handlers: handlers,
  };
  return {
    Platform: { OS: 'ios' },
    TurboModuleRegistry: {
      getEnforcing: jest.fn(() => nativeModule),
      get: jest.fn(() => nativeModule),
    },
  };
});

import { TurboModuleRegistry } from 'react-native';
import Upload, { chunkPlan, createUploadClient } from '../index';
import { createFakeNative } from '../testing';

/* eslint-disable @typescript-eslint/no-explicit-any */
// Same object the module captured at import time.
const native = (TurboModuleRegistry as any).getEnforcing('RNFileUploader');
const fire = (name: string, event: unknown) =>
  (native.__handlers[name] ?? []).forEach((h: (e: unknown) => void) =>
    h(event),
  );

const flush = async (rounds = 5): Promise<void> => {
  for (let i = 0; i < rounds; i++) {
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
};

const rows = [
  {
    id: 'a',
    key: 'k1',
    vars: null,
    state: 'queued',
    bytesSent: 0,
    totalBytes: 0,
    attempts: 0,
    updatedAt: 1,
  },
  {
    id: 'b',
    key: 'k2',
    vars: null,
    state: 'running',
    bytesSent: 1,
    totalBytes: 2,
    attempts: 1,
    updatedAt: 2,
  },
  {
    id: 'c',
    key: 'k1',
    vars: null,
    state: 'error',
    bytesSent: 0,
    totalBytes: 0,
    attempts: 3,
    updatedAt: 3,
  },
];

beforeEach(() => {
  jest.clearAllMocks();
  Object.keys(native.__handlers).forEach((k) => delete native.__handlers[k]);
});

describe('client shape', () => {
  it('exposes the v10 surface and nothing from v9', () => {
    const client = createUploadClient();
    expect(Object.keys(client).sort()).toEqual(
      [
        'addListener',
        'android',
        'cancel',
        'chunkPlan',
        'configure',
        'define',
        'getRequests',
        'pause',
        'resume',
        'setWifiOnly',
        'takeDiagnostics',
        'updateHeaders',
      ].sort(),
    );
    expect(Object.keys(client.android)).toEqual(['addNotificationListener']);
    expect(client.chunkPlan).toBe(chunkPlan);
  });

  it('exports a default client with the same shape', () => {
    expect(Object.keys(Upload).sort()).toEqual(
      Object.keys(createUploadClient()).sort(),
    );
  });

  it("keeps each client's definitions separate", async () => {
    const a = createUploadClient();
    const b = createUploadClient();
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    a.define({
      key: 'shared',
      request: (_v: null) => ({ url: 'https://x', data: 1 }),
    });
    b.define({
      key: 'shared',
      request: (_v: null) => ({ url: 'https://x', data: 1 }),
    });
    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });
});

describe('configure', () => {
  it('forwards lifetimeMs, retry and the flattened android config', () => {
    const client = createUploadClient();
    const retry = { terminalHttp: { exempt: [] } };
    client.configure({
      lifetimeMs: 1000,
      retry,
      headers: () => ({}),
      android: { notificationTitle: 'Backing up', notificationChannel: 'ch' },
    });
    expect(native.configure).toHaveBeenCalledWith({
      lifetimeMs: 1000,
      retry,
      notificationTitle: 'Backing up',
      notificationChannel: 'ch',
    });
  });

  it('sends the 14 day default lifetime and no retry when neither is given', () => {
    createUploadClient().configure({});
    expect(native.configure).toHaveBeenCalledWith({
      lifetimeMs: 14 * 24 * 60 * 60 * 1000,
    });
    expect(native.configure.mock.calls[0][0]).not.toHaveProperty('retry');
  });

  it('rejects a non-positive lifetime', () => {
    expect(() => createUploadClient().configure({ lifetimeMs: 0 })).toThrow(
      /lifetimeMs/,
    );
    expect(() => createUploadClient().configure({ lifetimeMs: NaN })).toThrow(
      /lifetimeMs/,
    );
  });

  it('rejects a non-positive maxVarsBytes and keeps it on the JS side', () => {
    expect(() =>
      createUploadClient().configure({ maxVarsBytes: 0 }),
    ).toThrow(/maxVarsBytes must be a positive number, got 0/);
    expect(() =>
      createUploadClient().configure({ maxVarsBytes: Infinity }),
    ).toThrow(/maxVarsBytes/);
    createUploadClient().configure({ maxVarsBytes: 64 });
    expect(native.configure.mock.calls.at(-1)![0]).not.toHaveProperty(
      'maxVarsBytes',
    );
  });

  it('applies maxVarsBytes to later mutates', async () => {
    const client = createUploadClient();
    const send = client.define({
      key: 'k',
      request: (_v: { s: string }) => ({ url: 'https://x', data: 1 }),
    });
    client.configure({ maxVarsBytes: 16 });
    await expect(send.mutate({ s: 'a'.repeat(8) })).resolves.toBeDefined();
    await expect(send.mutate({ s: 'a'.repeat(9) })).rejects.toThrow(
      /is 17 bytes; the limit is 16/,
    );
  });

  it('starts replay once; a second call updates settings without replaying', async () => {
    const client = createUploadClient();
    client.configure({});
    client.configure({ lifetimeMs: 5 });
    await flush();
    expect(native.getUnacknowledgedEvents).toHaveBeenCalledTimes(1);
    expect(native.onSettled).toHaveBeenCalledTimes(1);
    expect(native.configure).toHaveBeenCalledTimes(2);
  });

  it('does not touch the journal before configure()', async () => {
    createUploadClient();
    await flush();
    expect(native.getUnacknowledgedEvents).not.toHaveBeenCalled();
    expect(native.onSettled).not.toHaveBeenCalled();
  });

  it('applies the headers provider and lifetime to later mutates', async () => {
    const client = createUploadClient();
    const send = client.define({
      key: 'k',
      request: (_v: null) => ({
        url: 'https://x',
        data: 1,
        headers: { B: '2' },
      }),
    });
    client.configure({ lifetimeMs: 1000, headers: () => ({ A: '1' }) });
    const before = Date.now();
    await send.mutate(null);
    const entry = native.enqueue.mock.calls.at(-1)![0];
    expect(entry.descriptor.headers).toEqual({ A: '1', B: '2' });
    expect(entry.descriptor.expiresAt).toBeGreaterThanOrEqual(before + 1000);
    expect(entry.descriptor.expiresAt).toBeLessThan(before + 1000 + 5000);
  });
});

describe('queue control forwards', () => {
  const client = createUploadClient();

  it('pause with no scope forwards {} (the whole queue)', async () => {
    await client.pause();
    expect(native.pause).toHaveBeenCalledWith({});
    await client.pause({});
    expect(native.pause).toHaveBeenLastCalledWith({});
  });

  it('resume with no scope forwards {} (the whole queue)', async () => {
    await client.resume();
    expect(native.resume).toHaveBeenCalledWith({});
  });

  it('forwards a keys scope as a copy', async () => {
    const keys = ['capture.upload'];
    await client.pause({ keys });
    expect(native.pause).toHaveBeenCalledWith({ keys: ['capture.upload'] });
    expect(native.pause.mock.calls[0][0].keys).not.toBe(keys);
    await client.resume({ keys: ['capture.upload', 'capture.meta'] });
    expect(native.resume).toHaveBeenCalledWith({
      keys: ['capture.upload', 'capture.meta'],
    });
  });

  it('forwards an empty keys list, which changes nothing natively', async () => {
    await client.pause({ keys: [] });
    expect(native.pause).toHaveBeenCalledWith({ keys: [] });
  });

  it.each([
    ['a non-object scope', 'capture', /scope must be an object/],
    ['an array scope', ['capture'], /scope must be an object/],
    ['a null scope', null, /scope must be an object/],
    ['a misspelled field', { key: ['capture'] }, /unknown scope field "key"/],
    ['keys that is not an array', { keys: 'capture' }, /keys must be an array/],
    ['keys: undefined', { keys: undefined }, /keys must be an array/],
    ['an empty key', { keys: ['a', ''] }, /non-empty strings/],
    ['a non-string key', { keys: [1] }, /non-empty strings/],
  ])('rejects %s and never reaches native', async (_name, scope, message) => {
    await expect(client.pause(scope as any)).rejects.toThrow(message);
    await expect(client.resume(scope as any)).rejects.toThrow(message);
    expect(native.pause).not.toHaveBeenCalled();
    expect(native.resume).not.toHaveBeenCalled();
  });

  it('cancel', async () => {
    await client.cancel('u1');
    expect(native.cancel).toHaveBeenCalledWith('u1');
  });

  it('setWifiOnly', async () => {
    await client.setWifiOnly(true);
    expect(native.setWifiOnly).toHaveBeenCalledWith(true);
  });

  it('updateHeaders', async () => {
    await client.updateHeaders({ Authorization: 'Bearer t' });
    expect(native.updateHeaders).toHaveBeenCalledWith({
      Authorization: 'Bearer t',
    });
  });

  it('propagates a native rejection', async () => {
    native.pause.mockRejectedValueOnce(new Error('E_NOT_IMPLEMENTED'));
    await expect(client.pause()).rejects.toThrow('E_NOT_IMPLEMENTED');
  });
});

describe('takeDiagnostics', () => {
  it('returns the native records', async () => {
    const client = createUploadClient();
    const records = [
      { at: 1, level: 'error', message: 'run failed', error: 'java.io.IOException: disk full' },
      { at: 2, level: 'info', message: 'boot sweep restarted queued entries', data: { entries: 3 } },
    ];
    native.takeDiagnostics.mockResolvedValueOnce(records);
    await expect(client.takeDiagnostics()).resolves.toEqual(records);
  });
});

describe('getRequests', () => {
  const client = createUploadClient();

  it('returns the native rows synchronously', () => {
    native.getRequests.mockReturnValueOnce(rows);
    expect(client.getRequests()).toEqual(rows);
  });

  it('filters by key', () => {
    native.getRequests.mockReturnValueOnce(rows);
    expect(client.getRequests({ key: 'k1' }).map((r) => r.id)).toEqual([
      'a',
      'c',
    ]);
  });

  it('filters by id', () => {
    native.getRequests.mockReturnValueOnce(rows);
    expect(client.getRequests({ id: 'b' }).map((r) => r.id)).toEqual(['b']);
  });

  it('applies both filters together', () => {
    native.getRequests.mockReturnValueOnce(rows);
    expect(client.getRequests({ key: 'k1', id: 'b' })).toEqual([]);
    native.getRequests.mockReturnValueOnce(rows);
    expect(client.getRequests({ key: 'k1', id: 'c' }).map((r) => r.id)).toEqual(
      ['c'],
    );
  });

  it('treats an empty filter as no filter', () => {
    native.getRequests.mockReturnValueOnce(rows);
    expect(client.getRequests({})).toEqual(rows);
  });
});

describe('addListener', () => {
  it('maps progress and attempt to their native emitters', () => {
    const client = createUploadClient();
    const progress = jest.fn();
    const attempt = jest.fn();
    client.addListener('progress', progress);
    client.addListener('attempt', attempt);
    expect(native.onProgress).toHaveBeenCalledWith(progress);
    expect(native.onAttempt).toHaveBeenCalledWith(attempt);
    fire('progress', { id: 'u1', bytesSent: 1, totalBytes: 2 });
    fire('attempt', { id: 'u1', outcome: 'error' });
    expect(progress).toHaveBeenCalledWith({
      id: 'u1',
      bytesSent: 1,
      totalBytes: 2,
    });
    expect(attempt).toHaveBeenCalledWith({ id: 'u1', outcome: 'error' });
  });

  it('delivers native state rows and the JS unhandled-key rows to state listeners', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    const client = createUploadClient();
    const state = jest.fn();
    const subscription = client.addListener('state', state);
    expect(native.onState).toHaveBeenCalledWith(state);
    fire('state', rows[0]);
    expect(state).toHaveBeenCalledWith(rows[0]);

    client.configure({});
    await flush();
    fire('settled', {
      eventId: 'e1',
      id: 'x',
      key: 'nobody',
      vars: null,
      at: 5,
      attempts: 1,
      kind: 'completed',
      response: { bodyTruncated: false },
      state: 'completed',
    });
    await flush();
    expect(state).toHaveBeenLastCalledWith(
      expect.objectContaining({
        id: 'x',
        key: 'nobody',
        reason: 'unhandled-key',
      }),
    );
    expect(native.ackEvents).not.toHaveBeenCalled();

    // remove() drops both sources.
    subscription.remove();
    expect(native.__handlers.state).toEqual([]);
    state.mockClear();
    fire('settled', {
      eventId: 'e2',
      id: 'y',
      key: 'nobody',
      vars: null,
      at: 5,
      attempts: 1,
      kind: 'completed',
      state: 'completed',
    });
    await flush();
    expect(state).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledTimes(2);
    warn.mockRestore();
  });

  it('gives each subscription of one state listener its own removal', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    const client = createUploadClient();
    const state = jest.fn();
    const first = client.addListener('state', state);
    client.addListener('state', state);
    client.configure({});
    await flush();
    const settled = (eventId: string) => ({
      eventId,
      id: 'x',
      key: 'nobody',
      vars: null,
      at: 5,
      attempts: 1,
      kind: 'completed',
      response: { bodyTruncated: false },
      state: 'completed',
    });
    fire('state', rows[0]);
    fire('settled', settled('e1'));
    await flush();
    // Two subscriptions, two deliveries from each source.
    expect(state.mock.calls.filter(([e]) => e === rows[0])).toHaveLength(2);
    expect(
      state.mock.calls.filter(([e]) => e.reason === 'unhandled-key'),
    ).toHaveLength(2);

    first.remove();
    state.mockClear();
    fire('state', rows[0]);
    fire('settled', settled('e2'));
    await flush();
    // The surviving subscription still gets both sources, once each.
    expect(state.mock.calls.filter(([e]) => e === rows[0])).toHaveLength(1);
    expect(
      state.mock.calls.filter(([e]) => e.reason === 'unhandled-key'),
    ).toHaveLength(1);
    warn.mockRestore();
  });

  it('keeps the other state listeners and later deliveries when one listener throws', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    const client = createUploadClient();
    const second = jest.fn();
    const onSuccess = jest.fn();
    client.define({
      key: 'known',
      request: (_v: null) => ({ url: 'https://x', data: 1 }),
      onSuccess,
    });
    client.addListener('state', () => {
      throw new Error('listener down');
    });
    client.addListener('state', second);
    client.configure({});
    await flush();
    const settled = (eventId: string, key: string) => ({
      eventId,
      id: 'x',
      key,
      vars: null,
      at: 5,
      attempts: 1,
      kind: 'completed',
      response: { bodyTruncated: false },
      state: 'completed',
    });
    fire('settled', settled('e1', 'nobody'));
    await flush();
    expect(second).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'x', reason: 'unhandled-key' }),
    );
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/state listener threw/),
      expect.any(Error),
    );
    fire('settled', settled('e2', 'known'));
    await flush();
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(native.ackEvents).toHaveBeenCalledWith(['e2']);
    warn.mockRestore();
  });

  it('rejects an unknown event name', () => {
    const client = createUploadClient();
    expect(() => (client.addListener as any)('completed', jest.fn())).toThrow(
      /unknown event completed/,
    );
  });

  it('android.addNotificationListener subscribes to onNotification', () => {
    const client = createUploadClient();
    const listener = jest.fn();
    client.android.addNotificationListener(listener);
    expect(native.onNotification).toHaveBeenCalled();
    fire('notification', {});
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith();
  });
});

describe('end to end', () => {
  it('mutate, then a settled outcome, runs the handler and acks', async () => {
    const client = createUploadClient();
    const onSuccess = jest.fn();
    const create = client.define({
      key: 'item.create',
      request: ({ n }: { n: number }) => ({
        url: `https://x/${n}`,
        data: { n },
      }),
      response: (raw) => (raw as { id: string }).id,
      onSuccess,
    });
    client.configure({ headers: () => ({ Authorization: 'Bearer t' }) });
    await flush();
    const { id } = await create.mutate({ n: 3 }, { id: 'local-3' });
    expect(id).toBe('local-3');
    expect(native.enqueue).toHaveBeenCalledWith({
      id: 'local-3',
      key: 'item.create',
      varsJson: '{"n":3}',
      descriptor: {
        url: 'https://x/3',
        dataJson: '{"n":3}',
        headers: { Authorization: 'Bearer t' },
        expiresAt: expect.any(Number),
      },
    });
    fire('settled', {
      eventId: 'e1',
      id: 'local-3',
      key: 'item.create',
      vars: { n: 3 },
      at: 10,
      attempts: 1,
      requestId: 'r1',
      kind: 'completed',
      response: { status: 201, body: '{"id":"srv-9"}', bodyTruncated: false },
      state: 'completed',
    });
    await flush();
    expect(onSuccess).toHaveBeenCalledWith(
      'srv-9',
      { n: 3 },
      {
        id: 'local-3',
        key: 'item.create',
        at: 10,
        attempts: 1,
        requestId: 'r1',
        deliveries: 1,
      },
    );
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });

  it('runs the handler only after the caller of mutate() has the id', async () => {
    const client = createUploadClient();
    const order: string[] = [];
    const create = client.define({
      key: 'item.create',
      request: ({ n }: { n: number }) => ({ url: `https://x/${n}`, data: { n } }),
      onError: (_error, _vars, meta) => {
        order.push(`handler ${meta.id}`);
      },
    });
    client.configure({});
    await flush();
    let resolveEnqueue!: (id: string) => void;
    native.enqueue.mockImplementationOnce(
      (entry: { id: string }) =>
        new Promise<string>((resolve) => {
          resolveEnqueue = () => resolve(entry.id);
        }),
    );
    const pending = create.mutate({ n: 1 }).then(({ id }) => {
      order.push(`caller ${id}`);
      return id;
    });
    await flush();
    const { id } = native.enqueue.mock.calls.at(-1)![0];
    // Native settles the entry before the enqueue promise resolves.
    fire('settled', {
      eventId: 'e1',
      id,
      key: 'item.create',
      vars: { n: 1 },
      at: 10,
      attempts: 1,
      kind: 'error',
      error: { errorKind: 'file', message: 'gone' },
      state: 'error',
    });
    resolveEnqueue(id);
    await pending;
    // Delivery yields a macrotask after the enqueue promise; wait it out.
    await new Promise<void>((resolve) => setTimeout(resolve, 20));
    expect(order).toEqual([`caller ${id}`, `handler ${id}`]);
    expect(native.ackEvents).toHaveBeenCalledWith(['e1']);
  });
});

describe('with a fake native', () => {
  it('runs define, mutate, settle, onSuccess and ack on the fake, not the TurboModule', async () => {
    const fake = createFakeNative();
    const client = createUploadClient({ native: fake });
    const onSuccess = jest.fn();
    const create = client.define({
      key: 'item.create',
      request: ({ n }: { n: number }) => ({
        url: `https://x/${n}`,
        method: 'PUT',
        data: { n, note: null },
      }),
      response: (raw) => (raw as { id: string }).id,
      onSuccess,
    });
    client.configure({ headers: () => ({ Authorization: 'Bearer t' }) });
    const { id } = await create.mutate({ n: 3 }, { id: 'local-3' });

    expect(fake.entries).toEqual([
      {
        id: 'local-3',
        key: 'item.create',
        vars: { n: 3 },
        descriptor: {
          url: 'https://x/3',
          method: 'PUT',
          data: { n: 3, note: null },
          headers: { Authorization: 'Bearer t' },
          expiresAt: expect.any(Number),
        },
        raw: expect.objectContaining({ varsJson: '{"n":3}' }),
      },
    ]);
    expect(client.getRequests({ id })).toEqual([
      expect.objectContaining({ id, key: 'item.create', state: 'queued' }),
    ]);

    const event = await fake.settle(id, {
      kind: 'completed',
      response: { status: 201, body: '{"id":"srv-9"}' },
    });

    expect(onSuccess).toHaveBeenCalledWith(
      'srv-9',
      { n: 3 },
      expect.objectContaining({ id, key: 'item.create', deliveries: 1 }),
    );
    expect(event).toMatchObject({ url: 'https://x/3', method: 'PUT' });
    expect(fake.ackedEventIds).toEqual([event.eventId]);
    // An acked completed outcome forgets the row.
    expect(client.getRequests()).toEqual([]);
    expect(native.enqueue).not.toHaveBeenCalled();
    expect(native.ackEvents).not.toHaveBeenCalled();
  });

  it('replays an outcome settled before configure() when configure() runs', async () => {
    const fake = createFakeNative();
    const client = createUploadClient({ native: fake });
    const onError = jest.fn();
    const send = client.define({
      key: 'item.send',
      request: (_v: null) => ({ url: 'https://x' }),
      onError,
    });
    const { id } = await send.mutate();
    const acked = fake.settle(id, {
      kind: 'error',
      error: { errorKind: 'http', response: { status: 500 } },
    });
    await flush();
    expect(onError).not.toHaveBeenCalled();

    client.configure({});
    const event = await acked;

    expect(onError).toHaveBeenCalledWith(
      {
        errorKind: 'http',
        message: 'http',
        response: { status: 500, bodyTruncated: false },
      },
      null,
      expect.objectContaining({ id, deliveries: 1 }),
    );
    expect(fake.ackedEventIds).toEqual([event.eventId]);
    // An error row stays until cancel() or a same-id mutate().
    expect(client.getRequests({ id })).toEqual([
      expect.objectContaining({ state: 'error' }),
    ]);
  });
});

describe('a definition that arrives after configure()', () => {
  // A lazily loaded module can run its define() after configure(). These
  // outcomes come from an earlier session, so this one replays them.
  const NOT_LISTED = /is not in configure\(\{ definitions \}\)/;
  const request = (_v: null) => ({ url: 'https://x' });
  let warn: jest.SpyInstance;

  beforeEach(() => {
    warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    warn.mockRestore();
  });

  /**
   * Queues an entry of `key` through a client that never configures, as an
   * earlier session did. No listener exists, so settle() only journals.
   */
  const queuedEarlier = async (
    fake: ReturnType<typeof createFakeNative>,
    key: string,
  ): Promise<string> => {
    const earlier = createUploadClient({ native: fake });
    const { id } = await earlier.define({ key, request }).mutate();
    return id;
  };

  const notListedWarnings = () =>
    warn.mock.calls.filter(([message]) => NOT_LISTED.test(String(message)));

  it('delivers a held outcome to the late definition and acks it in the same session', async () => {
    const fake = createFakeNative();
    const id = await queuedEarlier(fake, 'late');
    const acked = fake.settle(id, {
      kind: 'completed',
      response: { body: '{}' },
    });
    const client = createUploadClient({ native: fake });
    const state = jest.fn();
    client.addListener('state', state);

    client.configure({});
    await flush();
    expect(state).toHaveBeenCalledWith(
      expect.objectContaining({ id, key: 'late', reason: 'unhandled-key' }),
    );
    expect(fake.ackedEventIds).toEqual([]);

    const onSuccess = jest.fn();
    client.define({ key: 'late', request, onSuccess });
    const event = await acked;

    expect(onSuccess).toHaveBeenCalledWith(
      { status: 200, body: '{}', bodyTruncated: false },
      null,
      expect.objectContaining({ id, key: 'late', deliveries: 1 }),
    );
    expect(fake.ackedEventIds).toEqual([event.eventId]);
  });

  it('delivers two held outcomes of one id in journal order', async () => {
    const fake = createFakeNative();
    const id = await queuedEarlier(fake, 'late');
    const first = fake.settle(id, {
      kind: 'error',
      error: { errorKind: 'http', response: { status: 500 } },
    });
    const second = fake.settle(id, { kind: 'completed' });
    const client = createUploadClient({ native: fake });
    client.configure({});
    await flush();

    const order: string[] = [];
    client.define({
      key: 'late',
      request,
      // The first handler is slow. The second must still wait for it.
      onError: async () => {
        await new Promise<void>((resolve) => setTimeout(resolve, 20));
        order.push('error');
      },
      onSuccess: () => {
        order.push('completed');
      },
    });
    const [a, b] = await Promise.all([first, second]);

    expect(order).toEqual(['error', 'completed']);
    expect(fake.ackedEventIds).toEqual([a.eventId, b.eventId]);
  });

  it('leaves an outcome whose key is never defined unacknowledged', async () => {
    const fake = createFakeNative({ ackTimeoutMs: 100 });
    const id = await queuedEarlier(fake, 'gone');
    const acked = fake.settle(id, { kind: 'completed' });
    const client = createUploadClient({ native: fake });
    const state = jest.fn();
    client.addListener('state', state);

    client.configure({});
    client.define({ key: 'other', request });

    await expect(acked).rejects.toThrow(/was not acknowledged/);
    expect(state).toHaveBeenCalledWith(
      expect.objectContaining({ id, key: 'gone', reason: 'unhandled-key' }),
    );
    expect(fake.ackedEventIds).toEqual([]);
    expect(await fake.getUnacknowledgedEvents()).toEqual([
      expect.objectContaining({ id, key: 'gone' }),
    ]);
  });

  it('does not deliver twice when define() replaces a key with held outcomes', async () => {
    const fake = createFakeNative();
    const id = await queuedEarlier(fake, 'late');
    const acked = fake.settle(id, { kind: 'completed' });
    const client = createUploadClient({ native: fake });
    client.configure({});
    await flush();

    // A hot reload runs the module's define() again.
    const replaced = jest.fn();
    const current = jest.fn();
    client.define({ key: 'late', request, onSuccess: replaced });
    client.define({ key: 'late', request, onSuccess: current });
    const event = await acked;
    client.define({ key: 'late', request, onSuccess: current });
    await flush();

    expect(replaced).not.toHaveBeenCalled();
    expect(current).toHaveBeenCalledTimes(1);
    expect(fake.ackedEventIds).toEqual([event.eventId]);
  });

  it('with configure({ definitions }) listing every handle, behaves as before', async () => {
    const fake = createFakeNative();
    const id = await queuedEarlier(fake, 'item.send');
    const acked = fake.settle(id, { kind: 'completed' });
    const client = createUploadClient({ native: fake });
    const onSuccess = jest.fn();
    const send = client.define({ key: 'item.send', request, onSuccess });
    const ping = client.define({ key: 'ping', request });

    client.configure({ definitions: [send, ping], lifetimeMs: 1000 });
    await acked;
    expect(onSuccess).toHaveBeenCalledTimes(1);
    // The list stays on the JS side.
    expect(fake.calls.configure).toEqual([{ lifetimeMs: 1000 }]);

    // A hot reload of a listed key replaces it without the list warning.
    client.define({ key: 'ping', request });
    expect(notListedWarnings()).toEqual([]);
    const { id: pingId } = await ping.mutate();
    await fake.settle(pingId, { kind: 'completed' });
  });

  it('rejects a definitions option that is not a list of handles', () => {
    const client = createUploadClient({ native: createFakeNative() });
    expect(() =>
      client.configure({ definitions: 'all' as unknown as [] }),
    ).toThrow(
      'configure: definitions must be an array of the handles that define() returned',
    );
    expect(() =>
      client.configure({ definitions: [null as unknown as never] }),
    ).toThrow(/definitions must be an array/);
  });

  it('in development, warns once about a key defined later that the list left out, and still delivers to it', async () => {
    const fake = createFakeNative();
    const id = await queuedEarlier(fake, 'late');
    const acked = fake.settle(id, { kind: 'completed' });
    const client = createUploadClient({ native: fake });
    const listed = client.define({ key: 'listed', request });
    client.configure({ definitions: [listed] });
    await flush();

    const onSuccess = jest.fn();
    client.define({ key: 'late', request, onSuccess });
    client.define({ key: 'late', request, onSuccess });
    await acked;

    expect(notListedWarnings()).toEqual([
      [
        'define: "late" is not in configure({ definitions }). It still receives its outcomes, but list it so that its module loads before configure().',
      ],
    ]);
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it('drops the held outcomes of a cancelled id, so a later define() runs no handler', async () => {
    const fake = createFakeNative({ ackTimeoutMs: 100 });
    const id = await queuedEarlier(fake, 'late');
    const acked = fake.settle(id, { kind: 'completed' });
    const client = createUploadClient({ native: fake });
    client.configure({});
    await flush();

    // On a settled entry, native forgets the row and its outcomes.
    await client.cancel(id);
    expect(await fake.getUnacknowledgedEvents()).toEqual([]);
    const onSuccess = jest.fn();
    client.define({ key: 'late', request, onSuccess });

    await expect(acked).rejects.toThrow(/was not acknowledged/);
    expect(onSuccess).not.toHaveBeenCalled();
    expect(fake.ackedEventIds).toEqual([]);
  });

  it('drops the held outcomes of an id at the start of cancel(), so a define() while native cancels runs no handler', async () => {
    const fake = createFakeNative({ ackTimeoutMs: 100 });
    const id = await queuedEarlier(fake, 'late');
    const acked = fake.settle(id, { kind: 'completed' });
    // Native's answer to cancel() waits until the test lets it go.
    let answer!: () => void;
    const answered = new Promise<void>((resolve) => {
      answer = resolve;
    });
    const nativeCancel = fake.cancel;
    jest.spyOn(fake, 'cancel').mockImplementation(async (cancelId) => {
      await answered;
      return nativeCancel(cancelId);
    });
    const client = createUploadClient({ native: fake });
    client.configure({});
    await flush();

    const cancelling = client.cancel(id);
    const onSuccess = jest.fn();
    client.define({ key: 'late', request, onSuccess });
    await flush();
    answer();
    await cancelling;

    await expect(acked).rejects.toThrow(/was not acknowledged/);
    expect(onSuccess).not.toHaveBeenCalled();
    expect(fake.ackedEventIds).toEqual([]);
  });

  it('sends no second state row when a late definition handles a held outcome', async () => {
    const fake = createFakeNative();
    const id = await queuedEarlier(fake, 'late');
    const acked = fake.settle(id, { kind: 'completed' });
    const client = createUploadClient({ native: fake });
    const state = jest.fn();
    client.addListener('state', state);
    client.configure({});
    await flush();

    const onSuccess = jest.fn();
    client.define({ key: 'late', request, onSuccess });
    await acked;
    await flush();

    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(state.mock.calls).toEqual([
      [expect.objectContaining({ id, key: 'late', reason: 'unhandled-key' })],
    ]);
  });

  it('a later configure() replaces the list the dev warning checks, and no list turns it off', () => {
    const client = createUploadClient({ native: createFakeNative() });
    const listed = client.define({ key: 'listed', request });
    client.configure({ definitions: [listed] });
    client.configure({
      definitions: [listed, { key: 'added', mutate: listed.mutate }],
    });
    client.define({ key: 'added', request });
    client.define({ key: 'unlisted', request });
    expect(notListedWarnings()).toEqual([
      [expect.stringContaining('define: "unlisted" is not in')],
    ]);

    client.configure({});
    client.define({ key: 'after.no.list', request });
    expect(notListedWarnings()).toHaveLength(1);
  });

  it('does not warn about an unlisted key without the list, or outside development', async () => {
    const withoutList = createUploadClient({ native: createFakeNative() });
    withoutList.configure({});
    withoutList.define({ key: 'late', request });

    const global = globalThis as { __DEV__?: boolean };
    global.__DEV__ = false;
    try {
      const production = createUploadClient({ native: createFakeNative() });
      production.configure({ definitions: [] });
      production.define({ key: 'late', request });
    } finally {
      delete global.__DEV__;
    }

    expect(notListedWarnings()).toEqual([]);
  });
});
