// The row cache behind useRequests(), tested through the client with the
// fake native. React is not rendered here (the root has no renderer); the
// example app's Jest renders the hooks with react-test-renderer.
jest.mock('react-native', () => {
  const { createFakeNative: create } = jest.requireActual('../testing');
  const fake = create();
  return {
    TurboModuleRegistry: { getEnforcing: () => fake, get: () => fake },
  };
});

import { createUploadClient } from '../index';
import {
  createRequestCache,
  createSelector,
  jsonEqual,
  rowEqual,
  sharedSelector,
} from '../requests';
import { createFakeNative, type FakeNative } from '../testing';
import type { RequestRow } from '../types';

const row = (overrides: Partial<RequestRow> = {}): RequestRow => ({
  id: 'a',
  key: 'k',
  vars: { n: 1 },
  state: 'queued',
  bytesSent: 0,
  totalBytes: 100,
  attempts: 0,
  updatedAt: 1,
  ...overrides,
});

const flush = async (): Promise<void> => {
  for (let i = 0; i < 3; i++) {
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
};

const setup = (native: FakeNative = createFakeNative()) => {
  const warnings: string[] = [];
  const client = createUploadClient({ native });
  const ping = client.define({
    key: 'ping',
    request: (_vars: { n: number }) => ({ url: 'https://api.test/ping' }),
  });
  client.configure({ onWarning: (message) => warnings.push(message) });
  const getRequests = jest.spyOn(native, 'getRequests');
  return { native, client, ping, warnings, getRequests };
};

/** Subscribes the way a mounted hook does, and counts the notifications. */
const listen = (client: ReturnType<typeof setup>['client']) => {
  const calls: Array<readonly RequestRow[]> = [];
  const subscription = client.addRequestsListener((rows) => calls.push(rows));
  return { calls, subscription };
};

describe('first subscribe', () => {
  it('reads native once, however many listeners and reads follow', () => {
    const { native, client, getRequests } = setup();
    native.seedRows([row({ id: 'a' }), row({ id: 'b' })]);
    listen(client);
    listen(client);
    client.getCachedRequests();
    client.getCachedRequests({ key: 'k' });
    expect(getRequests).toHaveBeenCalledTimes(1);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a', 'b']);
  });

  it('checks native at subscribe, so a row emitted after a render read is there', () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'a' })]);
    // A render reads before its effect subscribes.
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a']);
    // Native queues a row and emits it while nothing listens: the event is lost.
    native.seedRows([row({ id: 'b' })]);
    native.emit.state(row({ id: 'b' }));
    listen(client);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a', 'b']);
  });
});

describe('state events', () => {
  it('patch one row and keep the identity of the others', async () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'a' }), row({ id: 'b' })]);
    const { calls } = listen(client);
    const before = client.getCachedRequests();
    native.emit.state(row({ id: 'a', state: 'running', updatedAt: 2 }));
    const after = client.getCachedRequests();
    expect(after).not.toBe(before);
    expect(after[0]).toEqual(row({ id: 'a', state: 'running', updatedAt: 2 }));
    expect(after[1]).toBe(before[1]);
    expect(calls).toHaveLength(1);
  });

  it('add a row that mutate() created', async () => {
    const { client, ping } = setup();
    listen(client);
    const { id } = await ping.mutate({ n: 1 });
    expect(client.getCachedRequests()).toEqual([
      expect.objectContaining({ id, key: 'ping', state: 'queued' }),
    ]);
  });

  it('do not notify for a row that did not change', () => {
    const { native, client } = setup();
    native.seedRows([row()]);
    const { calls } = listen(client);
    const before = client.getCachedRequests();
    native.emit.state(row());
    expect(client.getCachedRequests()).toBe(before);
    expect(calls).toHaveLength(0);
  });

  it('take an unhandled-key row from native, not from the outcome', () => {
    const { native, client } = setup();
    // A same-id mutate() reopened the entry after the outcome was journaled.
    native.seedRows([row({ state: 'queued', updatedAt: 5 })]);
    listen(client);
    native.emit.state({
      ...row({ state: 'completed', updatedAt: 3 }),
      reason: 'unhandled-key',
    });
    expect(client.getCachedRequests()).toEqual([
      row({ state: 'queued', updatedAt: 5 }),
    ]);
  });

  it('show the native row of an unhandled-key outcome, without the reason', () => {
    const { native, client } = setup();
    native.seedRows([row({ state: 'running' })]);
    listen(client);
    native.seedRows([row({ state: 'completed', updatedAt: 2 })]);
    native.emit.state({
      ...row({ state: 'completed', updatedAt: 2 }),
      reason: 'unhandled-key',
    });
    expect(client.getCachedRequests()).toEqual([
      row({ state: 'completed', updatedAt: 2 }),
    ]);
  });
});

describe('progress', () => {
  // The progress view is only reachable through the hook, so these tests
  // build the cache over a client's events directly.
  const progressSetup = () => {
    const native = createFakeNative();
    const client = createUploadClient({ native });
    native.seedRows([row({ id: 'a' }), row({ id: 'b' })]);
    const cache = createRequestCache({
      readNative: () => native.getRequests() as RequestRow[],
      addListener: client.addListener,
      warn: jest.fn(),
    });
    return { native, cache };
  };

  it('notifies only the progress view, and overlays the bytes', () => {
    const { native, cache } = progressSetup();
    const rowCalls = jest.fn();
    const progressCalls = jest.fn();
    cache.subscribe(rowCalls);
    cache.subscribeWithProgress(progressCalls);
    const rows = cache.rows();
    const before = cache.rowsWithProgress();
    native.emit.progress({ id: 'a', bytesSent: 40, totalBytes: 100 });
    const after = cache.rowsWithProgress();
    expect(progressCalls).toHaveBeenCalledTimes(1);
    expect(rowCalls).not.toHaveBeenCalled();
    expect(cache.rows()).toBe(rows);
    expect(after).not.toBe(before);
    expect(after[0]).toEqual(row({ id: 'a', bytesSent: 40 }));
    expect(after[1]).toBe(before[1]);
    // Reading again without a new event returns the same array.
    expect(cache.rowsWithProgress()).toBe(after);
  });

  it('notifies both views on a state event', () => {
    const { native, cache } = progressSetup();
    const rowCalls = jest.fn();
    const progressCalls = jest.fn();
    cache.subscribe(rowCalls);
    cache.subscribeWithProgress(progressCalls);
    native.emit.state(row({ id: 'a', state: 'running', updatedAt: 2 }));
    expect(rowCalls).toHaveBeenCalledTimes(1);
    expect(progressCalls).toHaveBeenCalledTimes(1);
  });

  it('gives way to the bytes of the next state event', () => {
    const { native, cache } = progressSetup();
    cache.subscribeWithProgress(() => {});
    native.emit.progress({ id: 'a', bytesSent: 40, totalBytes: 100 });
    native.emit.state(row({ state: 'running', bytesSent: 10, updatedAt: 2 }));
    expect(cache.rowsWithProgress()[0]!.bytesSent).toBe(10);
  });

  it('ignores progress for a row it does not hold', () => {
    const { native, cache } = progressSetup();
    const progressCalls = jest.fn();
    cache.subscribeWithProgress(progressCalls);
    native.emit.progress({ id: 'zzz', bytesSent: 1, totalBytes: 2 });
    expect(progressCalls).not.toHaveBeenCalled();
  });
});

describe('rows native forgets with no event', () => {
  it('drops a completed row when its ack resolves', async () => {
    const { native, client, ping } = setup();
    const { calls } = listen(client);
    const { id } = await ping.mutate({ n: 1 });
    // settle() resolves after the client handled the ack, so the cache is
    // already up to date here.
    await native.settle(id, { kind: 'completed' });
    expect(native.getRequests()).toEqual([]);
    expect(client.getCachedRequests({ includeCancelled: true })).toEqual([]);
    expect(calls[calls.length - 1]).toEqual([]);
  });

  it('keeps a completed row whose ack failed', async () => {
    const native = createFakeNative({ ackTimeoutMs: 50 });
    const { client, ping, warnings } = setup(native);
    listen(client);
    const { id } = await ping.mutate({ n: 1 });
    native.failNext('ackEvents', 'E_STORAGE');
    await expect(native.settle(id, { kind: 'completed' })).rejects.toThrow(
      /not acknowledged/,
    );
    expect(client.getCachedRequests()).toEqual([
      expect.objectContaining({ id, state: 'completed' }),
    ]);
    expect(warnings).toEqual([expect.stringMatching(/ackEvents failed/)]);
  });

  it('keeps a completed row whose key has no definition', async () => {
    const native = createFakeNative({ ackTimeoutMs: 50 });
    const { client } = setup(native);
    native.seedRows([row({ id: 'x', key: 'gone', state: 'running' })]);
    listen(client);
    await expect(native.settle('x', { kind: 'completed' })).rejects.toThrow(
      /not acknowledged/,
    );
    expect(client.getCachedRequests()).toEqual([
      expect.objectContaining({ id: 'x', state: 'completed' }),
    ]);
  });

  it('drops a cancelled live row when its ack resolves', async () => {
    const { native, client, ping } = setup();
    listen(client);
    const { id } = await ping.mutate({ n: 1 });
    await client.cancel(id);
    await flush();
    expect(native.getRequests()).toEqual([]);
    expect(client.getCachedRequests({ includeCancelled: true })).toEqual([]);
  });

  it('drops a settled row when cancel() resolves', async () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'e', state: 'error' })]);
    const { calls } = listen(client);
    const states = jest.fn();
    client.addListener('state', states);
    await client.cancel('e');
    expect(states).not.toHaveBeenCalled();
    expect(client.getCachedRequests()).toEqual([]);
    expect(calls).toEqual([[]]);
  });

  it('keeps the row when cancel() rejects', async () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'e', state: 'error' })]);
    listen(client);
    native.failNext('cancel', 'E_STORAGE');
    await expect(client.cancel('e')).rejects.toThrow('E_STORAGE');
    expect(client.getCachedRequests()).toHaveLength(1);
  });

  it('keeps an error row after its ack, until cancel()', async () => {
    const { native, client, ping } = setup();
    listen(client);
    const { id } = await ping.mutate({ n: 1 });
    await native.settle(id, { kind: 'error', error: { errorKind: 'http' } });
    expect(client.getCachedRequests()).toEqual([
      expect.objectContaining({ id, state: 'error' }),
    ]);
    await client.cancel(id);
    expect(client.getCachedRequests()).toEqual([]);
  });

  it('replaces an error row on a same-id mutate()', async () => {
    const { native, client, ping } = setup();
    listen(client);
    const { id } = await ping.mutate({ n: 1 });
    await native.settle(id, { kind: 'error', error: { errorKind: 'http' } });
    await ping.mutate({ n: 2 }, { id });
    expect(client.getCachedRequests()).toEqual([
      expect.objectContaining({ id, state: 'queued', vars: { n: 2 } }),
    ]);
  });
});

describe('the ack of an outcome whose row moved on', () => {
  const stub = (nativeRows: RequestRow[]) => {
    const listeners: Record<string, (e: never) => void> = {};
    const readNative = jest.fn(() => nativeRows.map((r) => ({ ...r })));
    const cache = createRequestCache({
      readNative,
      addListener: ((event: string, listener: (e: never) => void) => {
        listeners[event] = listener;
        return { remove: () => {} };
      }) as never,
      warn: jest.fn(),
    });
    return { cache, readNative, listeners };
  };

  it('asks native when the row is not in the outcome state, and keeps it', async () => {
    // A cancelled entry reopened by a same-id mutate(): the old outcome's
    // ack does not forget it.
    const reopened = row({ id: 'a', state: 'queued', updatedAt: 5 });
    const { cache, readNative } = stub([reopened]);
    cache.subscribe(() => {});
    await Promise.resolve();
    readNative.mockClear();
    cache.acked({ id: 'a', kind: 'cancelled' });
    expect(readNative).toHaveBeenCalledTimes(1);
    expect(cache.rows()).toEqual([reopened]);
  });

  it('drops the row when native no longer has it', async () => {
    const nativeRows = [row({ id: 'a', state: 'running' })];
    const { cache } = stub(nativeRows);
    cache.subscribe(() => {});
    nativeRows.length = 0;
    cache.acked({ id: 'a', kind: 'completed' });
    expect(cache.rows()).toEqual([]);
  });

  it('does nothing for an error outcome on an error row', () => {
    const { cache, readNative } = stub([row({ id: 'a', state: 'error' })]);
    cache.subscribe(() => {});
    readNative.mockClear();
    cache.acked({ id: 'a', kind: 'error' });
    expect(readNative).not.toHaveBeenCalled();
    expect(cache.rows()).toHaveLength(1);
  });

  it('asks native for an error outcome on a row still shown live', () => {
    // iOS can settle a live row during the ack with no event.
    const nativeRows = [row({ id: 'a', state: 'running' })];
    const { cache, readNative } = stub(nativeRows);
    cache.subscribe(() => {});
    nativeRows[0] = row({ id: 'a', state: 'error', updatedAt: 2 });
    readNative.mockClear();
    cache.acked({ id: 'a', kind: 'error' });
    expect(readNative).toHaveBeenCalledTimes(1);
    expect(cache.rows()).toEqual([row({ id: 'a', state: 'error', updatedAt: 2 })]);
  });
});

describe('unsubscribe and subscribe again', () => {
  it('keeps the rows, then checks them against native', async () => {
    const { native, client, getRequests } = setup();
    native.seedRows([row({ id: 'a' })]);
    const first = listen(client);
    const kept = client.getCachedRequests();
    first.subscription.remove();
    await flush();
    // A change with no event while nobody listens.
    native.seedRows([row({ id: 'b' })]);
    getRequests.mockClear();
    const second = listen(client);
    expect(getRequests).toHaveBeenCalledTimes(1);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a', 'b']);
    // Row 'a' did not change, so it keeps its identity.
    expect(client.getCachedRequests()[0]).toBe(kept[0]);
    // No call while the listener registers; it reads the rows itself.
    expect(second.calls).toHaveLength(0);
  });

  it('keeps the same array when native has not changed', async () => {
    const { native, client } = setup();
    native.seedRows([row()]);
    const first = listen(client);
    const kept = client.getCachedRequests();
    first.subscription.remove();
    await flush();
    const second = listen(client);
    expect(client.getCachedRequests()).toBe(kept);
    expect(second.calls).toHaveLength(0);
  });

  it('stops listening to native when the last listener leaves', () => {
    const { native, client } = setup();
    const before = native.listenerCount('state');
    const one = listen(client);
    const two = listen(client);
    expect(native.listenerCount('state')).toBe(before + 1);
    one.subscription.remove();
    expect(native.listenerCount('state')).toBe(before + 1);
    two.subscription.remove();
    expect(native.listenerCount('state')).toBe(before);
  });

  it('reads native on every getCachedRequests() while nobody listens', () => {
    const { native, client, getRequests } = setup();
    native.seedRows([row({ id: 'a' })]);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a']);
    native.seedRows([row({ id: 'b' })]);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a', 'b']);
    expect(getRequests).toHaveBeenCalledTimes(2);
  });

  it('warns and keeps the rows when the native read throws', async () => {
    const { native, client, warnings } = setup();
    native.seedRows([row()]);
    const first = listen(client);
    first.subscription.remove();
    await flush();
    native.failNext('getRequests', 'E_BOOM');
    listen(client);
    expect(client.getCachedRequests()).toHaveLength(1);
    expect(warnings).toEqual([expect.stringMatching(/getRequests/)]);
  });
});

describe('two clients', () => {
  it('keep separate caches', async () => {
    const one = setup();
    const two = setup();
    one.native.seedRows([row({ id: 'a' })]);
    two.native.seedRows([row({ id: 'b' })]);
    listen(one.client);
    listen(two.client);
    one.native.emit.state(row({ id: 'c' }));
    expect(one.client.getCachedRequests().map((r) => r.id)).toEqual([
      'a',
      'c',
    ]);
    expect(two.client.getCachedRequests().map((r) => r.id)).toEqual(['b']);
  });
});

describe('filters', () => {
  it('leave out cancelled rows unless includeCancelled is set', () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'a' }), row({ id: 'c', state: 'cancelled' })]);
    listen(client);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a']);
    expect(
      client.getCachedRequests({ includeCancelled: true }).map((r) => r.id),
    ).toEqual(['a', 'c']);
  });

  it('match key and id', () => {
    const { native, client } = setup();
    native.seedRows([
      row({ id: 'a', key: 'k1' }),
      row({ id: 'b', key: 'k2' }),
      row({ id: 'c', key: 'k2' }),
    ]);
    listen(client);
    expect(client.getCachedRequests({ key: 'k2' }).map((r) => r.id)).toEqual([
      'b',
      'c',
    ]);
    expect(
      client.getCachedRequests({ key: 'k2', id: 'c' }).map((r) => r.id),
    ).toEqual(['c']);
  });

  it('return the same array while nothing changed', () => {
    const { native, client } = setup();
    native.seedRows([row()]);
    listen(client);
    expect(client.getCachedRequests()).toBe(client.getCachedRequests());
    expect(client.getCachedRequests({ includeCancelled: true })).toBe(
      client.getCachedRequests({ includeCancelled: true }),
    );
  });
});

describe('createSelector', () => {
  it('returns the same array for the same rows', () => {
    const rows = [row({ id: 'a' }), row({ id: 'b' })];
    const select = createSelector(() => rows, { id: 'a' });
    expect(select()).toBe(select());
  });

  it('keeps its array when only rows it leaves out change', () => {
    let rows = [row({ id: 'a' }), row({ id: 'b' })];
    const select = createSelector(() => rows, { id: 'a' });
    const first = select();
    rows = [rows[0]!, row({ id: 'b', state: 'running' })];
    expect(select()).toBe(first);
    rows = [row({ id: 'a', state: 'running' }), rows[1]!];
    expect(select()).not.toBe(first);
    expect(select()).toEqual([row({ id: 'a', state: 'running' })]);
  });

  it('keeps its array when a hidden cancelled row changes', () => {
    let rows = [row({ id: 'a' }), row({ id: 'c', state: 'cancelled' })];
    const select = createSelector(() => rows, {});
    const first = select();
    expect(first.map((r) => r.id)).toEqual(['a']);
    rows = [rows[0]!];
    expect(select()).toBe(first);
  });
});

describe('addRequestsListener', () => {
  it('is not called while it registers, even when the check finds a change', async () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'a' })]);
    const first = listen(client);
    first.subscription.remove();
    native.seedRows([row({ id: 'b' })]);
    const listener = jest.fn();
    client.addRequestsListener(listener);
    expect(listener).not.toHaveBeenCalled();
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a', 'b']);
  });

  it('is not called when only a hidden cancelled row changes', () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'a' }), row({ id: 'c', state: 'cancelled' })]);
    const { calls } = listen(client);
    native.emit.state(row({ id: 'c', state: 'cancelled', updatedAt: 9 }));
    expect(calls).toHaveLength(0);
    native.emit.state(row({ id: 'a', state: 'running' }));
    expect(calls).toHaveLength(1);
  });

  it('may call the client while the cache drops a row after an ack', async () => {
    const { native, client, ping, warnings } = setup();
    const { id: first } = await ping.mutate({ n: 1 });
    const { id: second } = await ping.mutate({ n: 2 });
    await native.settle(second, { kind: 'error', error: { errorKind: 'http' } });
    const seen: string[][] = [];
    const cancels: Array<Promise<void>> = [];
    client.addRequestsListener((rows) => {
      seen.push(rows.map((r) => r.id));
      // Reads native and starts a cancel from inside the notification.
      client.getRequests();
      if (cancels.length === 0 && !rows.some((r) => r.id === first)) {
        cancels.push(client.cancel(second));
      }
    });
    await native.settle(first, { kind: 'completed' });
    await Promise.all(cancels);
    expect(cancels).toHaveLength(1);
    expect(client.getCachedRequests()).toEqual([]);
    expect(native.getRequests()).toEqual([]);
    // The completed state event, the drop after the ack, then the cancel.
    expect(seen.slice(-2)).toEqual([[second], []]);
    expect(warnings).toEqual([]);
  });
});

describe('selector work', () => {
  /** An array that counts reads of its elements. */
  const counted = (rows: RequestRow[]) => {
    const reads = { count: 0 };
    const proxy = new Proxy(rows, {
      get: (target, prop, receiver) => {
        if (typeof prop === 'string' && /^\d+$/.test(prop)) {
          reads.count++;
        }
        return Reflect.get(target, prop, receiver);
      },
    });
    return { proxy, reads };
  };

  it('looks an id up in one index per rows array, without walking the rows each time', () => {
    const many = Array.from({ length: 100 }, (_, i) => row({ id: `r${i}` }));
    const { proxy, reads } = counted(many);
    const selectors = Array.from({ length: 50 }, (_, i) =>
      createSelector(() => proxy, { id: `r${i}` }),
    );
    const results = selectors.map((select) => select());
    // One index build reads each row once; 50 selectors share it.
    expect(reads.count).toBe(100);
    expect(results[7]).toEqual([many[7]]);
    expect(selectors[7]!()).toBe(results[7]);
  });

  it('keeps an id result when a new array holds the same row object', () => {
    const rows = [row({ id: 'a' }), row({ id: 'b' })];
    let current = rows;
    const select = createSelector(() => current, { id: 'a' });
    const first = select();
    current = [rows[0]!, row({ id: 'b', state: 'running' })];
    expect(select()).toBe(first);
    current = [row({ id: 'a', state: 'cancelled' }), current[1]!];
    expect(select()).toEqual([]);
  });

  it('shares one selector per source and filter without an id', () => {
    const rows = [row({ id: 'a', key: 'k1' }), row({ id: 'b', key: 'k2' })];
    const source = () => rows;
    expect(sharedSelector(source, {})).toBe(sharedSelector(source, {}));
    expect(sharedSelector(source, { key: 'k1' })).toBe(
      sharedSelector(source, { key: 'k1' }),
    );
    expect(sharedSelector(source, { key: 'k1' })).not.toBe(
      sharedSelector(source, {}),
    );
    expect(sharedSelector(source, { includeCancelled: true })).not.toBe(
      sharedSelector(source, {}),
    );
    expect(sharedSelector(source, { key: 'k1' })().map((r) => r.id)).toEqual([
      'a',
    ]);
  });
});

describe('jsonEqual', () => {
  it('compares JSON values deeply, with undefined fields as absent', () => {
    expect(jsonEqual({ a: [1, { b: null }] }, { a: [1, { b: null }] })).toBe(
      true,
    );
    expect(jsonEqual({ a: 1, b: undefined }, { a: 1 })).toBe(true);
    expect(jsonEqual({ a: 1 }, { a: 1, b: 2 })).toBe(false);
    expect(jsonEqual({ a: 1, c: 2 }, { a: 1, b: 2 })).toBe(false);
    expect(jsonEqual([1, 2], [1, 2, 3])).toBe(false);
    expect(jsonEqual([], {})).toBe(false);
    expect(jsonEqual(null, {})).toBe(false);
    expect(jsonEqual('1', 1)).toBe(false);
  });

  it('finds a changed vars field when every scalar field is the same', () => {
    expect(
      rowEqual(row({ vars: { n: 1 } }), row({ vars: { n: 2 } })),
    ).toBe(false);
    expect(rowEqual(row({ vars: { n: 1 } }), row({ vars: { n: 1 } }))).toBe(
      true,
    );
  });
});

describe('guards', () => {
  it('warns instead of throwing out of a native state callback', () => {
    const native = createFakeNative();
    const client = createUploadClient({ native });
    native.seedRows([row()]);
    const warn = jest.fn();
    const cache = createRequestCache({
      readNative: () => native.getRequests() as RequestRow[],
      addListener: client.addListener,
      warn,
    });
    // The cache's listener is registered first, so a throw in it would stop
    // the app's listener below.
    cache.subscribe(() => {
      throw new Error('boom');
    });
    cache.subscribeWithProgress(() => {
      throw new Error('boom');
    });
    const appListener = jest.fn();
    client.addListener('state', appListener);
    client.addListener('progress', appListener);
    expect(() =>
      native.emit.state(row({ state: 'running', updatedAt: 2 })),
    ).not.toThrow();
    expect(() =>
      native.emit.progress({ id: 'a', bytesSent: 1, totalBytes: 100 }),
    ).not.toThrow();
    expect(appListener).toHaveBeenCalledTimes(2);
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/from a state event threw/),
      expect.any(Error),
    );
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/from a progress event threw/),
      expect.any(Error),
    );
  });
});

describe('with nothing listening', () => {
  it('drops the rows at an ack instead of reading native', async () => {
    const { native, client, ping, getRequests } = setup();
    const { id } = await ping.mutate({ n: 1 });
    // A read with no listener fills the cache, then nobody listens.
    client.getCachedRequests();
    native.seedRows([row({ id, key: 'ping', state: 'running' })]);
    getRequests.mockClear();
    // The cached row is queued, the outcome completed: with a listener this
    // would read native.
    await native.settle(id, { kind: 'completed' });
    expect(getRequests).not.toHaveBeenCalled();
    // The next read takes native's rows.
    expect(client.getCachedRequests()).toEqual([]);
  });

  it('drops the rows at cancel() too, and the next subscribe reads native', async () => {
    const { native, client, getRequests } = setup();
    native.seedRows([row({ id: 'e', state: 'error' })]);
    client.getCachedRequests();
    getRequests.mockClear();
    await client.cancel('e');
    expect(getRequests).not.toHaveBeenCalled();
    listen(client);
    expect(getRequests).toHaveBeenCalledTimes(1);
    expect(client.getCachedRequests()).toEqual([]);
  });
});
