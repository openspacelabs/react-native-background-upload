import * as RNFS from 'react-native-fs';

// Settings that must survive a kill and relaunch. They are stored in a small
// JSON file, so the example needs no storage library. `throwInHandlers` must
// be loaded before configure(), because the boot replay runs the handlers.

export type HarnessSettings = {
  /** The test server, for example http://localhost:3000. */
  host: string;
  /** Handlers throw after they log, so outcomes stay unacknowledged. */
  throwInHandlers: boolean;
  /**
   * The last value this app passed to setWifiOnly(). Native keeps the real
   * value and has no getter, so this is only a mirror.
   */
  wifiOnly: boolean;
  /** Send `android: { noNotification: true }` on new requests. */
  silent: boolean;
};

const FILE = `${RNFS.DocumentDirectoryPath}/rnbgu-harness.json`;

export const settings: HarnessSettings = {
  host: 'http://localhost:3000',
  throwInHandlers: false,
  wifiOnly: false,
  silent: false,
};

export const loadSettings = async (): Promise<void> => {
  try {
    if (await RNFS.exists(FILE)) {
      Object.assign(settings, JSON.parse(await RNFS.readFile(FILE, 'utf8')));
    }
  } catch (e) {
    console.warn('[rnbgu] could not read the harness settings', e);
  }
};

export const saveSettings = (patch: Partial<HarnessSettings>): void => {
  Object.assign(settings, patch);
  RNFS.writeFile(FILE, JSON.stringify(settings), 'utf8').catch(e =>
    console.warn('[rnbgu] could not save the harness settings', e),
  );
};
