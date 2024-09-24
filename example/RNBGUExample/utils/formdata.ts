import pathUtils from 'path';
import RNFS from 'react-native-fs';

const random = () => Math.random().toString().substring(2);
export const createFormDataFile = async (
  name: string,
  parts: readonly FormDataPart[],
) => {
  const dir = RNFS.TemporaryDirectoryPath;
  const path = pathUtils.join(dir, name);

  const boundary = random() + '_' + Date.now();

  await RNFS.writeFile(path, '');

  // write each part into the form-data file
  let content = `--${boundary}`;

  for (const part of parts) {
    let fileName = '';

    if ('path' in part) {
      fileName = `; filename="${pathUtils.basename(part.path)}"`;
    }

    const header =
      `\r\nContent-Disposition: form-data; name="${part.name}"${fileName}` +
      `\r\nContent-Type: ${part.contentType}\r\n\r\n`;

    content += header;

    if ('path' in part) {
      await RNFS.appendFile(path, content, 'utf8');
      const data = await RNFS.readFile(part.path, 'base64');
      await RNFS.appendFile(path, data, 'base64');
      content = '';
    } else {
      content += part.string;
    }

    content += `\r\n--${boundary}`;
  }

  content += '--';

  await RNFS.appendFile(path, content, 'utf8');

  const {size} = await RNFS.stat(path);

  const contentType = `multipart/form-data; boundary=${boundary}`;
  return {path, size, contentType};
};

export type FormDataPart =
  | {
      contentType: string;
      name: string;
      string: string;
    }
  | {
      contentType: string;
      name: string;
      path: string;
    };
