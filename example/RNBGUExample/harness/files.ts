import * as RNFS from 'react-native-fs';

// Test files for the upload buttons. Each 1 MiB block starts with its own
// index, so parts that arrive in the wrong place change the MD5 that the
// test server prints.

export const MB = 1024 * 1024;
export const TEST_DIR = `${RNFS.DocumentDirectoryPath}/rnbgu-test`;

// A 1x1 JPEG, small enough to inline.
const JPEG_BASE64 =
  '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////' +
  '////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBAB' +
  'AAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=';

let filler: string | undefined;

// 1 MiB of ASCII from a fixed pseudo-random sequence.
const getFiller = (): string => {
  if (!filler) {
    const alphabet =
      'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    const chars: string[] = [];
    let x = 12345;
    for (let i = 0; i < 65536; i++) {
      // A 32-bit LCG. The top 6 bits pick one of 64 characters.
      /* eslint-disable no-bitwise */
      x = (Math.imul(x, 1103515245) + 12345) >>> 0;
      chars.push(alphabet[x >>> 26]);
      /* eslint-enable no-bitwise */
    }
    filler = chars.join('').repeat(16);
  }
  return filler;
};

const block = (index: number): string => {
  const head = `block ${index} `.padEnd(32, '.');
  return head + getFiller().slice(head.length);
};

const ensureDir = async () => {
  if (!(await RNFS.exists(TEST_DIR))) {
    await RNFS.mkdir(TEST_DIR);
  }
};

export type TestFile = {path: string; size: number; md5: string};

/** Writes a new file of `mb` MiB and returns its path, size, and MD5. */
export const makeFile = async (name: string, mb: number): Promise<TestFile> => {
  await ensureDir();
  const path = `${TEST_DIR}/${name}`;
  await RNFS.writeFile(path, block(0), 'utf8');
  for (let i = 1; i < mb; i++) {
    await RNFS.appendFile(path, block(i), 'utf8');
  }
  const {size} = await RNFS.stat(path);
  return {path, size: Number(size), md5: await RNFS.hash(path, 'md5')};
};

/** Writes a new 1x1 JPEG and returns its path. */
export const makePhoto = async (name: string): Promise<TestFile> => {
  await ensureDir();
  const path = `${TEST_DIR}/${name}`;
  await RNFS.writeFile(path, JPEG_BASE64, 'base64');
  const {size} = await RNFS.stat(path);
  return {path, size: Number(size), md5: await RNFS.hash(path, 'md5')};
};

/** A path that does not exist, for the E_FILE_MISSING check. */
export const missingPath = (): string => `${TEST_DIR}/does-not-exist.bin`;

export const exists = (path: string): Promise<boolean> => RNFS.exists(path);

export const remove = async (path: string): Promise<void> => {
  if (await RNFS.exists(path)) {
    await RNFS.unlink(path);
  }
};
