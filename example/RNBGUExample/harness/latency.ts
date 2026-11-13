import * as RNFS from 'react-native-fs';
import Upload from 'react-native-background-upload';

import {MB, TEST_DIR, makeFile, remove} from './files';
import {log} from './log';
import {postJson, putChunked20} from './uploads';

// The enqueue latency run. It measures what Diana does after a capture: the
// file mutate(), then at once the metadata mutate(). nextMs is how long the
// second mutate() waits. Before 11.2.0 it waited while iOS copied every part
// of the file, and a 2 GiB file could pass the 10 s enqueue timeout.

export const LATENCY_RUNS = 7;
const SOURCE_MB = 2048;
const SOURCE_NAME = 'latency-src.bin';
const SOURCE = `${TEST_DIR}/${SOURCE_NAME}`;

/** The 2 GiB source, made one time and kept. Making it takes minutes. */
const source = async (): Promise<number> => {
  const size = SOURCE_MB * MB;
  if (await RNFS.exists(SOURCE)) {
    const stat = await RNFS.stat(SOURCE);
    if (Number(stat.size) === size) {
      return size;
    }
  }
  log('action', `latency: making the ${SOURCE_MB} MiB source, one time`);
  await makeFile(SOURCE_NAME, SOURCE_MB, {md5: false});
  return size;
};

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

/**
 * Waits until native forgets `ids` (cancel, then the automatic ack). Logs a
 * warning when they are still there after 60 s.
 */
const waitGone = async (ids: string[]) => {
  const until = Date.now() + 60_000;
  const left = () => Upload.getRequests().filter(r => ids.includes(r.id));
  while (left().length > 0) {
    if (Date.now() >= until) {
      log(
        'warn',
        `latency: still in the queue after 60 s: ${left()
          .map(r => `${r.id} ${r.state}`)
          .join(', ')}`,
      );
      return;
    }
    await sleep(200);
  }
};

export type LatencyResult = {nextMs: number[]; median: number; max: number};

/**
 * LATENCY_RUNS runs. Each: copy the source, mutate() it as a chunked upload
 * in 20 MiB parts, then at once mutate() a small JSON request. Both go to
 * the server's `slow` mode, so no part finishes during the run. The run
 * calls resume() first: a paused entry builds no parts. Then both are
 * cancelled, so the next run has the disk space.
 */
export const measureEnqueueLatency = async (
  host: string,
): Promise<LatencyResult> => {
  const size = await source();
  // A paused entry builds no parts.
  await Upload.resume();
  const nextMs: number[] = [];
  for (let i = 1; i <= LATENCY_RUNS; i++) {
    const run = `${Date.now().toString(36)}-${i}`;
    const file = `${TEST_DIR}/latency-${run}.bin`;
    const c0 = Date.now();
    await RNFS.copyFile(SOURCE, file);
    const copyMs = Date.now() - c0;
    const t0 = Date.now();
    const cap = await putChunked20.mutate({
      host,
      path: `/slow/chunk/${run}`,
      file,
      size,
    });
    const t1 = Date.now();
    const meta = await postJson.mutate({
      host,
      path: `/slow/json/${run}`,
      n: i,
      text: 'latency',
    });
    const t2 = Date.now();
    nextMs.push(t2 - t1);
    log(
      'action',
      `latency run ${i}/${LATENCY_RUNS}: copyMs=${copyMs} fileMs=${
        t1 - t0
      } nextMs=${t2 - t1}`,
    );
    await Upload.cancel(cap.id);
    await Upload.cancel(meta.id);
    await remove(file); // the library moved it; this is a guard
    await waitGone([cap.id, meta.id]);
  }
  const sorted = [...nextMs].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)];
  const max = sorted[sorted.length - 1];
  log(
    'action',
    `latency nextMs median=${median} max=${max} ` +
      `(target: median < 500, max < 2000) all=${nextMs.join(',')}`,
  );
  return {nextMs, median, max};
};
