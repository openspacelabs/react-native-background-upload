import {Platform} from 'react-native';
import notifee, {AndroidImportance} from '@notifee/react-native';
import Upload, {
  type Meta,
  type OutcomeError,
  type RequestRow,
} from 'react-native-background-upload';

import {MB} from './files';
import {clock, log} from './log';
import {loadSettings, settings} from './settings';

// The request kinds, the listeners, and configure(). All of it runs at module
// scope, as in a real app: every define() before configure(), and the
// listeners before the boot replay.

export const LAUNCHED_AT = Date.now();

/** Each part of a chunked upload is 5 MiB, so 30 MiB gives 6 parts. */
export const PART_BYTES = 5 * MB;

/** The test server's route modes. See example/server/src/index.ts. */
export const MODES = [
  'ok',
  'flaky',
  'auth',
  'auth-once',
  'auth-slow',
  'bad',
  'gone',
  'conflict',
  'down',
  'slow',
  'big',
] as const;
export type Mode = (typeof MODES)[number];

/**
 * State that the screen edits and the definitions read at mutate(). `auth`
 * is what the configure() headers provider returns. It changes only when the
 * screen calls updateHeaders(), so new requests carry what native carries.
 */
export const session = {
  auth: 'Bearer bad',
  mode: 'ok' as Mode,
};

/** What every request kind carries in its vars. */
type Target = {
  host: string;
  /** Starts with the mode, for example /ok/json/abc. */
  path: string;
  /** Seconds from mutate() to expiresAt. Absent means the default. */
  expiresInS?: number;
  silent?: boolean;
};

const common = (v: Target) => ({
  ...(v.expiresInS ? {expiresAt: Date.now() + v.expiresInS * 1000} : {}),
  ...(v.silent ? {android: {noNotification: true}} : {}),
});

const short = (s: string | undefined) => (s ? s.slice(0, 8) : '-');

const pathOf = (url: string) => url.replace(/^https?:\/\/[^/]+/, '');

// Logs one outcome. An outcome whose native time is before this launch was
// journaled by an earlier process and replayed after configure().
const outcome = (label: string, meta: Meta, text: string) => {
  const journaled = meta.at < LAUNCHED_AT;
  log(
    'outcome',
    `${label} ${meta.key} id=${meta.id} ${text} deliveries=${meta.deliveries} ` +
      `attempts=${meta.attempts} rid=${short(meta.requestId)} at=${clock(
        meta.at,
      )}${journaled ? ' JOURNALED' : ''}`,
    {journaled},
  );
  if (settings.throwInHandlers) {
    throw new Error('harness: the handler throws on purpose');
  }
};

const errorText = (e: OutcomeError, v: {path: string} | null) =>
  `${v?.path ?? ''} errorKind=${e.errorKind} status=${
    e.response?.status ?? '-'
  } part=${e.partIndex ?? '-'} ${e.message.slice(0, 120)}`;

// A 409 whose body says "already completed" counts as success, as in Diana.
const ALREADY_DONE = [{status: 409, bodyIncludes: 'already completed'}];

export type JsonVars = Target & {n: number; text: string};

/** A JSON POST with a response parser. */
export const postJson = Upload.define({
  key: 'harness.json',
  request: (v: JsonVars) => ({
    url: v.host + v.path,
    data: {n: v.n, text: v.text},
    accept: ALREADY_DONE,
    ...common(v),
  }),
  response: raw => raw as {ok?: boolean; bytes?: number} | undefined,
  onSuccess: (data, v, meta) =>
    outcome('ok', meta, `${v.path} body=${JSON.stringify(data)}`),
  onError: (e, v, meta) => outcome('error', meta, errorText(e, v)),
});

export type FormVars = Target & {photo: string; caption: string};

/** A multipart photo upload: a JSON string part and a file part. */
export const postForm = Upload.define({
  key: 'harness.form',
  request: (v: FormVars) => ({
    url: v.host + v.path,
    form: [
      {
        name: 'meta',
        contentType: 'application/json',
        string: JSON.stringify({caption: v.caption}),
      },
      {
        name: 'photo',
        contentType: 'image/jpeg',
        path: v.photo,
        fileName: 'photo.jpg',
      },
    ],
    ...common(v),
  }),
  onSuccess: (raw, v, meta) =>
    outcome('ok', meta, `${v.path} status=${raw.status}`),
  onError: (e, v, meta) => outcome('error', meta, errorText(e, v)),
});

export type FileVars = Target & {file: string};

/** A whole-file PUT. The library copies the file at mutate(). */
export const putFile = Upload.define({
  key: 'harness.file',
  request: (v: FileVars) => ({
    url: v.host + v.path,
    method: 'PUT',
    file: v.file,
    headers: {'Content-Type': 'application/octet-stream'},
    ...common(v),
  }),
  onSuccess: (raw, v, meta) =>
    outcome('ok', meta, `${v.path} status=${raw.status}`),
  onError: (e, v, meta) => outcome('error', meta, errorText(e, v)),
});

export type ChunkedVars = Target & {file: string; size: number};

/**
 * A chunked PUT. The library moves the file at mutate(). Part URLs are
 * `${path}/${n}`, 1-indexed, with a Diana-style Content-Range. A 404 on a
 * part is terminal, as in Diana, so onError could recreate the upload.
 */
export const putChunked = Upload.define({
  key: 'harness.chunked',
  request: (v: ChunkedVars) => {
    const ranges = Upload.chunkPlan(v.size, {min: PART_BYTES, max: PART_BYTES});
    return {
      method: 'PUT',
      file: v.file,
      headers: {'Content-Type': 'application/octet-stream'},
      parts: ranges.map((range, i) => ({
        url: `${v.host}${v.path}/${i + 1}`,
        range,
        headers: {
          'Content-Range': `${range.start}-${range.end - 1}/${v.size}`,
        },
      })),
      accept: ALREADY_DONE,
      retry: {terminalHttp: {exempt: []}},
      ...common(v),
    };
  },
  onSuccess: (raw, v, meta) =>
    outcome('ok', meta, `${v.path} chunked status=${raw.status ?? 'none'}`),
  onError: (e, v, meta) => outcome('error', meta, errorText(e, v)),
});

/**
 * A bodiless DELETE. `data: undefined` is spelled out on purpose: Diana's
 * generated requests pass it that way.
 */
export const deleteThing = Upload.define({
  key: 'harness.delete',
  request: (v: Target) => ({
    url: v.host + v.path,
    method: 'DELETE',
    data: undefined,
    ...common(v),
  }),
  onSuccess: (raw, v, meta) =>
    outcome('ok', meta, `${v.path} status=${raw.status}`),
  onError: (e, v, meta) => outcome('error', meta, errorText(e, v)),
});

/** A bodiless GET whose request takes no vars, so mutate() takes none. */
export const getNoVars = Upload.define({
  key: 'harness.get',
  request: () => ({
    url: `${settings.host}/${session.mode}/get`,
    method: 'GET',
  }),
  onSuccess: (raw, _v, meta) => outcome('ok', meta, `GET status=${raw.status}`),
  onError: (e, _v, meta) => outcome('error', meta, errorText(e, null)),
});

/** A GET with a body. mutate() must reject it. */
export const getWithBody = Upload.define({
  key: 'harness.get-body',
  request: (v: Target) => ({
    url: v.host + v.path,
    method: 'GET',
    data: {not: 'allowed'},
  }),
});

/**
 * The definitions that a row's key maps to, for the "Same id" row buttons.
 * The file-backed keys are not here. The screen deletes the form and file
 * sources after mutate(), and the library moves the chunked source, so a
 * second mutate() with the stored vars rejects with E_FILE_MISSING.
 */
export const byKey: Record<
  string,
  {mutate: (vars: never, options?: {id?: string}) => Promise<{id: string}>}
> = {
  [postJson.key]: postJson,
  [deleteThing.key]: deleteThing,
  [getNoVars.key]: getNoVars,
};

// Live progress, keyed by id. Rows from getRequests() are read on a timer;
// this map carries the bytes between two reads.
export const progress = new Map<
  string,
  {bytesSent: number; totalBytes: number}
>();

Upload.addListener('state', e => {
  const next = e.nextAttemptAt
    ? ` next=${Math.max(0, Math.round((e.nextAttemptAt - Date.now()) / 1000))}s`
    : '';
  log(
    'state',
    `${e.id} ${e.key} -> ${e.state} attempts=${e.attempts} bytes=${
      e.bytesSent
    }/${e.totalBytes}${next}${e.reason ? ` reason=${e.reason}` : ''}`,
  );
});

Upload.addListener('progress', p => {
  const last = progress.get(p.id);
  if (last && p.bytesSent < last.bytesSent) {
    log(
      'warn',
      `${p.id} progress went back ${last.bytesSent} -> ${p.bytesSent}`,
    );
  }
  progress.set(p.id, {bytesSent: p.bytesSent, totalBytes: p.totalBytes});
});

Upload.addListener('attempt', a => {
  log(
    'attempt',
    `${a.id} #${a.attempt} ${a.method} ${pathOf(a.url)} part=${
      a.partIndex ?? '-'
    } ${a.outcome} http=${a.httpCode ?? '-'}${
      a.errorKind ? ` errorKind=${a.errorKind}` : ''
    }${a.errorMessage ? ` ${a.errorMessage.slice(0, 100)}` : ''} rid=${short(
      a.requestId,
    )}`,
  );
});

Upload.android.addNotificationListener(() =>
  log('action', 'the upload notification was pressed'),
);

export const NOTIFICATION_CHANNEL = 'rnbgu-uploads';

const describeRows = (rows: RequestRow[]) => {
  const counts = new Map<string, number>();
  rows.forEach(r => counts.set(r.state, (counts.get(r.state) ?? 0) + 1));
  return [...counts].map(([s, n]) => `${s}=${n}`).join(' ') || 'none';
};

/**
 * Loads the settings, registers the Android channel, then calls
 * configure(). The screen waits for this before it enables the buttons.
 */
export const ready: Promise<void> = (async () => {
  await loadSettings();
  if (Platform.OS === 'android') {
    // Diana registers its own LOW channel too. The library only creates a
    // channel when this one is absent.
    await notifee
      .createChannel({
        id: NOTIFICATION_CHANNEL,
        name: 'Upload progress',
        importance: AndroidImportance.LOW,
      })
      .catch(e => log('warn', `createChannel failed: ${String(e)}`));
  }
  const atBoot = Upload.getRequests();
  log(
    'action',
    `launched at ${clock(LAUNCHED_AT)}. Rows before configure: ${describeRows(
      atBoot,
    )}`,
  );
  Upload.configure({
    headers: () => ({Authorization: session.auth}),
    android: {
      notificationId: 'rnbgu-upload-progress',
      notificationTitle: 'Uploading test files',
      notificationTitleNoWifi: 'Waiting for Wi-Fi...',
      notificationTitleNoInternet: 'Waiting for internet...',
      notificationChannel: NOTIFICATION_CHANNEL,
    },
  });
})();
