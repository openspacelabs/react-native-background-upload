import { type CodegenTypes, type TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Codegen TurboModule spec (New Architecture). The typed public API lives in
// ./types and is applied at the JS edge in ./index; here the dynamic-shaped
// payloads (the options dict, journaled events, upload snapshots, and the
// terminal event payloads that carry header maps + optional fields) are declared
// as UnsafeObject because codegen can't model index signatures, Partial<>, or
// intersections. index.ts casts them back to the precise ./types shapes.
export interface Spec extends TurboModule {
  // One-time notification configuration. Android persists it, so a headless
  // WorkManager relaunch (no JS) can read it. It does nothing on iOS, because
  // iOS has no library notification.
  configure(options: CodegenTypes.UnsafeObject): void;
  startUpload(options: CodegenTypes.UnsafeObject): Promise<string>;
  // Chunked uploads get their own entry point for two reasons. Codegen cannot
  // model the raw/chunked discriminated union. And the native implementations
  // share no parsing: startUpload dispatches one request, while
  // startChunkedUpload creates or reconciles a durable part manifest. index.ts
  // keeps the single public startUpload and routes on options.type.
  startChunkedUpload(options: CodegenTypes.UnsafeObject): Promise<string>;
  cancelUpload(id: string): Promise<boolean>;
  // Releases the manifest and the bytes of a non-completed upload. A completed
  // upload releases itself when you acknowledge its terminal event.
  removeUpload(id: string): Promise<void>;
  getUnacknowledgedEvents(): Promise<CodegenTypes.UnsafeObject[]>;
  ackEvents(ids: string[]): Promise<boolean>;
  getAllUploads(): Promise<CodegenTypes.UnsafeObject[]>;

  // Events. progress fires on both platforms with a fixed shape; the terminal
  // events carry variant payloads (header maps, optional fields) so they're
  // UnsafeObject. notification is Android-only (tapping the progress
  // notification) and simply never fires on iOS.
  readonly onProgress: CodegenTypes.EventEmitter<{
    id: string;
    progress: number;
  }>;
  readonly onError: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onCancelled: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onCompleted: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onNotification: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNFileUploader');
