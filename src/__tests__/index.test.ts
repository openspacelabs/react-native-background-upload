// Define all mocks inside the factory (no outer references) to avoid the
// import-hoisting TDZ trap, then grab handles from the mocked module below.
jest.mock('react-native', () => ({
  Platform: { OS: 'ios' },
  DeviceEventEmitter: {
    addListener: jest.fn(() => ({ remove: jest.fn() })),
  },
  NativeModules: {
    RNFileUploader: {
      addListener: jest.fn(),
      startUpload: jest.fn(async () => 'id-1'),
      cancelUpload: jest.fn(async () => true),
      getUnacknowledgedEvents: jest.fn(async () => [
        { eventId: 'e1', id: 'u1', type: 'completed', timestamp: 1, responseCode: 200 },
      ]),
      ackEvents: jest.fn(async () => true),
      getAllUploads: jest.fn(async () => [{ id: 'u1', state: 'running' }]),
    },
  },
}));

import { DeviceEventEmitter, NativeModules } from 'react-native';
import Upload from '../index';

/* eslint-disable @typescript-eslint/no-explicit-any */
const native = (NativeModules as any).RNFileUploader;
const deviceAddListener = (DeviceEventEmitter as any).addListener as jest.Mock;

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
  it('only invokes the listener for the matching upload id', () => {
    const cb = jest.fn();
    Upload.addListener('completed', 'u1', cb);
    const call = deviceAddListener.mock.calls.find(
      (c) => c[0] === 'RNFileUploader-completed',
    );
    const handler = call![1] as (data: unknown) => void;
    handler({ id: 'u1', responseCode: 200 });
    handler({ id: 'someone-else', responseCode: 200 });
    expect(cb).toHaveBeenCalledTimes(1);
  });
});
