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
import { createRequestCache, createSelector } from '../requests';
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

  it('does not read native again at subscribe right after a render read', () => {
    const { native, client, getRequests } = setup();
    native.seedRows([row()]);
    // A render reads before its effect subscribes, in the same task.
    client.getCachedRequests();
    listen(client);
    expect(getRequests).toHaveBeenCalledTimes(1);
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

  it('drop the unhandled-key reason from the row', () => {
    const { native, client } = setup();
    native.seedRows([row()]);
    listen(client);
    native.emit.state({ ...row({ state: 'completed' }), reason: 'unhandled-key' });
    expect(client.getCachedRequests()[0]).toEqual(row({ state: 'completed' }));
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

  it('does nothing for an error outcome', () => {
    const { cache, readNative } = stub([row({ id: 'a', state: 'error' })]);
    cache.subscribe(() => {});
    readNative.mockClear();
    cache.acked({ id: 'a', kind: 'error' });
    expect(readNative).not.toHaveBeenCalled();
    expect(cache.rows()).toHaveLength(1);
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
    expect(second.calls).toHaveLength(1);
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

  it('reads native on every getCachedRequests() while nobody listens', async () => {
    const { native, client } = setup();
    native.seedRows([row({ id: 'a' })]);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a']);
    await flush();
    native.seedRows([row({ id: 'b' })]);
    expect(client.getCachedRequests().map((r) => r.id)).toEqual(['a', 'b']);
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
