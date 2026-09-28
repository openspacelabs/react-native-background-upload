// Local test receiver for the example app. Every request prints one line.
//
// The first path segment picks the behavior (the "mode"). The rest of the
// path is free, so each test run can use its own path. Counters are per
// full path, so a fresh path starts a fresh count.
//
//   /ok/...         200
//   /flaky/...      503 for the first 2 hits of the path, then 200
//   /auth/...       401 unless Authorization is AUTH_TOKEN
//   /auth-once/...  401 for the first hit of the path, then 200
//   /auth-slow/...  waits SLOW_AUTH_MS, then answers like /auth
//   /bad/...        400
//   /gone/...       404
//   /conflict/...   409 with "Upload already completed" in the body
//   /down/...       503, always
//   /slow/...       waits SLOW_MS, then 200
//   /big/...        200 with a body over 1 MB
//
// A request with a Content-Range header ("a-b/size" or "bytes a-b/size") is
// a chunked part. The server writes it at its offset into one file per
// upload, where the upload is the path without its last segment. When every
// byte of the file is present, it prints the MD5 of the whole file. The app
// prints the MD5 of the source file, so the two can be compared.
//
// GET /stats shows the open-request counters. POST /stats/reset clears them.

import crypto from 'crypto';
import express from 'express';
import { formidable } from 'formidable';
import * as fs from 'fs';
import os from 'os';
import path from 'path';
import { Readable } from 'stream';

const PORT = Number(process.env.PORT || 3000);
const AUTH_TOKEN = process.env.AUTH_TOKEN || 'Bearer good';
const SLOW_MS = Number(process.env.SLOW_MS || 30_000);
const SLOW_AUTH_MS = Number(process.env.SLOW_AUTH_MS || 20_000);
const RECEIVED_DIR = path.join(__dirname, '..', 'received');

fs.mkdirSync(RECEIVED_DIR, { recursive: true });

const hits = new Map<string, number>();
let open = 0;
let maxOpen = 0;

type Upload = {
  file: string;
  size: number;
  // Accepted part ranges, keyed by the part's path.
  parts: Map<string, { start: number; end: number }>;
  assembled: boolean;
};
const uploads = new Map<string, Upload>();

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const md5 = (data: Buffer) => crypto.createHash('md5').update(data).digest('hex');
const time = () => new Date().toISOString().slice(11, 23);

const readBody = (req: express.Request): Promise<Buffer> =>
  new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });

const parseRange = (header: string | undefined) => {
  const m = header?.match(/^(?:bytes )?(\d+)-(\d+)\/(\d+)$/);
  if (!m) return undefined;
  return { start: Number(m[1]), end: Number(m[2]) + 1, size: Number(m[3]) };
};

// Summarizes a multipart body: field names, file names, types, sizes, MD5.
const describeMultipart = async (req: express.Request, body: Buffer) => {
  const stream = Readable.from(body) as Readable & { headers?: unknown };
  stream.headers = req.headers;
  // @ts-expect-error formidable parses any stream that carries headers.
  const [fields, files] = await formidable({}).parse(stream);
  const out: string[] = [];
  for (const [name, values] of Object.entries(fields)) {
    out.push(`field ${name}=${JSON.stringify(values)}`);
  }
  for (const [name, list] of Object.entries(files)) {
    for (const f of list ?? []) {
      const data = fs.readFileSync(f.filepath);
      // formidable reports a typed part with no filename as a file. Print
      // a small one in full, because it is a string part such as JSON.
      const value =
        !f.originalFilename && data.length <= 500 ? ` value=${data.toString('utf8')}` : '';
      out.push(
        `file ${name} filename=${f.originalFilename} type=${f.mimetype} ` +
          `bytes=${data.length} md5=${md5(data)}${value}`,
      );
      fs.unlinkSync(f.filepath);
    }
  }
  return out;
};

// Stores an accepted part. Prints DUPLICATE when the same part path was
// already accepted, and ASSEMBLED with the MD5 once every byte is present.
const storePart = (
  partPath: string,
  range: { start: number; end: number; size: number },
  body: Buffer,
) => {
  const key = partPath.split('/').slice(0, -1).join('/');
  let upload = uploads.get(key);
  if (!upload || upload.size !== range.size) {
    const file = path.join(RECEIVED_DIR, key.replace(/\W+/g, '_') + '.bin');
    fs.writeFileSync(file, Buffer.alloc(0));
    upload = { file, size: range.size, parts: new Map(), assembled: false };
    uploads.set(key, upload);
  }
  if (upload.parts.has(partPath)) {
    console.log(`  DUPLICATE part ${partPath}`);
  }
  if (body.length !== range.end - range.start) {
    console.log(
      `  RANGE MISMATCH ${partPath}: header says ${range.end - range.start} B, body has ${body.length} B`,
    );
  }
  const fd = fs.openSync(upload.file, 'r+');
  fs.writeSync(fd, body, 0, body.length, range.start);
  fs.closeSync(fd);
  upload.parts.set(partPath, { start: range.start, end: range.end });

  const covered = [...upload.parts.values()]
    .sort((a, b) => a.start - b.start)
    .reduce((end, r) => (r.start <= end ? Math.max(end, r.end) : end), 0);
  if (covered >= upload.size && !upload.assembled) {
    upload.assembled = true;
    const data = fs.readFileSync(upload.file);
    console.log(
      `  ASSEMBLED ${key} parts=${upload.parts.size} bytes=${data.length} md5=${md5(data)}`,
    );
  }
};

const statusFor = async (
  mode: string,
  hit: number,
  auth: string | undefined,
): Promise<number> => {
  switch (mode) {
    case 'ok':
      return 200;
    case 'flaky':
      return hit <= 2 ? 503 : 200;
    case 'auth':
      return auth === AUTH_TOKEN ? 200 : 401;
    case 'auth-once':
      return hit <= 1 ? 401 : 200;
    case 'auth-slow':
      await sleep(SLOW_AUTH_MS);
      return auth === AUTH_TOKEN ? 200 : 401;
    case 'bad':
      return 400;
    case 'gone':
      return 404;
    case 'conflict':
      return 409;
    case 'down':
      return 503;
    case 'slow':
      await sleep(SLOW_MS);
      return 200;
    case 'big':
      return 200;
    default:
      return 404;
  }
};

const app = express();

app.use((_req, res, next) => {
  open += 1;
  maxOpen = Math.max(maxOpen, open);
  res.on('close', () => {
    open -= 1;
  });
  next();
});

app.get('/', (_req, res) => {
  res.send('RNBGU test server');
});

app.get('/stats', (_req, res) => {
  res.json({ open, maxOpen });
});

app.post('/stats/reset', (_req, res) => {
  maxOpen = open;
  hits.clear();
  res.json({ open, maxOpen });
});

app.all('*', async (req, res) => {
  const body = await readBody(req);
  const reqPath = req.path;
  const mode = reqPath.split('/')[1] ?? '';
  const hit = (hits.get(reqPath) ?? 0) + 1;
  hits.set(reqPath, hit);
  const auth = req.header('authorization');
  const range = parseRange(req.header('content-range'));

  console.log(
    `${time()} ${req.method} ${reqPath} ${body.length} B md5=${md5(body)} ` +
      `rid=${req.header('x-request-id')} auth=${auth} ` +
      `type=${req.header('content-type')} range=${req.header('content-range')} ` +
      `hit=${hit} open=${open} maxOpen=${maxOpen}`,
  );

  if (req.header('content-type')?.startsWith('multipart/form-data')) {
    try {
      for (const line of await describeMultipart(req, body)) {
        console.log(`  ${line}`);
      }
    } catch (err) {
      console.log(`  multipart parse failed: ${String(err)}`);
    }
  } else if (req.header('content-type')?.startsWith('application/json')) {
    console.log(`  json ${body.toString('utf8').slice(0, 500)}`);
  }

  const status = await statusFor(mode, hit, auth);
  if (range && status >= 200 && status < 300) {
    storePart(reqPath, range, body);
  }
  console.log(`  -> ${status} ${req.method} ${reqPath}`);

  if (mode === 'conflict') {
    res.status(status).json({ message: 'Upload already completed' });
  } else if (mode === 'big') {
    res.status(status).json({ ok: true, pad: 'x'.repeat(1_200_000) });
  } else {
    res.status(status).json({ ok: status < 300, status, bytes: body.length, md5: md5(body) });
  }
});

app.listen(PORT, () => {
  const addresses = Object.values(os.networkInterfaces())
    .flat()
    .filter((a) => a && a.family === 'IPv4' && !a.internal)
    .map((a) => `http://${a!.address}:${PORT}`);
  console.log(`RNBGU test server on http://localhost:${PORT}`);
  console.log(`LAN: ${addresses.join(' ') || 'none'}`);
  console.log(`Host name: http://${os.hostname()}:${PORT}`);
  console.log(`AUTH_TOKEN="${AUTH_TOKEN}" SLOW_MS=${SLOW_MS} SLOW_AUTH_MS=${SLOW_AUTH_MS}`);
});
