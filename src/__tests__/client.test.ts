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
      definitions: [],
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
    createUploadClient().configure({ definitions: [] });
    expect(native.configure).toHaveBeenCalledWith({
      lifetimeMs: 14 * 24 * 60 * 60 * 1000,
    });
    expect(native.configure.mock.calls[0][0]).not.toHaveProperty('retry');
  });

  it('rejects a non-positive lifetime', () => {
    expect(() =>
      createUploadClient().configure({ definitions: [], lifetimeMs: 0 }),
    ).toThrow(/lifetimeMs/);
    expect(() =>
      createUploadClient().configure({ definitions: [], lifetimeMs: NaN }),
    ).toThrow(/lifetimeMs/);
  });

  it('rejects a non-positive maxVarsBytes and keeps it on the JS side', () => {
    expect(() =>
      createUploadClient().configure({ definitions: [], maxVarsBytes: 0 }),
    ).toThrow(/maxVarsBytes must be a positive number, got 0/);
    expect(() =>
      createUploadClient().configure({
        definitions: [],
        maxVarsBytes: Infinity,
      }),
    ).toThrow(/maxVarsBytes/);
    createUploadClient().configure({ definitions: [], maxVarsBytes: 64 });
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
    client.configure({ definitions: [send], maxVarsBytes: 16 });
    await expect(send.mutate({ s: 'a'.repeat(8) })).resolves.toBeDefined();
    await expect(send.mutate({ s: 'a'.repeat(9) })).rejects.toThrow(
      /is 17 bytes; the limit is 16/,
    );
  });

  it('starts replay once; a second call updates settings without replaying', async () => {
    const client = createUploadClient();
    client.configure({ definitions: [] });
    client.configure({ definitions: [], lifetimeMs: 5 });
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
    client.configure({
      definitions: [send],
      lifetimeMs: 1000,
      headers: () => ({ A: '1' }),
    });
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

    client.configure({ definitions: [] });
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
    client.configure({ definitions: [] });
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
    const known = client.define({
      key: 'known',
      request: (_v: null) => ({ url: 'https://x', data: 1 }),
      onSuccess,
    });
    client.addListener('state', () => {
      throw new Error('listener down');
    });
    client.addListener('state', second);
    client.configure({ definitions: [known] });
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
    client.configure({
      definitions: [create],
      headers: () => ({ Authorization: 'Bearer t' }),
    });
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
    client.configure({ definitions: [create] });
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
    client.configure({
      definitions: [create],
      headers: () => ({ Authorization: 'Bearer t' }),
    });
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

  it('replays an outcome journaled before configure() when configure() runs', async () => {
    const fake = createFakeNative();
    const client = createUploadClient({ native: fake });
    const onError = jest.fn();
    const send = client.define({
      key: 'item.send',
      request: (_v: null) => ({ url: 'https://x' }),
      onError,
    });
    // An earlier session queued the entry, and it failed while JS was gone.
    const id = 'earlier';
    fake.seedRows([{ ...rows[0]!, id, key: 'item.send', state: 'error' }]);
    const event = fake.buildSettled(id, {
      kind: 'error',
      error: { errorKind: 'http', response: { status: 500 } },
    });
    fake.seedUnacknowledged([event]);
    await flush();
    expect(onError).not.toHaveBeenCalled();

    client.configure({ definitions: [send] });
    await flush();

    expect(onError).toHaveBeenCalledWith(
      {
        errorKind: 'http',
        message: 'http',
        response: { status: 500, bodyTruncated: false },
      },
      null,
      expect.objectContaining({ id }),
    );
    expect(fake.ackedEventIds).toEqual([event.eventId]);
    // An error row stays until cancel() or a same-id mutate().
    expect(client.getRequests({ id })).toEqual([
      expect.objectContaining({ state: 'error' }),
    ]);
  });

  it('holds a mutate() made before configure() and queues it with the configured headers', async () => {
    const fake = createFakeNative();
    const client = createUploadClient({ native: fake });
    const send = client.define({
      key: 'item.send',
      request: (_v: null) => ({ url: 'https://x' }),
    });
    const pending = send.mutate(null, { id: 'early' });
    await flush();
    expect(fake.entries).toEqual([]);

    client.configure({
      definitions: [send],
      headers: () => ({ Authorization: 'Bearer t' }),
    });

    await expect(pending).resolves.toEqual({ id: 'early' });
    expect(fake.entries).toEqual([
      expect.objectContaining({
        id: 'early',
        descriptor: expect.objectContaining({
          headers: { Authorization: 'Bearer t' },
        }),
      }),
    ]);
  });

  it('runs a replayed outcome for a held mutate() id after the caller has the id', async () => {
    const fake = createFakeNative();
    const client = createUploadClient({ native: fake });
    const order: string[] = [];
    const send = client.define({
      key: 'item.send',
      request: (_v: null) => ({ url: 'https://x' }),
      onError: (_error, _vars, meta) => {
        order.push(`handler ${meta.id}`);
      },
    });
    fake.seedRows([{ ...rows[0]!, id: 'early', key: 'item.send' }]);
    fake.seedUnacknowledged([
      fake.buildSettled('early', {
        kind: 'error',
        error: { errorKind: 'http', response: { status: 500 } },
      }),
    ]);
    const pending = send.mutate(null, { id: 'early' }).then(({ id }) => {
      order.push(`caller ${id}`);
    });

    client.configure({ definitions: [send] });
    await pending;
    // Delivery yields a macrotask after the mutate() promise; wait it out.
    await new Promise<void>((resolve) => setTimeout(resolve, 20));

    expect(order).toEqual(['caller early', 'handler early']);
  });

  it('keeps the settings when a later configure() lists other keys', async () => {
    const fake = createFakeNative();
    const client = createUploadClient({ native: fake });
    const send = client.define({
      key: 'k',
      request: (_v: { s: string }) => ({ url: 'https://x', data: 1 }),
    });
    client.configure({ definitions: [send], maxVarsBytes: 64 });

    expect(() =>
      client.configure({ definitions: [], maxVarsBytes: 16 }),
    ).toThrow(
      'configure: the first call fixes the definitions. A later call must list the same keys.',
    );

    await expect(send.mutate({ s: 'a'.repeat(30) })).resolves.toBeDefined();
    expect(fake.calls.configure).toHaveLength(1);
  });

  it('refuses a definitions list before it touches native', () => {
    const fake = createFakeNative();
    const client = createUploadClient({ native: fake });
    const other = createUploadClient({ native: fake }).define({
      key: 'item.send',
      request: (_v: null) => ({ url: 'https://x' }),
    });

    expect(() => client.configure({ definitions: [other] })).toThrow(
      "configure: every definition must be one that this client's define() returned",
    );
    expect(fake.calls.configure).toEqual([]);
    expect(fake.listenerCount('settled')).toBe(0);
  });
});
