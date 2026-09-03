// Define all mocks inside the factory (no outer references) to avoid the
// import-hoisting TDZ trap, then grab handles from the mocked module below.
// The library reaches native through TurboModuleRegistry.getEnforcing, so that
// is what has to be stubbed — the codegen event emitters are plain functions
// that take a handler and return a subscription.
jest.mock('react-native', () => {
  const subscription = { remove: jest.fn() };
  const nativeModule = {
    configure: jest.fn(),
    startUpload: jest.fn(async () => 'id-1'),
    startChunkedUpload: jest.fn(async () => 'id-2'),
    cancelUpload: jest.fn(async () => true),
    removeUpload: jest.fn(async () => undefined),
    getUnacknowledgedEvents: jest.fn(async () => [
      {
        eventId: 'e1',
        id: 'u1',
        type: 'completed',
        timestamp: 1,
        responseCode: 200,
      },
    ]),
    ackEvents: jest.fn(async () => true),
    getAllUploads: jest.fn(async () => [{ id: 'u1', state: 'running' }]),
    onProgress: jest.fn(() => subscription),
    onError: jest.fn(() => subscription),
    onCancelled: jest.fn(() => subscription),
    onCompleted: jest.fn(() => subscription),
    onNotification: jest.fn(() => subscription),
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
import Upload from '../index';

/* eslint-disable @typescript-eslint/no-explicit-any */
// Same object the module captured at import time.
const native = (TurboModuleRegistry as any).getEnforcing('RNFileUploader');

describe('journal + query API', () => {
  it('getUnacknowledgedEvents returns the native events', async () => {
    const events = await Upload.getUnacknowledgedEvents();
    expect(events[0].eventId).toBe('e1');
    expect(events[0].type).toBe('completed');
  });

  it('ackEvents forwards the ids to native', async () => {
    await Upload.ackEvents(['e1', 'e2']);
    expect(native.ackEvents).toHaveBeenCalledWith(['e1', 'e2']);
  });

  it('getAllUploads returns the native snapshots', async () => {
    const uploads = await Upload.getAllUploads();
    expect(uploads[0]).toEqual({ id: 'u1', state: 'running' });
  });
});

describe('configure', () => {
  it('forwards the android notification config to native, flattened', () => {
    Upload.configure({
      android: { notificationTitle: 'Backing up…', notificationChannel: 'ch' },
    });
    expect(native.configure).toHaveBeenCalledWith({
      notificationTitle: 'Backing up…',
      notificationChannel: 'ch',
    });
  });
});

describe('startUpload', () => {
  it('prefixes the file path on iOS and forwards options', async () => {
    await Upload.startUpload({
      url: 'https://example.com/up',
      path: '/tmp/f.bin',
      method: 'POST',
      type: 'raw',
      accept: [{ status: 409, bodyIncludes: 'already completed' }],
    });
    expect(native.startUpload).toHaveBeenCalledWith(
      expect.objectContaining({
        url: 'https://example.com/up',
        path: 'file:///tmp/f.bin',
        accept: [{ status: 409, bodyIncludes: 'already completed' }],
      }),
    );
  });

  it('forwards android.noNotification but no notification text', async () => {
    await Upload.startUpload({
      url: 'https://example.com/up',
      path: '/tmp/f.bin',
      method: 'POST',
      type: 'raw',
      android: { noNotification: true },
    });
    const options = native.startUpload.mock.calls.at(-1)![0];
    expect(options.noNotification).toBe(true);
    // configure() owns the notification text. startUpload never carries it.
    expect(options).not.toHaveProperty('notificationTitle');
    expect(options).not.toHaveProperty('notificationId');
  });
});

describe('startUpload (chunked)', () => {
  const chunked = {
    type: 'chunked' as const,
    id: 'u1',
    path: '/tmp/f.bin',
    parts: [
      {
        url: 'https://example.com/up?partNum=1',
        headers: { 'Content-Range': 'bytes 0-9/20' },
        range: { start: 0, end: 10 },
      },
      {
        url: 'https://example.com/up?partNum=2',
        headers: { 'Content-Range': 'bytes 10-19/20' },
        range: { start: 10, end: 20 },
      },
    ],
    expiresAt: 1735689600000,
  };

  it('routes to startChunkedUpload, not startUpload', async () => {
    native.startUpload.mockClear();
    await Upload.startUpload(chunked);
    expect(native.startUpload).not.toHaveBeenCalled();
    expect(native.startChunkedUpload).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'u1',
        path: 'file:///tmp/f.bin',
        parts: chunked.parts,
        expiresAt: chunked.expiresAt,
      }),
    );
  });

  it('rejects an empty id', () => {
    expect(() => Upload.startUpload({ ...chunked, id: '' })).toThrow(
      /non-empty id/,
    );
  });

  it('rejects empty parts', () => {
    expect(() => Upload.startUpload({ ...chunked, parts: [] })).toThrow(
      /non-empty/,
    );
  });

  it.each([
    { start: -1, end: 10 },
    { start: 10, end: 10 },
    { start: 11, end: 10 },
    { start: 0.5, end: 10 },
    { start: 0, end: NaN },
  ])('rejects range %p', (range) => {
    const parts = [{ ...chunked.parts[0], range }];
    expect(() => Upload.startUpload({ ...chunked, parts })).toThrow(
      /0 <= start < end/,
    );
  });

  it.each([NaN, Infinity, 0, -5])('rejects expiresAt %p', (expiresAt) => {
    expect(() => Upload.startUpload({ ...chunked, expiresAt })).toThrow(
      /expiresAt/,
    );
  });

  it('rejects a nonzero first start', () => {
    const parts = [
      { ...chunked.parts[0], range: { start: 5, end: 10 } },
      { ...chunked.parts[1], range: { start: 10, end: 20 } },
    ];
    expect(() => Upload.startUpload({ ...chunked, parts })).toThrow(
      /parts\[0\]\.range\.start must be 0/,
    );
  });

  it('rejects a gap between parts', () => {
    const parts = [
      { ...chunked.parts[0], range: { start: 0, end: 8 } },
      { ...chunked.parts[1], range: { start: 10, end: 20 } },
    ];
    expect(() => Upload.startUpload({ ...chunked, parts })).toThrow(
      /no gaps or overlaps/,
    );
  });

  it('rejects overlapping parts', () => {
    const parts = [
      { ...chunked.parts[0], range: { start: 0, end: 12 } },
      { ...chunked.parts[1], range: { start: 10, end: 20 } },
    ];
    expect(() => Upload.startUpload({ ...chunked, parts })).toThrow(
      /no gaps or overlaps/,
    );
  });

  it('rejects out-of-order parts', () => {
    const parts = [chunked.parts[1], chunked.parts[0]];
    expect(() => Upload.startUpload({ ...chunked, parts })).toThrow(
      /parts\[0\]\.range\.start must be 0/,
    );
  });

  it('rejects an empty part url', () => {
    const parts = [{ ...chunked.parts[0], url: '' }, chunked.parts[1]];
    expect(() => Upload.startUpload({ ...chunked, parts })).toThrow(
      /parts\[0\]\.url must be a non-empty string/,
    );
  });

  it('rejects non-object part headers', () => {
    const parts = [
      { ...chunked.parts[0], headers: 'nope' as never },
      chunked.parts[1],
    ];
    expect(() => Upload.startUpload({ ...chunked, parts })).toThrow(
      /parts\[0\]\.headers must be a plain object/,
    );
  });

  it('throws before reaching native', () => {
    native.startChunkedUpload.mockClear();
    expect(() => Upload.startUpload({ ...chunked, parts: [] })).toThrow();
    expect(native.startChunkedUpload).not.toHaveBeenCalled();
  });
});

describe('removeUpload', () => {
  it('forwards the id to native', async () => {
    await Upload.removeUpload('u1');
    expect(native.removeUpload).toHaveBeenCalledWith('u1');
  });
});

describe('addListener', () => {
  it('subscribes to the matching codegen emitter', () => {
    Upload.addListener('progress', jest.fn());
    expect(native.onProgress).toHaveBeenCalled();
  });

  it('delivers events for every upload', () => {
    const cb = jest.fn();
    Upload.addListener('completed', cb);
    const handler = native.onCompleted.mock.calls.at(-1)![0] as (
      data: unknown,
    ) => void;
    handler({ id: 'u1', responseCode: 200 });
    handler({ id: 'someone-else', responseCode: 200 });
    expect(cb).toHaveBeenCalledTimes(2);
  });
});
