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
    cancelUpload: jest.fn(async () => true),
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
      acceptStatus: [409],
    });
    expect(native.startUpload).toHaveBeenCalledWith(
      expect.objectContaining({
        url: 'https://example.com/up',
        path: 'file:///tmp/f.bin',
        acceptStatus: [409],
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
