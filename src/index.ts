/**
 * Durable HTTP requests and file uploads from an iOS or Android device. The
 * consumer defines request kinds with define(), enqueues them with mutate(),
 * and receives every outcome through the definition's handlers.
 */
import type { EventSubscription } from 'react-native';
import NativeRNFileUploader from './NativeRNFileUploader';
import type { Spec } from './NativeRNFileUploader';
import { chunkPlan } from './chunkPlan';
import { createDelivery } from './delivery';
import {
  createRegistry,
  DEFAULT_ENQUEUE_TIMEOUT_MS,
  DEFAULT_LIFETIME_MS,
  isDev,
  MAX_VARS_BYTES,
  type AnyDefinition,
  type Settings,
  validateRetryPolicy,
} from './registry';
import type {
  AddListener,
  ConfigureOptions,
  Diagnostic,
  PauseScope,
  RequestRow,
  StateEvent,
  UploadClient,
} from './types';

export * from './types';
export * from './chunkPlan';

/**
 * The native module contract that a client talks to. Tests pass a fake one
 * to createUploadClient(); see createFakeNative() in ./testing.
 */
export type UploadNative = Spec;

export type UploadClientOptions = {
  /** Default: the real TurboModule. A test passes a fake. */
  native?: UploadNative;
};

/**
 * The scope as it crosses to native. Codegen has no optional arguments, so
 * the whole queue is `{}`. A misspelled field throws, because dropping it
 * would pause the whole queue. Throws inside the async caller, so the
 * promise rejects.
 */
const toNativeScope = (
  method: 'pause' | 'resume',
  scope: PauseScope | undefined,
): PauseScope => {
  if (scope === undefined) {
    return {};
  }
  if (typeof scope !== 'object' || scope === null || Array.isArray(scope)) {
    throw new Error(`${method}: scope must be an object when given`);
  }
  Object.keys(scope).forEach((field) => {
    if (field !== 'keys') {
      throw new Error(`${method}: unknown scope field "${field}"`);
    }
  });
  if (!('keys' in scope)) {
    return {};
  }
  // A present but undefined list is refused too: it is more often a
  // computed list gone missing than a request for the whole queue.
  const { keys } = scope;
  if (
    !Array.isArray(keys) ||
    !keys.every((key) => typeof key === 'string' && key.length > 0)
  ) {
    throw new Error(`${method}: keys must be an array of non-empty strings`);
  }
  return { keys: [...keys] };
};

/**
 * Builds one client over the native queue. Each client has its own
 * definitions and settings. An app needs one; the default export is one.
 */
export const createUploadClient = ({
  native = NativeRNFileUploader,
}: UploadClientOptions = {}): UploadClient => {
  const settings: Settings = {
    lifetimeMs: DEFAULT_LIFETIME_MS,
    enqueueTimeoutMs: DEFAULT_ENQUEUE_TIMEOUT_MS,
    maxVarsBytes: MAX_VARS_BYTES,
  };
  const definitions = new Map<string, AnyDefinition>();
  // One entry per subscription, not per function, so the same listener
  // registered twice is removed one subscription at a time.
  const stateListeners = new Set<{ listener: (event: StateEvent) => void }>();

  // Every warning the JS layer raises goes through here. The registry and
  // delivery capture this function when the client is built, before
  // configure(), so it reads the sink at each call: a sink set later is used
  // from then on. Native warnings go to takeDiagnostics() instead.
  const warn = (message: string, detail?: unknown): void => {
    const sink = settings.onWarning;
    if (sink) {
      try {
        sink(message, detail);
        return;
      } catch (e) {
        console.warn('configure: onWarning threw', e);
      }
    }
    if (detail === undefined) {
      console.warn(message);
    } else {
      console.warn(message, detail);
    }
  };

  // A listener that throws must not stop the others or fail the delivery
  // that produced the row.
  const emitState = (event: StateEvent): void => {
    stateListeners.forEach(({ listener }) => {
      try {
        listener(event);
      } catch (e) {
        warn('addListener: a state listener threw', e);
      }
    });
  };

  const delivery = createDelivery({
    native,
    lookup: (key) => definitions.get(key),
    emitState,
    warn,
  });
  // The keys of the last configure({ definitions }), and the unlisted keys
  // already warned about.
  let listedKeys: Set<string> | undefined;
  const warnedKeys = new Set<string>();

  const onDefine = (key: string): void => {
    if (
      listedKeys !== undefined &&
      !listedKeys.has(key) &&
      !warnedKeys.has(key) &&
      isDev()
    ) {
      warnedKeys.add(key);
      warn(
        `define: "${key}" is not in configure({ definitions }). It still receives its outcomes, but list it so that its module loads before configure().`,
      );
    }
    delivery.release(key);
  };

  const { define } = createRegistry({
    native,
    definitions,
    getSettings: () => settings,
    trackMutate: delivery.trackMutate,
    onDefine,
    warn,
  });

  /**
   * One-time setup. Call it at boot, after every define() call. Stores the
   * lifetime, the vars cap, the headers provider, the warning sink and the
   * retry defaults, forwards the
   * lifetime, retry and Android notification settings to native, then starts
   * replaying journaled outcomes. A second call updates the settings and does
   * not replay again. Each call replaces the full configuration.
   *
   * `definitions` is optional. Each listed handle came from define(), so its
   * key is registered already. The list only pins the keys, so that a later
   * define() of another key warns in development.
   */
  const configure = (options: ConfigureOptions): void => {
    // Every check runs before any setting changes, so a rejected call leaves
    // the previous configuration whole.
    if (options.retry !== undefined) {
      validateRetryPolicy(options.retry, 'configure');
    }
    const listed = options.definitions;
    if (
      listed !== undefined &&
      (!Array.isArray(listed) ||
        !listed.every((handle) => typeof handle?.key === 'string'))
    ) {
      throw new Error(
        'configure: definitions must be an array of the handles that define() returned',
      );
    }
    const lifetimeMs = options.lifetimeMs ?? DEFAULT_LIFETIME_MS;
    if (!Number.isFinite(lifetimeMs) || lifetimeMs <= 0) {
      throw new Error(
        `configure: lifetimeMs must be a positive number, got ${options.lifetimeMs}`,
      );
    }
    const enqueueTimeoutMs =
      options.enqueueTimeoutMs ?? DEFAULT_ENQUEUE_TIMEOUT_MS;
    if (!Number.isFinite(enqueueTimeoutMs) || enqueueTimeoutMs <= 0) {
      throw new Error(
        `configure: enqueueTimeoutMs must be a positive number, got ${options.enqueueTimeoutMs}`,
      );
    }
    const maxVarsBytes = options.maxVarsBytes ?? MAX_VARS_BYTES;
    if (!Number.isFinite(maxVarsBytes) || maxVarsBytes <= 0) {
      throw new Error(
        `configure: maxVarsBytes must be a positive number, got ${options.maxVarsBytes}`,
      );
    }
    if (
      options.onWarning !== undefined &&
      typeof options.onWarning !== 'function'
    ) {
      throw new Error(
        `configure: onWarning must be a function when given, got ${typeof options.onWarning}`,
      );
    }
    settings.lifetimeMs = lifetimeMs;
    settings.enqueueTimeoutMs = enqueueTimeoutMs;
    settings.maxVarsBytes = maxVarsBytes;
    settings.headers = options.headers;
    settings.retry = options.retry;
    settings.onWarning = options.onWarning;
    const forwarded: Record<string, unknown> = {
      lifetimeMs,
      ...options.android,
    };
    if (options.retry !== undefined) {
      forwarded.retry = options.retry;
    }
    listedKeys =
      listed === undefined
        ? undefined
        : new Set(listed.map((handle) => handle.key));
    native.configure(forwarded);
    delivery.start();
  };

  /**
   * No scope pauses the whole queue. `{ keys }` pauses the entries of those
   * keys, queued and future. No outcome is produced; live rows show
   * 'paused'. A paused entry past its expiresAt settles error/expired when
   * it resumes.
   */
  const pause = async (scope?: PauseScope): Promise<void> =>
    native.pause(toNativeScope('pause', scope));

  /**
   * Undoes the pause of the same scope. An entry stays paused while another
   * scope still pauses it: the whole-queue pause, or its key.
   */
  const resume = async (scope?: PauseScope): Promise<void> =>
    native.resume(toNativeScope('resume', scope));

  /**
   * On a live entry: settles it 'cancelled' with reason 'user', then forgets
   * it after the ack. On a settled entry: forgets it now: row, bytes, and its
   * unacknowledged outcomes. On an id with no row: deletes the id's directory
   * when it has no entry file: a v9 upload's bytes, or a chunked file kept
   * after an enqueue that rejected E_STORAGE.
   */
  const cancel = async (id: string): Promise<void> => {
    // On a settled entry native also drops the unacknowledged outcomes, so
    // the held ones must not reach a handler later. On a live entry native
    // keeps an earlier generation's outcomes; dropping them here only defers
    // them to the next launch, as before late definitions existed.
    // Forget before the await, so a define() of the key that runs while
    // native cancels does not run a handler for this id. When native cancel
    // rejects, the dropped outcomes were never acknowledged, so native
    // redelivers them at the next launch.
    delivery.forget(id);
    await native.cancel(id);
    // Forget again: an outcome of the id can be held during the await.
    delivery.forget(id);
  };

  /**
   * Persisted natively. Applies to queued and future entries whose
   * descriptor does not set `wifiOnly`.
   */
  const setWifiOnly = (enabled: boolean): Promise<void> =>
    native.setWifiOnly(enabled);

  /**
   * Merges the patch into the headers of every entry not yet forgotten and
   * resumes the entries parked on 'awaiting-auth'. The patch also replaces
   * same-named headers a part carries. This is how a fresh token
   * reaches requests that stalled on 401. Native bumps a header generation, so
   * a 401 from an attempt issued under the old headers re-issues at once
   * instead of parking.
   */
  const updateHeaders = (patch: Record<string, string>): Promise<void> =>
    native.updateHeaders(patch);

  /**
   * Every entry native has not yet forgotten, read synchronously from its
   * in-memory index. Works offline. Completed and cancelled entries leave
   * after their ack; an error entry stays until cancel() or a same-id
   * mutate().
   */
  const getRequests = (filter?: {
    key?: string;
    id?: string;
  }): RequestRow[] => {
    const rows = native.getRequests() as RequestRow[];
    if (!filter) {
      return rows;
    }
    return rows.filter(
      (row) =>
        (filter.key === undefined || row.key === filter.key) &&
        (filter.id === undefined || row.id === filter.id),
    );
  };

  /**
   * The library's persisted diagnostic records, oldest first. Each call
   * returns the records since the last one, so report them where they can
   * be seen, such as an error tracker. A boot record (message 'boot
   * sweep') has `v9Leftovers` and `orphans`, zeros included; `orphans`
   * also says what the start-up cleanup deleted. Android adds
   * it at each module start and keeps the newest 50 records: native
   * warnings and errors, including ones from background runs with no JS,
   * and that record, which also describes the queue and its WorkManager
   * runs. iOS adds it once per process and keeps its records in memory.
   */
  const takeDiagnostics = async (): Promise<Diagnostic[]> =>
    (await native.takeDiagnostics()) as Diagnostic[];

  /**
   * Listens for one event type across all requests. Listeners are global; use
   * the event's id to tell requests apart. 'state' carries a full RequestRow
   * per transition, plus a row with reason 'unhandled-key' for an outcome
   * whose key has no definition. 'progress' is byte-weighted. 'attempt' is
   * one HTTP attempt, emitted live before the library settles the entry.
   */
  const addListener = ((
    event: 'state' | 'progress' | 'attempt',
    // The public AddListener overloads carry the precise payload per event.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    listener: (data: any) => void,
  ): EventSubscription => {
    switch (event) {
      case 'state': {
        const entry = { listener };
        stateListeners.add(entry);
        // One subscription covers the native rows and the JS-synthesized
        // unhandled-key rows, so remove() has to drop both.
        const subscription = native.onState(listener);
        const removeNative = subscription.remove.bind(subscription);
        subscription.remove = () => {
          stateListeners.delete(entry);
          removeNative();
        };
        return subscription;
      }
      case 'progress':
        return native.onProgress(listener);
      case 'attempt':
        return native.onAttempt(listener);
      default:
        throw new Error(`addListener: unknown event ${String(event)}`);
    }
  }) as AddListener;

  const android = {
    /**
     * Fires when the Android upload notification is pressed. It never fires
     * on iOS.
     */
    addNotificationListener: (listener: () => void): EventSubscription =>
      native.onNotification(() => listener()),
  };

  return {
    configure,
    define,
    pause,
    resume,
    cancel,
    setWifiOnly,
    updateHeaders,
    getRequests,
    takeDiagnostics,
    addListener,
    chunkPlan,
    android,
  };
};

export default createUploadClient();
