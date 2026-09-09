import type { Spec } from './NativeRNFileUploader';
import type {
  Define,
  Defined,
  Definition,
  FormPart,
  Json,
  Method,
  Part,
  RequestDescriptor,
  RetryPolicy,
} from './types';

export const DEFAULT_LIFETIME_MS = 14 * 24 * 60 * 60 * 1000;
/** `vars` are persisted natively next to every entry. Only they are capped. */
export const MAX_VARS_BYTES = 4096;

const METHODS: readonly Method[] = ['POST', 'PUT', 'PATCH', 'DELETE', 'GET'];

const DESCRIPTOR_KEYS = [
  'url',
  'method',
  'headers',
  'data',
  'form',
  'file',
  'parts',
  'accept',
  'expiresAt',
  'retry',
  'android',
];
const PART_KEYS = ['url', 'headers', 'range'];
const RANGE_KEYS = ['start', 'end'];
const FORM_PART_KEYS = ['name', 'contentType', 'string', 'path', 'fileName'];
const RETRY_KEYS = ['backoff', 'terminalHttp'];
const BACKOFF_KEYS = ['baseMs', 'maxMs', 'jitter'];
const TERMINAL_HTTP_KEYS = ['exempt'];
const ACCEPT_RULE_KEYS = ['status', 'bodyIncludes'];
const ANDROID_KEYS = ['noNotification'];

/** The JS-side settings that `configure()` stores. */
export type Settings = {
  lifetimeMs: number;
  headers?: () => Record<string, string>;
  retry?: Partial<RetryPolicy>;
};

/** What crosses to native `enqueue()`. */
export type EnqueueEntry = {
  id: string;
  key: string;
  vars: Json;
  descriptor: RequestDescriptor;
};

// The registry stores definitions of every shape under one map. The generic
// parameters are enforced at define() and re-applied by the delivery layer.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type AnyDefinition = Definition<any, any>;

type RegistryDeps = {
  native: Pick<Spec, 'enqueue'>;
  definitions: Map<string, AnyDefinition>;
  getSettings: () => Settings;
  /** Called with every enqueue promise, so delivery can wait for it. */
  trackMutate: (id: string, pending: Promise<unknown>) => void;
  warn?: (message: string) => void;
  now?: () => number;
};

export type Registry = {
  define: Define;
};

declare const __DEV__: boolean | undefined;
declare const process: { env?: { NODE_ENV?: string } } | undefined;

/** React Native sets `__DEV__`. Other hosts (Jest) fall back to NODE_ENV. */
export const isDev = (): boolean => {
  if (typeof __DEV__ === 'boolean') {
    return __DEV__;
  }
  return (
    typeof process === 'undefined' || process?.env?.NODE_ENV !== 'production'
  );
};

const isPlainObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** UTF-8 length of a string that JSON.stringify produced. */
export const utf8ByteLength = (s: string): number => {
  let bytes = 0;
  for (let i = 0; i < s.length; i++) {
    const code = s.charCodeAt(i);
    if (code < 0x80) {
      bytes += 1;
    } else if (code < 0x800) {
      bytes += 2;
    } else if (code >= 0xd800 && code <= 0xdbff) {
      // A surrogate pair encodes one 4-byte code point.
      bytes += 4;
      i++;
    } else {
      bytes += 3;
    }
  }
  return bytes;
};

/** RFC 4122 version 4, from Math.random. Ids only need to be unique per app. */
export const uuidV4 = (): string =>
  'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    const v = c === 'x' ? r : (r % 4) + 8;
    return v.toString(16);
  });

const editDistance = (a: string, b: string): number => {
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const row = [i];
    for (let j = 1; j <= b.length; j++) {
      const same = a[i - 1] === b[j - 1] ? 0 : 1;
      row[j] = Math.min(
        (prev[j] ?? 0) + 1,
        (row[j - 1] ?? 0) + 1,
        (prev[j - 1] ?? 0) + same,
      );
    }
    prev = row;
  }
  return prev[b.length] ?? 0;
};

// TypeScript does not check an inferred arrow return for excess properties,
// so `header:` in place of `headers:` compiles. Rejecting unknown keys here
// turns that silent drop into a mutate() rejection.
const rejectUnknownKeys = (
  value: Record<string, unknown>,
  known: readonly string[],
  where: string,
): void => {
  Object.keys(value).forEach((key) => {
    if (known.includes(key)) {
      return;
    }
    const guess = known.find(
      (candidate) =>
        editDistance(key.toLowerCase(), candidate.toLowerCase()) <= 2,
    );
    throw new Error(
      `mutate: unknown ${where} field "${key}".${
        guess ? ` Did you mean "${guess}"?` : ''
      }`,
    );
  });
};

// The parts must tile the file from byte 0. They must be sorted in ascending
// order, with no gaps and no overlaps. Each plan from chunkPlan obeys this by
// construction.
const validateParts = (parts: unknown): void => {
  if (!Array.isArray(parts) || parts.length === 0) {
    throw new Error('mutate: parts must be a non-empty array');
  }
  let expectedStart = 0;
  (parts as Part[]).forEach((part, i) => {
    if (!isPlainObject(part)) {
      throw new Error(`mutate: parts[${i}] must be an object`);
    }
    rejectUnknownKeys(part, PART_KEYS, `parts[${i}]`);
    const { url, headers, range } = part;
    if (typeof url !== 'string' || url.length === 0) {
      throw new Error(`mutate: parts[${i}].url must be a non-empty string`);
    }
    if (headers !== undefined && !isPlainObject(headers)) {
      throw new Error(
        `mutate: parts[${i}].headers must be a plain object when present`,
      );
    }
    if (!isPlainObject(range)) {
      throw new Error(`mutate: parts[${i}].range must be an object`);
    }
    rejectUnknownKeys(range, RANGE_KEYS, `parts[${i}].range`);
    if (
      !Number.isInteger(range.start) ||
      !Number.isInteger(range.end) ||
      range.start < 0 ||
      range.start >= range.end
    ) {
      throw new Error(
        `mutate: parts[${i}].range must satisfy 0 <= start < end, got ${JSON.stringify(
          range,
        )}`,
      );
    }
    if (range.start !== expectedStart) {
      throw new Error(
        i === 0
          ? `mutate: parts[0].range.start must be 0, got ${range.start}`
          : `mutate: parts must be sorted ascending and tile the file with no gaps or overlaps. parts[${i}].range.start is ${
              range.start
            } but parts[${i - 1}].range.end is ${expectedStart}`,
      );
    }
    expectedStart = range.end;
  });
};

const validateForm = (form: unknown): void => {
  if (!Array.isArray(form) || form.length === 0) {
    throw new Error('mutate: form must be a non-empty array');
  }
  (form as FormPart[]).forEach((part, i) => {
    if (!isPlainObject(part)) {
      throw new Error(`mutate: form[${i}] must be an object`);
    }
    rejectUnknownKeys(part, FORM_PART_KEYS, `form[${i}]`);
    if (typeof part.name !== 'string' || part.name.length === 0) {
      throw new Error(`mutate: form[${i}].name must be a non-empty string`);
    }
    if (typeof part.contentType !== 'string' || part.contentType.length === 0) {
      throw new Error(
        `mutate: form[${i}].contentType must be a non-empty string`,
      );
    }
    const hasString = typeof (part as { string?: unknown }).string === 'string';
    const hasPath = typeof (part as { path?: unknown }).path === 'string';
    if (hasString === hasPath) {
      throw new Error(
        `mutate: form[${i}] must set exactly one of string, path`,
      );
    }
  });
};

const requireObject = (
  value: unknown,
  where: string,
): Record<string, unknown> => {
  if (!isPlainObject(value)) {
    throw new Error(`mutate: ${where} must be a plain object`);
  }
  return value;
};

const validateRetry = (retry: unknown): void => {
  const r = requireObject(retry, 'retry');
  rejectUnknownKeys(r, RETRY_KEYS, 'retry');
  if (r.backoff !== undefined) {
    const backoff = requireObject(r.backoff, 'retry.backoff');
    rejectUnknownKeys(backoff, BACKOFF_KEYS, 'retry.backoff');
  }
  if (r.terminalHttp !== undefined) {
    const terminal = requireObject(r.terminalHttp, 'retry.terminalHttp');
    rejectUnknownKeys(terminal, TERMINAL_HTTP_KEYS, 'retry.terminalHttp');
    const { exempt } = terminal;
    if (
      !Array.isArray(exempt) ||
      !exempt.every((status) => typeof status === 'number')
    ) {
      throw new Error(
        'mutate: retry.terminalHttp.exempt must be an array of numbers',
      );
    }
  }
};

const validateAccept = (accept: unknown): void => {
  if (!Array.isArray(accept)) {
    throw new Error('mutate: accept must be an array');
  }
  accept.forEach((rule, i) => {
    const r = requireObject(rule, `accept[${i}]`);
    rejectUnknownKeys(r, ACCEPT_RULE_KEYS, `accept[${i}]`);
    if (typeof r.status !== 'number') {
      throw new Error(`mutate: accept[${i}].status must be a number`);
    }
    if (r.bodyIncludes !== undefined && typeof r.bodyIncludes !== 'string') {
      throw new Error(`mutate: accept[${i}].bodyIncludes must be a string`);
    }
  });
};

const validateAndroid = (android: unknown): void => {
  const a = requireObject(android, 'android');
  rejectUnknownKeys(a, ANDROID_KEYS, 'android');
  if (a.noNotification !== undefined && typeof a.noNotification !== 'boolean') {
    throw new Error('mutate: android.noNotification must be a boolean');
  }
};

/**
 * The descriptor's headers over the provider's. Names match without regard
 * to case, and the descriptor's spelling is the one kept.
 */
const mergeHeaders = (
  provided: Record<string, string>,
  own: Record<string, string> = {},
): Record<string, string> => {
  const overridden = new Set(
    Object.keys(own).map((name) => name.toLowerCase()),
  );
  const merged: Record<string, string> = {};
  Object.entries(provided).forEach(([name, value]) => {
    if (!overridden.has(name.toLowerCase())) {
      merged[name] = value;
    }
  });
  return { ...merged, ...own };
};

/**
 * Rejects a malformed descriptor before it crosses the bridge. Then native
 * never persists an entry that cannot run. Nested objects are checked for
 * unknown keys too, so a misspelled field cannot be dropped in silence.
 */
export const validateDescriptor = (descriptor: unknown): RequestDescriptor => {
  if (!isPlainObject(descriptor)) {
    throw new Error('mutate: request() must return a descriptor object');
  }
  rejectUnknownKeys(descriptor, DESCRIPTOR_KEYS, 'descriptor');
  const d = descriptor as RequestDescriptor;
  const kinds = (['data', 'form', 'file'] as const).filter(
    (kind) => d[kind] !== undefined,
  );
  if (kinds.length !== 1) {
    throw new Error(
      `mutate: the descriptor must set exactly one of data, form, file; got ${
        kinds.length === 0 ? 'none' : kinds.join(', ')
      }`,
    );
  }
  if (d.parts !== undefined && d.file === undefined) {
    throw new Error('mutate: parts requires file');
  }
  if (d.url === undefined) {
    if (d.parts === undefined) {
      throw new Error('mutate: url is required unless parts is set');
    }
  } else if (typeof d.url !== 'string' || d.url.length === 0) {
    throw new Error('mutate: url must be a non-empty string');
  }
  if (d.method !== undefined && !METHODS.includes(d.method)) {
    throw new Error(
      `mutate: method must be one of ${METHODS.join(', ')}, got ${String(
        d.method,
      )}`,
    );
  }
  if (d.headers !== undefined && !isPlainObject(d.headers)) {
    throw new Error('mutate: headers must be a plain object when present');
  }
  if (d.file !== undefined && (typeof d.file !== 'string' || !d.file)) {
    throw new Error('mutate: file must be a non-empty path');
  }
  if (d.form !== undefined) {
    validateForm(d.form);
  }
  if (d.parts !== undefined) {
    validateParts(d.parts);
  }
  if (d.retry !== undefined) {
    validateRetry(d.retry);
  }
  if (d.accept !== undefined) {
    validateAccept(d.accept);
  }
  if (d.android !== undefined) {
    validateAndroid(d.android);
  }
  if (
    d.expiresAt !== undefined &&
    (!Number.isFinite(d.expiresAt) || d.expiresAt <= 0)
  ) {
    throw new Error(
      `mutate: expiresAt must be a finite epoch-ms timestamp, got ${d.expiresAt}`,
    );
  }
  return d;
};

/**
 * Holds the definitions of one client and builds `define()`. Every `mutate()`
 * validates `vars` and the descriptor, merges the configured headers under the
 * descriptor's (names matched without regard to case), defaults `expiresAt`,
 * hands the entry to native, and resolves with the entry's own id.
 */
export const createRegistry = ({
  native,
  definitions,
  getSettings,
  trackMutate,
  warn = console.warn,
  now = Date.now,
}: RegistryDeps): Registry => {
  // The overloads on Define keep the with-parser and without-parser shapes
  // apart for callers. One implementation serves both.
  const define = (<V extends Json, T>(
    definition: Definition<V, T>,
  ): Defined<V, T> => {
    const { key } = definition;
    if (typeof key !== 'string' || key.length === 0) {
      throw new Error('define: key must be a non-empty string');
    }
    if (typeof definition.request !== 'function') {
      throw new Error(`define: "${key}" needs a request function`);
    }
    // Hot reload re-evaluates modules, so a duplicate key replaces instead of
    // throwing. The warning catches two modules that share a key by mistake.
    if (definitions.has(key) && isDev()) {
      warn(
        `define: "${key}" is already defined. The new definition replaces it.`,
      );
    }
    definitions.set(key, definition);

    const mutate = async (
      input: V | undefined,
      options?: { id?: string },
    ): Promise<{ id: string }> => {
      // A no-vars definition calls mutate() with nothing; native stores null.
      const vars = (input === undefined ? null : input) as V;
      const serialized = JSON.stringify(vars);
      if (serialized === undefined) {
        throw new Error('mutate: vars must be a JSON value');
      }
      const bytes = utf8ByteLength(serialized);
      if (bytes > MAX_VARS_BYTES) {
        throw new Error(
          `mutate: vars for "${key}" is ${bytes} bytes; the limit is ${MAX_VARS_BYTES}`,
        );
      }
      if (options?.id !== undefined && !options.id) {
        throw new Error('mutate: id must be a non-empty string when given');
      }
      // The current definition under this key, so a replaced definition's
      // request() is the one that runs.
      const current = (definitions.get(key) ?? definition) as Definition<V, T>;
      const descriptor = validateDescriptor(current.request(vars));
      const settings = getSettings();
      const provided = settings.headers?.() ?? {};
      if (!isPlainObject(provided)) {
        throw new Error('mutate: configure().headers() must return an object');
      }
      const entry: EnqueueEntry = {
        id: options?.id ?? uuidV4(),
        key,
        vars,
        descriptor: {
          ...descriptor,
          headers: mergeHeaders(provided, descriptor.headers),
          expiresAt: descriptor.expiresAt ?? now() + settings.lifetimeMs,
        },
      };
      const pending = native.enqueue(entry);
      trackMutate(entry.id, pending);
      // The JS id is the one the caller may have chosen and the one the
      // entry carries. Native's return value is not trusted for it.
      await pending;
      return { id: entry.id };
    };

    return { key, mutate } as Defined<V, T>;
  }) as Define;

  return { define };
};
