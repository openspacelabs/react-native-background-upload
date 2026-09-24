/**
 * The v10 device test harness. Every step of the device test script in
 * README.md can be done from this screen. The request kinds, the listeners,
 * and configure() are in ./harness/uploads.ts.
 *
 * @format
 */

import React, {useEffect, useState, useSyncExternalStore} from 'react';
import {
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import Upload, {type RequestRow} from 'react-native-background-upload';

import {
  exists,
  makeFile,
  makePhoto,
  missingPath,
  remove,
  type TestFile,
} from './harness/files';
import {clearLog, clock, getLog, log, subscribeLog} from './harness/log';
import type {LogEntry, LogKind} from './harness/log';
import {saveSettings, settings} from './harness/settings';
import {
  byKey,
  deleteThing,
  getNoVars,
  getWithBody,
  LAUNCHED_AT,
  MODES,
  PART_BYTES,
  postForm,
  postJson,
  progress,
  putChunked,
  putFile,
  ready,
  session,
  type Mode,
} from './harness/uploads';

const runId = () =>
  Date.now().toString(36) + Math.random().toString(36).slice(2, 5);

let counter = 0;

type RejectLike = {code?: string; message?: string};

// Runs one button's work and logs the result or the rejection code.
const run = (label: string, work: () => Promise<unknown>) => () => {
  log('action', `${label}...`);
  work().then(
    result =>
      log(
        'action',
        `${label}: resolved${
          result === undefined ? '' : ` ${JSON.stringify(result)}`
        }`,
      ),
    (e: RejectLike) =>
      log(
        'warn',
        `${label}: rejected code=${e?.code ?? '-'} ${e?.message ?? String(e)}`,
      ),
  );
};

const fileNote = (f: TestFile) =>
  log('action', `file ${f.path.split('/').pop()} ${f.size} B md5=${f.md5}`);

// The path after its first segment: /ok/json/abc gives /json/abc.
const afterMode = (path: string) => {
  const i = path.indexOf('/', 1);
  return i < 0 ? '' : path.slice(i);
};

const App = () => {
  const [isReady, setReady] = useState(false);
  const [host, setHost] = useState(settings.host);
  const [mode, setMode] = useState<Mode>(session.mode);
  const [authDraft, setAuthDraft] = useState('Bearer good');
  const [auth, setAuth] = useState(session.auth);
  const [customId, setCustomId] = useState('');
  const [expiresInS, setExpiresInS] = useState('');
  const [sizeMb, setSizeMb] = useState('30');
  const [wifiOnly, setWifiOnlyMirror] = useState(settings.wifiOnly);
  const [throwInHandlers, setThrow] = useState(settings.throwInHandlers);
  const [silent, setSilent] = useState(settings.silent);
  const [rows, setRows] = useState<RequestRow[]>([]);
  const [filter, setFilter] = useState<LogKind | 'all'>('all');
  const entries = useSyncExternalStore(subscribeLog, getLog);

  useEffect(() => {
    ready.then(
      () => {
        setHost(settings.host);
        setWifiOnlyMirror(settings.wifiOnly);
        setThrow(settings.throwInHandlers);
        setSilent(settings.silent);
        setReady(true);
      },
      e => log('warn', `boot failed: ${String(e)}`),
    );
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      // The progress notification is also the foreground-service
      // notification. Without this permission Android 13+ hides it.
      PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
      ).then(r => log('action', `POST_NOTIFICATIONS: ${r}`));
    }
  }, []);

  // getRequests() is synchronous and cheap. A 1 s read also refreshes the
  // backoff countdowns and drops rows that native forgot after an ack.
  useEffect(() => {
    if (!isReady) return;
    const read = () => setRows(Upload.getRequests());
    read();
    const sub = Upload.addListener('state', read);
    const timer = setInterval(read, 1000);
    return () => {
      sub.remove();
      clearInterval(timer);
    };
  }, [isReady]);

  const target = (path: string) => ({
    host: settings.host,
    path,
    ...(Number(expiresInS) > 0 ? {expiresInS: Number(expiresInS)} : {}),
    ...(settings.silent ? {silent: true} : {}),
  });
  const idOption = () => (customId.trim() ? {id: customId.trim()} : undefined);
  const mb = () => Math.max(1, Math.floor(Number(sizeMb) || 1));

  const enqueueJson = (m: Mode = mode, id = idOption()) =>
    postJson.mutate(
      {...target(`/${m}/json/${runId()}`), n: ++counter, text: 'hi'},
      id,
    );

  const enqueueChunked = async (m: Mode = mode, id = idOption()) => {
    const f = await makeFile(`chunked-${runId()}.bin`, mb());
    fileNote(f);
    const r = await putChunked.mutate(
      {...target(`/${m}/chunk/${runId()}`), file: f.path, size: f.size},
      id,
    );
    log(
      'action',
      `chunked ${r.id}: ${Math.ceil(f.size / PART_BYTES)} parts. Source ${
        (await exists(f.path)) ? 'still there (unexpected)' : 'moved'
      }`,
    );
    return r;
  };

  const actions = {
    json: run('JSON POST', () => enqueueJson()),
    form: run('Multipart photo', async () => {
      const photo = await makePhoto(`photo-${runId()}.jpg`);
      fileNote(photo);
      const r = await postForm.mutate(
        {
          ...target(`/${mode}/form/${runId()}`),
          photo: photo.path,
          caption: 'test',
        },
        idOption(),
      );
      // The library copied the file, so the source can go now.
      await remove(photo.path);
      log('action', `form ${r.id}: source deleted after mutate()`);
      return r;
    }),
    file: run('File PUT', async () => {
      const f = await makeFile(`file-${runId()}.bin`, mb());
      fileNote(f);
      const r = await putFile.mutate(
        {...target(`/${mode}/file/${runId()}`), file: f.path},
        idOption(),
      );
      await remove(f.path);
      log('action', `file ${r.id}: source deleted after mutate()`);
      return r;
    }),
    chunked: run('Chunked PUT', () => enqueueChunked()),
    del: run('DELETE', () =>
      deleteThing.mutate(target(`/${mode}/thing/${runId()}`), idOption()),
    ),
    get: run('GET', () => getNoVars.mutate(null, idOption())),
    threeJson: run('3 JSON at once', () =>
      Promise.all([1, 2, 3].map(() => enqueueJson(mode, undefined))),
    ),
    capTest: run('Cap test', async () => {
      await fetch(`${settings.host}/stats/reset`, {method: 'POST'});
      return Promise.all([
        ...[1, 2, 3].map(() => enqueueChunked('slow', undefined)),
        ...[1, 2, 3].map(() => enqueueJson('slow', undefined)),
      ]);
    }),
    stats: run('Server stats', async () =>
      (await fetch(`${settings.host}/stats`)).json(),
    ),
    ping: run('Ping server (plain fetch)', async () =>
      (await fetch(`${settings.host}/`)).text(),
    ),
    missing: run('Missing file', () =>
      putFile.mutate({...target(`/${mode}/file/missing`), file: missingPath()}),
    ),
    getBody: run('GET with a body', () =>
      getWithBody.mutate(target(`/${mode}/get-body`)),
    ),
    pause: run('pause()', () => Upload.pause()),
    resume: run('resume()', () => Upload.resume()),
    updateHeaders: run(`updateHeaders(${authDraft})`, async () => {
      await Upload.updateHeaders({Authorization: authDraft});
      session.auth = authDraft;
      setAuth(authDraft);
    }),
    cancelUnknown: run("cancel('nope')", () => Upload.cancel('nope')),
  };

  const onWifiOnly = (enabled: boolean) =>
    run(`setWifiOnly(${enabled})`, async () => {
      await Upload.setWifiOnly(enabled);
      saveSettings({wifiOnly: enabled});
      setWifiOnlyMirror(enabled);
    })();

  // Same id and the stored vars: the same body. A different mode and path
  // is a different body.
  const again = (row: RequestRow, newBody: boolean) => {
    const def = byKey[row.key];
    let vars = row.vars as {path?: string} | null;
    if (newBody && vars?.path) {
      vars = {...vars, path: `/${mode}${afterMode(vars.path)}-b`};
    }
    run(`${newBody ? 'new body' : 'same body'} ${row.id}`, () =>
      def.mutate(vars as never, {id: row.id}),
    )();
  };

  const shown = entries.filter(e => filter === 'all' || e.kind === filter);
  const journaled = entries.filter(e => e.journaled);

  return (
    <View style={styles.root}>
      <StatusBar barStyle="dark-content" />
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.content}>
        <Text style={styles.title}>RNBGU v10 test harness</Text>
        <Text style={styles.small}>
          {Platform.OS} launched {clock(LAUNCHED_AT)}.{' '}
          {isReady ? 'configure() done.' : 'Booting...'}
        </Text>

        <Section title="Server and auth">
          <Field
            label="Server"
            value={host}
            onChangeText={v => {
              // Saved on each change, so a button pressed while the keyboard
              // is open uses the new value.
              setHost(v);
              saveSettings({host: v.trim()});
            }}
          />
          <Row>
            <Btn title="Ping server" onPress={actions.ping} />
            <Btn title="Server stats" onPress={actions.stats} />
          </Row>
          <Text style={styles.small}>Headers provider sends: {auth}</Text>
          <Field
            label="Authorization"
            value={authDraft}
            onChangeText={setAuthDraft}
          />
          <Btn
            title="updateHeaders + use for new requests"
            onPress={actions.updateHeaders}
          />
        </Section>

        <Section title="Options for new requests">
          <Text style={styles.small}>Server mode (first path segment)</Text>
          <View style={styles.wrap}>
            {MODES.map(m => (
              <Chip
                key={m}
                title={m}
                active={m === mode}
                onPress={() => {
                  session.mode = m;
                  setMode(m);
                }}
              />
            ))}
          </View>
          <Field
            label="Id (blank = new UUID)"
            value={customId}
            onChangeText={setCustomId}
          />
          <Field
            label="Expires in s (blank = default)"
            value={expiresInS}
            onChangeText={setExpiresInS}
            keyboardType="number-pad"
          />
          <Field
            label="File size MiB"
            value={sizeMb}
            onChangeText={setSizeMb}
            keyboardType="number-pad"
          />
          <Toggle
            label="Silent (android.noNotification)"
            value={silent}
            onValueChange={v => {
              saveSettings({silent: v});
              setSilent(v);
            }}
          />
          <Toggle
            label="Handlers throw (kept after relaunch)"
            value={throwInHandlers}
            onValueChange={v => {
              saveSettings({throwInHandlers: v});
              setThrow(v);
            }}
          />
        </Section>

        <Section title="Enqueue">
          <Row>
            <Btn title="JSON POST" onPress={actions.json} disabled={!isReady} />
            <Btn
              title="Multipart photo"
              onPress={actions.form}
              disabled={!isReady}
            />
          </Row>
          <Row>
            <Btn
              title={`File PUT ${sizeMb} MiB`}
              onPress={actions.file}
              disabled={!isReady}
            />
            <Btn
              title={`Chunked PUT ${sizeMb} MiB`}
              onPress={actions.chunked}
              disabled={!isReady}
            />
          </Row>
          <Row>
            <Btn title="DELETE" onPress={actions.del} disabled={!isReady} />
            <Btn
              title="GET (no vars)"
              onPress={actions.get}
              disabled={!isReady}
            />
          </Row>
          <Row>
            <Btn
              title="3 JSON at once"
              onPress={actions.threeJson}
              disabled={!isReady}
            />
            <Btn
              title="Cap test: 3 chunked + 3 JSON on /slow"
              onPress={actions.capTest}
              disabled={!isReady}
            />
          </Row>
          <Row>
            <Btn
              title="Missing file"
              onPress={actions.missing}
              disabled={!isReady}
            />
            <Btn
              title="GET with a body"
              onPress={actions.getBody}
              disabled={!isReady}
            />
          </Row>
        </Section>

        <Section title="Queue">
          <Row>
            <Btn title="pause()" onPress={actions.pause} />
            <Btn title="resume()" onPress={actions.resume} />
            <Btn title="cancel('nope')" onPress={actions.cancelUnknown} />
          </Row>
          <Toggle
            label="setWifiOnly (app's last call)"
            value={wifiOnly}
            onValueChange={onWifiOnly}
          />
          <Text style={styles.small}>
            {rows.length} rows from getRequests()
          </Text>
          {rows.map(row => (
            <RequestCard
              key={row.id}
              row={row}
              canAgain={row.key in byKey}
              onCancel={run(`cancel ${row.id}`, () => Upload.cancel(row.id))}
              onAgain={() => again(row, false)}
              onNewBody={() => again(row, true)}
            />
          ))}
        </Section>

        {journaled.length > 0 && (
          <Section title={`Journaled before this launch (${journaled.length})`}>
            {journaled.map(e => (
              <LogLine key={e.seq} entry={e} />
            ))}
          </Section>
        )}

        <Section title="Log">
          <View style={styles.wrap}>
            {(
              ['all', 'attempt', 'state', 'outcome', 'action', 'warn'] as const
            ).map(k => (
              <Chip
                key={k}
                title={k}
                active={k === filter}
                onPress={() => setFilter(k)}
              />
            ))}
            <Chip title="clear" active={false} onPress={clearLog} />
          </View>
          {shown.slice(0, 200).map(e => (
            <LogLine key={e.seq} entry={e} />
          ))}
        </Section>
      </ScrollView>
    </View>
  );
};

const RequestCard = ({
  row,
  canAgain,
  onCancel,
  onAgain,
  onNewBody,
}: {
  row: RequestRow;
  canAgain: boolean;
  onCancel: () => void;
  onAgain: () => void;
  onNewBody: () => void;
}) => {
  const live = progress.get(row.id);
  const sent = row.state === 'running' && live ? live.bytesSent : row.bytesSent;
  const total = row.totalBytes || live?.totalBytes || 0;
  const pct = total > 0 ? Math.min(100, (sent / total) * 100) : 0;
  const next = row.nextAttemptAt
    ? ` next in ${Math.max(
        0,
        Math.round((row.nextAttemptAt - Date.now()) / 1000),
      )}s`
    : '';
  return (
    <View style={styles.card}>
      <Text style={styles.mono} selectable>
        {row.id}
      </Text>
      <Text style={styles.mono}>
        {row.key} <Text style={stateStyle(row.state)}>{row.state}</Text>{' '}
        attempts={row.attempts}
        {next}
      </Text>
      <Text style={styles.mono}>
        {sent}/{total} B ({pct.toFixed(0)}%) updated {clock(row.updatedAt)}
      </Text>
      <View style={styles.bar}>
        <View style={[styles.barFill, {width: `${pct}%`}]} />
      </View>
      <Row>
        <Btn title="Cancel" onPress={onCancel} />
        {canAgain && <Btn title="Same id, same body" onPress={onAgain} />}
        {canAgain && row.vars !== null && (
          <Btn title="Same id, new body" onPress={onNewBody} />
        )}
      </Row>
    </View>
  );
};

const stateStyle = (state: RequestRow['state']) => {
  switch (state) {
    case 'completed':
      return styles.good;
    case 'error':
    case 'cancelled':
      return styles.bad;
    case 'awaiting-auth':
    case 'paused':
      return styles.waiting;
    default:
      return styles.busy;
  }
};

const LogLine = ({entry}: {entry: LogEntry}) => (
  <Text
    selectable
    style={[
      styles.mono,
      entry.kind === 'warn' && styles.bad,
      entry.kind === 'outcome' && styles.good,
    ]}>
    {clock(entry.at)} {entry.kind} {entry.text}
  </Text>
);

const Section = ({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) => (
  <View style={styles.section}>
    <Text style={styles.sectionTitle}>{title}</Text>
    {children}
  </View>
);

const Row = ({children}: {children: React.ReactNode}) => (
  <View style={styles.row}>{children}</View>
);

const Btn = ({
  title,
  onPress,
  disabled,
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
}) => (
  <Pressable
    accessibilityRole="button"
    onPress={onPress}
    disabled={disabled}
    style={({pressed}) => [
      styles.btn,
      pressed && styles.btnPressed,
      disabled && styles.btnDisabled,
    ]}>
    <Text style={styles.btnText}>{title}</Text>
  </Pressable>
);

const Chip = ({
  title,
  active,
  onPress,
}: {
  title: string;
  active: boolean;
  onPress: () => void;
}) => (
  <Pressable
    onPress={onPress}
    style={[styles.chip, active && styles.chipActive]}>
    <Text style={[styles.chipText, active && styles.chipTextActive]}>
      {title}
    </Text>
  </Pressable>
);

const Field = ({
  label,
  ...props
}: {label: string} & React.ComponentProps<typeof TextInput>) => (
  <View style={styles.field}>
    <Text style={styles.small}>{label}</Text>
    <TextInput
      autoCapitalize="none"
      autoCorrect={false}
      style={styles.input}
      {...props}
    />
  </View>
);

const Toggle = ({
  label,
  value,
  onValueChange,
}: {
  label: string;
  value: boolean;
  onValueChange: (v: boolean) => void;
}) => (
  <View style={styles.toggle}>
    <Text style={styles.label}>{label}</Text>
    <Switch value={value} onValueChange={onValueChange} />
  </View>
);

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#F3F3F3'},
  content: {
    padding: 12,
    paddingTop:
      Platform.OS === 'android' ? (StatusBar.currentHeight ?? 24) + 12 : 12,
    paddingBottom: 48,
  },
  title: {fontSize: 20, fontWeight: '700', color: '#000'},
  section: {
    marginTop: 12,
    padding: 10,
    borderRadius: 8,
    backgroundColor: '#FFF',
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 6,
    color: '#000',
  },
  row: {flexDirection: 'row', flexWrap: 'wrap', marginVertical: 2},
  wrap: {flexDirection: 'row', flexWrap: 'wrap', marginVertical: 4},
  btn: {
    backgroundColor: '#1F5FBF',
    borderRadius: 6,
    paddingVertical: 8,
    paddingHorizontal: 10,
    marginRight: 6,
    marginBottom: 6,
  },
  btnPressed: {opacity: 0.6},
  btnDisabled: {backgroundColor: '#999'},
  btnText: {color: '#FFF', fontSize: 13, fontWeight: '600'},
  chip: {
    borderWidth: 1,
    borderColor: '#1F5FBF',
    borderRadius: 12,
    paddingVertical: 4,
    paddingHorizontal: 8,
    marginRight: 6,
    marginBottom: 6,
  },
  chipActive: {backgroundColor: '#1F5FBF'},
  chipText: {color: '#1F5FBF', fontSize: 12},
  chipTextActive: {color: '#FFF'},
  field: {marginVertical: 4},
  input: {
    borderWidth: 1,
    borderColor: '#CCC',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 6,
    fontSize: 14,
    color: '#000',
  },
  toggle: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginVertical: 4,
  },
  label: {fontSize: 14, color: '#000', flexShrink: 1},
  small: {fontSize: 12, color: '#444'},
  mono: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 11,
    color: '#222',
  },
  card: {
    borderTopWidth: StyleSheet.hairlineWidth,
    borderColor: '#CCC',
    paddingVertical: 6,
  },
  bar: {height: 4, backgroundColor: '#E0E0E0', marginVertical: 4},
  barFill: {height: 4, backgroundColor: '#1F5FBF'},
  good: {color: '#1B7F3A'},
  bad: {color: '#B3261E'},
  waiting: {color: '#9A6700'},
  busy: {color: '#1F5FBF'},
});

export default App;
