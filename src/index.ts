/**
 * Durable HTTP requests and file uploads from an iOS or Android device. The
 * consumer defines request kinds with define(), enqueues them with mutate(),
 * and receives every outcome through the definition's handlers.
 */
import type { EventSubscription } from 'react-native';
import NativeRNFileUploader from './NativeRNFileUploader';
import { chunkPlan } from './chunkPlan';
import { createDelivery } from './delivery';
import {
  createRegistry,
  DEFAULT_LIFETIME_MS,
  type AnyDefinition,
  type Settings,
} from './registry';
import type {
  AddListener,
  ConfigureOptions,
  RequestRow,
  StateEvent,
  UploadClient,
} from './types';

export * from './types';
export * from './chunkPlan';

/**
 * Builds one client over the native queue. Each client has its own
 * definitions and settings. An app needs one; the default export is one.
 */
export const createUploadClient = (): UploadClient => {
  const native = NativeRNFileUploader;
  const settings: Settings = { lifetimeMs: DEFAULT_LIFETIME_MS };
  const definitions = new Map<string, AnyDefinition>();
  // One entry per subscription, not per function, so the same listener
  // registered twice is removed one subscription at a time.
  const stateListeners = new Set<{ listener: (event: StateEvent) => void }>();

  const delivery = createDelivery({
    native,
    lookup: (key) => definitions.get(key),
    emitState: (event) =>
      stateListeners.forEach(({ listener }) => listener(event)),
  });
  const { define } = createRegistry({
    native,
    definitions,
    getSettings: () => settings,
    trackMutate: delivery.trackMutate,
  });

  /**
   * One-time setup. Call it at boot, after every define() call. Stores the
   * lifetime, the headers provider and the retry defaults, forwards the
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

  /** Pauses the whole queue. No outcome is produced; live rows show 'paused'. */
  const pause = (): Promise<void> => native.pause();

  /** Resumes a paused queue. */
  const resume = (): Promise<void> => native.resume();

  /**
   * On a live entry: settles it 'cancelled' with reason 'user', then forgets
   * it after the ack. On a settled entry: forgets it now, row and bytes.
   */
  const cancel = (id: string): Promise<void> => native.cancel(id);

  /** Persisted natively. Applies to queued and future entries. */
  const setWifiOnly = (enabled: boolean): Promise<void> =>
    native.setWifiOnly(enabled);

  /**
   * Merges the patch into the headers of every queued and parked entry, then
   * resumes the entries parked on 'awaiting-auth'. This is how a fresh token
   * reaches requests that stalled on 401.
   */
  const updateHeaders = (patch: Record<string, string>): Promise<void> =>
    native.updateHeaders(patch);

  /**
   * The live rows of the queue, read synchronously from native's in-memory
   * index. Works offline. Completed entries leave after their ack.
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
   * one HTTP attempt before interpretation.
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
