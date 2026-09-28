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
  MAX_VARS_BYTES,
  type AnyDefinition,
  type Settings,
} from './registry';
import type {
  AddListener,
  ConfigureOptions,
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

  // A listener that throws must not stop the others or fail the delivery
  // that produced the row.
  const emitState = (event: StateEvent): void => {
    stateListeners.forEach(({ listener }) => {
      try {
        listener(event);
      } catch (e) {
        console.warn('addListener: a state listener threw', e);
      }
    });
  };

  const delivery = createDelivery({
    native,
    lookup: (key) => definitions.get(key),
    emitState,
  });
  const { define } = createRegistry({
    native,
    definitions,
    getSettings: () => settings,
    trackMutate: delivery.trackMutate,
  });

  /**
   * One-time setup. Call it at boot, after every define() call. Stores the
   * lifetime, the vars cap, the headers provider and the retry defaults, forwards the
   * lifetime, retry and Android notification settings to native, then starts
   * replaying journaled outcomes. A second call updates the settings and does
   * not replay again. Each call replaces the full configuration.
   */
  const configure = (options: ConfigureOptions): void => {
    const lifetimeMs = options.lifetimeMs ?? DEFAULT_LIFETIME_MS;
    if (!Number.isFinite(lifetimeMs) || lifetimeMs <= 0) {
      throw new Error(
        `configure: lifetimeMs must be a positive number, got ${options.lifetimeMs}`,
      );
    }
    settings.lifetimeMs = lifetimeMs;
    const enqueueTimeoutMs =
      options.enqueueTimeoutMs ?? DEFAULT_ENQUEUE_TIMEOUT_MS;
    if (!Number.isFinite(enqueueTimeoutMs) || enqueueTimeoutMs <= 0) {
      throw new Error(
        `configure: enqueueTimeoutMs must be a positive number, got ${options.enqueueTimeoutMs}`,
      );
    }
    settings.enqueueTimeoutMs = enqueueTimeoutMs;
    const maxVarsBytes = options.maxVarsBytes ?? MAX_VARS_BYTES;
    if (!Number.isFinite(maxVarsBytes) || maxVarsBytes <= 0) {
      throw new Error(
        `configure: maxVarsBytes must be a positive number, got ${options.maxVarsBytes}`,
      );
    }
    settings.maxVarsBytes = maxVarsBytes;
    settings.headers = options.headers;
    settings.retry = options.retry;
    const forwarded: Record<string, unknown> = {
      lifetimeMs,
      ...options.android,
    };
    if (options.retry !== undefined) {
      forwarded.retry = options.retry;
    }
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
   * unacknowledged outcomes.
   */
  const cancel = (id: string): Promise<void> => native.cancel(id);

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
    addListener,
    chunkPlan,
    android,
  };
};

export default createUploadClient();
