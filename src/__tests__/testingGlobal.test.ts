// The pattern for an app that builds its client at module load: the
// TurboModuleRegistry mock hands out one fake, so the default client and
// every createUploadClient() run on it. The factory may require the fake
// because testing.ts loads no react-native code at runtime.
jest.mock('react-native', () => {
  const { createFakeNative } = jest.requireActual('../testing');
  const fake = createFakeNative();
  return {
    TurboModuleRegistry: { getEnforcing: () => fake, get: () => fake },
  };
});

import { TurboModuleRegistry } from 'react-native';
import Upload from '../index';
import type { FakeNative } from '../testing';

const native = TurboModuleRegistry.getEnforcing(
  'RNFileUploader',
) as unknown as FakeNative;

beforeEach(() => native.reset());

it('runs the default client on the fake from the registry mock', async () => {
  const onSuccess = jest.fn();
  const ping = Upload.define({
    key: 'ping',
    request: () => ({ url: 'https://api.test/ping' }),
    response: (raw) => raw as { ok: boolean },
    onSuccess,
  });
  Upload.configure({});
  const { id } = await ping.mutate();
  expect(native.entries).toEqual([
    expect.objectContaining({ id, key: 'ping', vars: null }),
  ]);
  const event = await native.settle(id, {
    kind: 'completed',
    response: { body: '{"ok":true}' },
  });
  expect(onSuccess).toHaveBeenCalledWith(
    { ok: true },
    null,
    expect.objectContaining({ id }),
  );
  expect(native.ackedEventIds).toEqual([event.eventId]);
});
