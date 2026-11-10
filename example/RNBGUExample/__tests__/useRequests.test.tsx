/**
 * @format
 */

import 'react-native';
import React from 'react';

// Note: import explicitly to use the types shipped with jest.
import {beforeEach, expect, it, jest} from '@jest/globals';

// Note: test renderer must be required after react-native.
import renderer, {act, type ReactTestRenderer} from 'react-test-renderer';

import {
  createUploadClient,
  type RequestRow,
  type RequestsFilter,
  type UploadClient,
} from 'react-native-background-upload';
import {createFakeNative, type FakeNative} from '../../../src/testing';

// The package root builds a default client over the TurboModule at load.
jest.mock('../../../src/NativeRNFileUploader', () => ({
  __esModule: true,
  default: jest
    .requireActual<typeof import('../../../src/testing')>(
      '../../../src/testing',
    )
    .createFakeNative(),
}));

const row = (overrides: Partial<RequestRow> = {}): RequestRow => ({
  id: 'a',
  key: 'k',
  vars: null,
  state: 'queued',
  bytesSent: 0,
  totalBytes: 100,
  attempts: 0,
  updatedAt: 1,
  ...overrides,
});

let native: FakeNative;
let client: UploadClient;

beforeEach(() => {
  native = createFakeNative();
  client = createUploadClient({native});
  client.configure({});
});

type Seen = {renders: number; results: Array<readonly RequestRow[]>};

const Rows = ({
  seen,
  filter,
  progress = false,
}: {
  seen: Seen;
  filter?: RequestsFilter;
  progress?: boolean;
}) => {
  const rows = progress
    ? client.useRequestsWithProgress(filter)
    : client.useRequests(filter);
  seen.renders += 1;
  seen.results.push(rows);
  return null;
};

const newSeen = (): Seen => ({renders: 0, results: []});
const last = (seen: Seen) => seen.results[seen.results.length - 1]!;

const mount = (element: React.ReactElement): ReactTestRenderer => {
  let tree!: ReactTestRenderer;
  act(() => {
    tree = renderer.create(element);
  });
  return tree;
};

it('reads native at the first render and at the first subscribe, not per hook', () => {
  native.seedRows([row()]);
  const getRequests = jest.spyOn(native, 'getRequests');
  const one = newSeen();
  const two = newSeen();
  mount(
    <>
      <Rows seen={one} />
      <Rows seen={two} progress />
    </>,
  );
  // One read for the render, one check when the first hook subscribes.
  expect(getRequests).toHaveBeenCalledTimes(2);
  expect(last(one)).toEqual([row()]);
  expect(last(two)).toEqual([row()]);
  // The check found nothing new, so no hook rendered again.
  expect(one.renders).toBe(1);
});

it('works under StrictMode, which subscribes twice', () => {
  native.seedRows([row()]);
  const seen = newSeen();
  const tree = mount(
    <React.StrictMode>
      <Rows seen={seen} progress />
    </React.StrictMode>,
  );
  expect(native.listenerCount('state')).toBe(1);
  expect(native.listenerCount('progress')).toBe(1);
  act(() => native.emit.progress({id: 'a', bytesSent: 7, totalBytes: 100}));
  expect(last(seen)[0]!.bytesSent).toBe(7);
  act(() => native.emit.state(row({state: 'running', updatedAt: 2})));
  expect(last(seen)[0]!.state).toBe('running');
  act(() => tree.unmount());
  expect(native.listenerCount('state')).toBe(0);
  expect(native.listenerCount('progress')).toBe(0);
});

it('follows a filter whose value changes', () => {
  native.seedRows([row({id: 'a'}), row({id: 'b', key: 'other'})]);
  const seen = newSeen();
  const tree = mount(<Rows seen={seen} filter={{id: 'a'}} />);
  expect(last(seen).map(r => r.id)).toEqual(['a']);
  act(() => tree.update(<Rows seen={seen} filter={{id: 'b'}} />));
  expect(last(seen).map(r => r.id)).toEqual(['b']);
  act(() => tree.update(<Rows seen={seen} filter={{key: 'k'}} />));
  expect(last(seen).map(r => r.id)).toEqual(['a']);
  act(() => native.emit.state(row({id: 'b', key: 'other', state: 'running'})));
  expect(last(seen).map(r => r.id)).toEqual(['a']);
});

it('re-renders on a state event, and only the progress hook on progress', () => {
  native.seedRows([row()]);
  const plain = newSeen();
  const withProgress = newSeen();
  mount(
    <>
      <Rows seen={plain} />
      <Rows seen={withProgress} progress />
    </>,
  );
  const plainRenders = plain.renders;
  const progressRenders = withProgress.renders;

  act(() => native.emit.progress({id: 'a', bytesSent: 40, totalBytes: 100}));
  expect(plain.renders).toBe(plainRenders);
  expect(withProgress.renders).toBe(progressRenders + 1);
  expect(last(withProgress)[0]!.bytesSent).toBe(40);
  expect(last(plain)[0]!.bytesSent).toBe(0);

  act(() => native.emit.state(row({state: 'running', updatedAt: 2})));
  expect(plain.renders).toBe(plainRenders + 1);
  expect(last(plain)[0]!.state).toBe('running');
  expect(last(withProgress)[0]!.state).toBe('running');
});

it('returns the same filtered array across renders while its rows hold', () => {
  native.seedRows([row({id: 'a'}), row({id: 'b'})]);
  const seen = newSeen();
  // A new filter object each render, as an inline literal is.
  const tree = mount(<Rows seen={seen} filter={{id: 'a'}} />);
  act(() => tree.update(<Rows seen={seen} filter={{id: 'a'}} />));
  expect(seen.renders).toBe(2);
  expect(seen.results[1]).toBe(seen.results[0]);

  // A change to another row does not re-render this one.
  act(() => native.emit.state(row({id: 'b', state: 'running'})));
  expect(seen.renders).toBe(2);

  act(() => native.emit.state(row({id: 'a', state: 'running'})));
  expect(seen.renders).toBe(3);
  expect(last(seen)).toEqual([row({id: 'a', state: 'running'})]);
});

it('drops a completed row once its outcome is acknowledged', async () => {
  const ping = client.define({
    key: 'ping',
    request: () => ({url: 'https://api.test/ping'}),
  });
  const seen = newSeen();
  mount(<Rows seen={seen} />);
  let id = '';
  await act(async () => {
    ({id} = await ping.mutate());
  });
  expect(last(seen)).toEqual([expect.objectContaining({id, state: 'queued'})]);
  await act(async () => {
    await native.settle(id, {kind: 'completed'});
  });
  expect(last(seen)).toEqual([]);
});

it('drops a settled row when cancel() resolves, and hides cancelled rows', async () => {
  native.seedRows([
    row({id: 'e', state: 'error'}),
    row({id: 'c', state: 'cancelled'}),
  ]);
  const visible = newSeen();
  const all = newSeen();
  mount(
    <>
      <Rows seen={visible} />
      <Rows seen={all} filter={{includeCancelled: true}} />
    </>,
  );
  expect(last(visible).map(r => r.id)).toEqual(['e']);
  expect(last(all).map(r => r.id)).toEqual(['e', 'c']);
  await act(async () => {
    await client.cancel('e');
  });
  expect(last(visible)).toEqual([]);
  expect(last(all).map(r => r.id)).toEqual(['c']);
});

it('re-checks native when a hook mounts again', () => {
  native.seedRows([row({id: 'a'})]);
  const first = newSeen();
  const tree = mount(<Rows seen={first} />);
  act(() => tree.unmount());
  // A change with no event while nothing is mounted.
  native.seedRows([row({id: 'b'})]);
  const second = newSeen();
  mount(<Rows seen={second} />);
  expect(last(second).map(r => r.id)).toEqual(['a', 'b']);
});

it('types the vars of a definition', () => {
  type Vars = {captureId: string};
  const upload = client.define({
    key: 'capture',
    request: (_vars: Vars) => ({url: 'https://api.test/c'}),
  });
  native.seedRows([
    row({id: 'x', key: 'capture', vars: {captureId: 'c1'}}),
    row({id: 'y', key: 'other'}),
  ]);
  const ids: string[][] = [];
  const Captures = () => {
    const rows = upload.useRequests();
    ids.push(rows.map(r => r.vars.captureId));
    return null;
  };
  mount(<Captures />);
  expect(ids[ids.length - 1]).toEqual(['c1']);
});
