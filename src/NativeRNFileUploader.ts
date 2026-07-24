import { type CodegenTypes, type TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Codegen TurboModule spec (New Architecture). The typed public API lives in
// ./types and is applied at the JS edge in ./index; here the dynamic-shaped
// payloads (the options dict, journaled events, upload snapshots, and the
// terminal event payloads that carry header maps + optional fields) are declared
// as UnsafeObject because codegen can't model index signatures, Partial<>, or
// intersections. index.ts casts them back to the precise ./types shapes.
export interface Spec extends TurboModule {
  startUpload(options: CodegenTypes.UnsafeObject): Promise<string>;
  cancelUpload(id: string): Promise<boolean>;
  // iOS returns { state, bytesSent, totalBytes }; Android returns null.
  getUploadStatus(id: string): Promise<CodegenTypes.UnsafeObject | null>;
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
