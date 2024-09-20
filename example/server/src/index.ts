import express from 'express';
import * as fs from 'fs';
import path from 'path';
import { inspect } from 'util';
const md5File = require('md5-file');
import formidable from 'formidable';
import { PassThrough } from 'stream';

const router = express.Router();

router.get('/', (_, res) => {
  res.send('Hello World!');
});

router.post('/multipart-upload', async (req, res) => {
  // Create two PassThrough streams
  const rawBodyStream = new PassThrough();
  const formStream = new PassThrough();
  req.pipe(rawBodyStream);
  req.pipe(formStream);

  let body = '';

  // Collect the data in chunks
  rawBodyStream.on('data', (chunk) => {
    body += chunk.toString();
  });

  // When the entire body has been received, log it
  rawBodyStream.on('end', () => {
    console.log('Request Body with \\r and \\n visualized:');
    console.log(body.replace(/\r/g, '\\r').replace(/\n/g, '\\n'));
  });

  try {
    // @ts-expect-error make compatible with form.parse
    formStream.headers = req.headers;
    // @ts-expect-error
    const [fields, files] = await formidable({}).parse(formStream);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    const parsed = JSON.stringify({ fields, files }, null, 2);
    console.log('---headers', req.headers);
    console.log('---parsed', parsed);
    res.end(parsed, 'utf8');
  } catch (err) {
    console.error(err);
    let httpCode =
      err instanceof formidable.errors.FormidableError && err.httpCode;
    httpCode ||= 500;
    res.writeHead(httpCode, { 'Content-Type': 'text/plain' });
    res.end(String(err));
  }
});

router.post('/upload', (req, res) => {
  const filePath = path.join(__dirname, '/uploaded-file.txt');
  const stream = fs.createWriteStream(filePath);

  console.log(inspect(req.headers));
  console.log(filePath);

  if (req.query.simulateFailImmediately) {
    res.status(500).send('Simulated Error').end();
    return;
  }

  return new Promise<void>((resolve, reject) => {
    stream.on('open', () => {
      console.log('Stream open ...  0.00%');
      req.pipe(stream);
    });

    // Drain is fired whenever a data chunk is written.
    stream.on('drain', () => {
      // TODO simulate error while uploading
      const written = stream.bytesWritten;
      const total = parseInt(req.headers['content-length']!, 10);
      const progress = (written / total) * 100;

      console.log(`Processing  ...  ${progress.toFixed(2)}%`);
      if (req.query.simulateFailMidway && progress > 50) {
        res.status(500).send('Simulated Error').end();
        resolve();
      }
    });

    stream.on('close', () => {
      console.log('Processing  ...  100%');
      fs.promises.stat(filePath).then((r) => console.log(inspect(r)));
      md5File(filePath).then((r: string) => console.log('MD5:', r));
      res.send(filePath);
      resolve();
    });

    stream.on('error', (err) => {
      console.error(err);
      reject(err);
    });
  });
});

const app = express();
app.use(router);

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`Running at http://localhost:${port}`));
