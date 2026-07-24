// Define all mocks inside the factory (no outer references) to avoid the
// import-hoisting TDZ trap, then grab handles from the mocked module below.
// The library reaches native through TurboModuleRegistry.getEnforcing, so that
// is what has to be stubbed — the codegen event emitters are plain functions
// that take a handler and return a subscription.
jest.mock('react-native', () => {
  const subscription = { remove: jest.fn() };
  const nativeModule = {
    startUpload: jest.fn(async () => 'id-1'),
    cancelUpload: jest.fn(async () => true),
    getUploadStatus: jest.fn(async () => null),
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

  it('getUploadStatus maps a null result to undefined', async () => {
    await expect(Upload.ios.getUploadStatus('u1')).resolves.toBeUndefined();
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
});

describe('addListener', () => {
  it('subscribes to the matching codegen emitter', () => {
    Upload.addListener('progress', null, jest.fn());
    expect(native.onProgress).toHaveBeenCalled();
  });

  it('only invokes the listener for the matching upload id', () => {
    const cb = jest.fn();
    Upload.addListener('completed', 'u1', cb);
    const handler = native.onCompleted.mock.calls.at(-1)![0] as (
      data: unknown,
    ) => void;
    handler({ id: 'u1', responseCode: 200 });
    handler({ id: 'someone-else', responseCode: 200 });
    expect(cb).toHaveBeenCalledTimes(1);
  });
});
