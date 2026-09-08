import { type CodegenTypes, type TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Codegen TurboModule spec (New Architecture). The typed public API lives in
// ./types and is applied at the JS edge in ./index, ./registry and ./delivery.
// The dynamic-shaped payloads (the entry, queue rows, settled events, attempt
// events) are declared as UnsafeObject because codegen can't model index
// signatures, Partial<>, or unions. The JS layer casts them back to the
// precise ./types shapes.
export interface Spec extends TurboModule {
  // Queue-wide settings: { lifetimeMs, retry, ...androidNotificationConfig }.
  // Android persists the notification config, so a headless WorkManager
  // relaunch (no JS) can read it. Each call replaces the full configuration.
  configure(options: CodegenTypes.UnsafeObject): void;
  // Persists { id, key, vars, descriptor } and schedules it. Resolves with the
  // entry's id once the write has landed, never on the network. A same-id call
  // follows the v9 resume rules: same body resumes, different parts on a
  // settled entry recreate, different parts on a running entry reject.
  enqueue(entry: CodegenTypes.UnsafeObject): Promise<string>;
  // Whole-queue pause. No outcome is produced; live rows move to 'paused'.
  pause(): Promise<void>;
  resume(): Promise<void>;
  // A live entry settles 'cancelled' (user) and is forgotten after its ack. A
  // settled entry is forgotten now, row and bytes.
  cancel(id: string): Promise<void>;
  // Persisted natively. Applies to queued and future entries.
  setWifiOnly(enabled: boolean): Promise<void>;
  // Merges the patch into every queued and parked entry's headers, then
  // resumes the entries parked on 'awaiting-auth'.
  updateHeaders(patch: CodegenTypes.UnsafeObject): Promise<void>;
  // Synchronous. Serialized from the in-memory index that native keeps
  // current on every state change, never from disk. Live entries only.
  getRequests(): CodegenTypes.UnsafeObject[];
  // Settled outcomes that JS has not acknowledged, in the onSettled shape,
  // including ones journaled while JS was dead. Internal: ./delivery drains
  // them after configure().
  getUnacknowledgedEvents(): Promise<CodegenTypes.UnsafeObject[]>;
  // Removes journaled outcomes by eventId. An acknowledged 'completed' is the
  // one moment native deletes the entry's row and bytes.
  ackEvents(ids: string[]): Promise<boolean>;

  // Events. state carries a full RequestRow per transition. progress is
  // byte-weighted across a chunked upload's parts. attempt is one HTTP attempt
  // before interpretation. settled is the journaled terminal outcome, emitted
  // after the journal write; ./delivery routes it to the definition's
  // handlers. notification is Android-only (tapping the progress notification)
  // and never fires on iOS.
  readonly onState: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onProgress: CodegenTypes.EventEmitter<{
    id: string;
    bytesSent: number;
    totalBytes: number;
  }>;
  readonly onAttempt: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onSettled: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onNotification: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNFileUploader');
