/**
 * @format
 */

import 'react-native';
import React from 'react';
import {Text} from 'react-native';

// Note: import explicitly to use the types shipped with jest.
import {afterEach, beforeEach, expect, it, jest} from '@jest/globals';

// Note: test renderer must be required after react-native.
import renderer, {act, type ReactTestRenderer} from 'react-test-renderer';

// The library's TurboModule, as a stub. The harness must call configure()
// at boot, and each button must reach enqueue() with the right descriptor.
const mockSubscription = () => ({remove: jest.fn()});
const mockNative = {
  configure: jest.fn(),
  enqueue: jest.fn((entry: {id: string}) => Promise.resolve(entry.id)),
  pause: jest.fn(() => Promise.resolve()),
  resume: jest.fn(() => Promise.resolve()),
  cancel: jest.fn(() => Promise.resolve()),
  setWifiOnly: jest.fn(() => Promise.resolve()),
  updateHeaders: jest.fn(() => Promise.resolve()),
  getRequests: jest.fn(() => []),
  getUnacknowledgedEvents: jest.fn(() => Promise.resolve([])),
  ackEvents: jest.fn(() => Promise.resolve()),
  onState: jest.fn(mockSubscription),
  onProgress: jest.fn(mockSubscription),
  onAttempt: jest.fn(mockSubscription),
  onSettled: jest.fn(mockSubscription),
  onNotification: jest.fn(mockSubscription),
};
jest.mock('../../../src/NativeRNFileUploader', () => ({
  __esModule: true,
  default: mockNative,
}));
jest.mock('react-native-fs', () => ({
  DocumentDirectoryPath: '/docs',
  exists: jest.fn(() => Promise.resolve(false)),
  readFile: jest.fn(() => Promise.resolve('{}')),
  writeFile: jest.fn(() => Promise.resolve()),
  stat: jest.fn(() => Promise.resolve({size: 2048 * 1024 * 1024})),
  copyFile: jest.fn(() => Promise.resolve()),
}));
jest.mock('@notifee/react-native', () => ({
  __esModule: true,
  default: {createChannel: jest.fn(() => Promise.resolve('rnbgu-uploads'))},
  AndroidImportance: {LOW: 2},
}));

const App = require('../App').default;
const {ready} = require('../harness/uploads');

const press = async (tree: ReactTestRenderer, title: string) => {
  const button = tree.root.find(
    node =>
      node.props.accessibilityRole === 'button' &&
      node.findAllByType(Text).some(t => t.props.children === title),
  );
  await act(async () => {
    button.props.onPress();
  });
};

let tree: ReactTestRenderer;

beforeEach(async () => {
  await act(async () => {
    tree = renderer.create(<App />);
    await ready;
  });
});

// Unmount, so the screen's 1 s getRequests() timer stops.
afterEach(() => {
  act(() => tree.unmount());
});

it('calls configure() at boot with the notification config', () => {
  expect(mockNative.configure).toHaveBeenCalledTimes(1);
  expect(mockNative.configure.mock.calls[0][0]).toMatchObject({
    notificationChannel: 'rnbgu-uploads',
    notificationTitleNoWifi: 'Waiting for Wi-Fi...',
  });
});

it('enqueues a JSON POST with the headers provider value', async () => {
  await press(tree, 'JSON POST');
  const entry = mockNative.enqueue.mock.calls[0][0] as unknown as {
    key: string;
    descriptor: {url: string; dataJson: string; headers: object};
  };
  expect(entry.key).toBe('harness.json');
  expect(entry.descriptor.url).toMatch(
    /^http:\/\/localhost:3000\/ok\/json\/\w+$/,
  );
  expect(JSON.parse(entry.descriptor.dataJson)).toEqual({n: 1, text: 'hi'});
  expect(entry.descriptor.headers).toEqual({Authorization: 'Bearer bad'});
});

it('sends a bodiless DELETE', async () => {
  await press(tree, 'DELETE');
  const entry = mockNative.enqueue.mock.calls.at(-1)?.[0] as unknown as {
    descriptor: {method: string; dataJson?: string};
  };
  expect(entry.descriptor.method).toBe('DELETE');
  expect(entry.descriptor).not.toHaveProperty('dataJson');
});

it('rejects a GET with a body before native sees it', async () => {
  const before = mockNative.enqueue.mock.calls.length;
  await press(tree, 'GET with a body');
  expect(mockNative.enqueue.mock.calls.length).toBe(before);
});

it('runs the enqueue latency measurement 7 times on /slow', async () => {
  const fs = require('react-native-fs') as {
    exists: jest.Mock<(path: string) => Promise<boolean>>;
    copyFile: jest.Mock;
  };
  // The 2 GiB source is already made.
  fs.exists.mockImplementation(path =>
    Promise.resolve(path.endsWith('/latency-src.bin')),
  );
  const before = mockNative.enqueue.mock.calls.length;
  try {
    await press(tree, 'Enqueue latency x7: 2 GiB, 20 MiB parts, on /slow');
    for (let i = 0; i < 100 && mockNative.cancel.mock.calls.length < 14; i++) {
      await act(async () => {
        await new Promise(r => setTimeout(r, 0));
      });
    }
  } finally {
    fs.exists.mockImplementation(() => Promise.resolve(false));
  }
  const entries = mockNative.enqueue.mock.calls
    .slice(before)
    .map(c => c[0] as unknown as {key: string; descriptor: any});
  expect(entries.map(e => e.key)).toEqual(
    Array(7).fill(['harness.chunked20', 'harness.json']).flat(),
  );
  const parts = entries[0].descriptor.parts as Array<{
    url: string;
    headers: Record<string, string>;
  }>;
  expect(parts).toHaveLength(103);
  expect(parts[0].url).toMatch(/\/slow\/chunk\/[\w-]+\/1$/);
  expect(parts[0].headers['Content-Range']).toBe('0-20971519/2147483648');
  expect(entries[1].descriptor.url).toMatch(/\/slow\/json\//);
  expect(fs.copyFile).toHaveBeenCalledTimes(7);
  expect(mockNative.cancel).toHaveBeenCalledTimes(14);
});
