import type { Spec } from './NativeRNFileUploader';
import type {
  AnyDefined,
  Define,
  Defined,
  Definition,
  FormPart,
  Method,
  Part,
  RequestDescriptor,
  RetryPolicy,
  Vars,
} from './types';

export const DEFAULT_LIFETIME_MS = 14 * 24 * 60 * 60 * 1000;
/**
 * How long mutate() waits for native enqueue() before it rejects. Enqueue
 * includes the time to stage a copy of a `file` body and of form `path`
 * parts, so a large file takes longer. A timeout means native did not
 * answer, not that the request failed. This is a watchdog for a native path
 * that never settles, not a tuning knob.
 */
export const DEFAULT_ENQUEUE_TIMEOUT_MS = 10_000;
/**
 * Default `configure().maxVarsBytes`. `vars` are persisted natively next to
 * every entry. Only they are capped.
 */
export const MAX_VARS_BYTES = 1_048_576;

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
  'wifiOnly',
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
  enqueueTimeoutMs: number;
  maxVarsBytes: number;
  headers?: () => Record<string, string>;
  retry?: Partial<RetryPolicy>;
};

/** Rejects with `makeError()` when `promise` has not settled after `ms`. */
export const withTimeout = <T>(
  promise: Promise<T>,
  ms: number,
  makeError: () => Error,
): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(makeError()), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });

/**
 * The descriptor as it crosses to native. `dataJson` is `JSON.stringify(data)`
 * and replaces `data`. It is absent for a bodiless request. `wifiOnly` crosses
 * only when the definition set it; absent means "follow setWifiOnly()".
 */
export type NativeDescriptor = Omit<RequestDescriptor, 'data'> & {
  dataJson?: string;
};

/**
 * What crosses to native `enqueue()`. `vars` and `data` cross as JSON
 * strings, because React Native on iOS drops object keys whose value is
 * null. Native parses them.
 */
export type EnqueueEntry = {
  id: string;
  key: string;
  varsJson: string;
  descriptor: NativeDescriptor;
};

// The registry stores definitions of every shape under one map. The generic
// parameters are enforced at define() and re-applied by the delivery layer.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type AnyDefinition = Definition<any, any>;

type RegistryDeps = {
  native: Pick<Spec, 'enqueue'>;
  /** The definitions that outcomes route to. Only `register()` fills it. */
  definitions: Map<string, AnyDefinition>;
  getSettings: () => Settings;
  /** Called with every mutate() promise, so delivery can wait for it. */
  trackMutate: (id: string, pending: Promise<unknown>) => void;
  warn?: (message: string) => void;
  now?: () => number;
};

export type Registry = {
  define: Define;
  /**
   * Makes `list` the definitions that outcomes route to, and releases the
   * `mutate()` calls that wait for it. The first call fixes the keys. A later
   * call must list the same keys, and changes nothing.
   */
  register: (list: readonly AnyDefined[]) => void;
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

/**
 * `JSON.stringify` as native will run it. A throw (a cycle, a BigInt, a
 * `toJSON` that throws) becomes a `mutate:` error. The result is `undefined`
 * for a function, a symbol, or a `toJSON` that returns nothing.
 */
const stringifyForNative = (
  value: unknown,
  where: string,
): string | undefined => {
  try {
    return JSON.stringify(value);
  } catch (e) {
    const reason = e instanceof Error ? e.message : String(e);
    throw new Error(`mutate: ${where} is not JSON-serializable: ${reason}`);
  }
};

/**
 * TypeScript accepts any object as vars, so the runtime checks what native
 * will persist. Methods on a class instance are dropped, as JSON.stringify
 * drops them; a Date or a `toJSON` that yields a primitive is rejected.
 */
const serializeVars = (vars: unknown): string => {
  if (typeof vars !== 'object') {
    throw new Error(
      `mutate: vars must be an object, an array, or null, got ${typeof vars}`,
    );
  }
  const serialized = stringifyForNative(vars, 'vars');
  if (
    serialized === undefined ||
    (serialized !== 'null' &&
      !serialized.startsWith('{') &&
      !serialized.startsWith('['))
  ) {
    throw new Error(
      'mutate: vars must serialize to a JSON object, an array, or null',
    );
  }
  return serialized;
};

/** The JSON that native sends as the body. `null` is the JSON body `null`. */
const serializeData = (data: unknown): string => {
  const serialized = stringifyForNative(data, 'data');
  if (serialized === undefined) {
    throw new Error(
      `mutate: data must be a JSON-serializable value, got ${typeof data}`,
    );
  }
  return serialized;
};

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
 * Returns the descriptor in its bridge form, with `data` as `dataJson`.
 */
export const validateDescriptor = (descriptor: unknown): NativeDescriptor => {
  if (!isPlainObject(descriptor)) {
    throw new Error('mutate: request() must return a descriptor object');
  }
  rejectUnknownKeys(descriptor, DESCRIPTOR_KEYS, 'descriptor');
  const d = descriptor as RequestDescriptor;
  const kinds = (['data', 'form', 'file'] as const).filter(
    (kind) => d[kind] !== undefined,
  );
  // No body is valid: a GET, a DELETE, or a POST that carries its meaning in
  // the URL.
  if (kinds.length > 1) {
    throw new Error(
      `mutate: the descriptor must set at most one of data, form, file; got ${kinds.join(
        ', ',
      )}`,
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
  // A DELETE may carry a body, because some APIs take one.
  if (d.method === 'GET' && kinds.length > 0) {
    throw new Error(
      `mutate: a GET request cannot have a body; got ${kinds.join(', ')}`,
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
  if (d.wifiOnly !== undefined && typeof d.wifiOnly !== 'boolean') {
    throw new Error('mutate: wifiOnly must be a boolean when present');
  }
  if (
    d.expiresAt !== undefined &&
    (!Number.isFinite(d.expiresAt) || d.expiresAt <= 0)
  ) {
    throw new Error(
      `mutate: expiresAt must be a finite epoch-ms timestamp, got ${d.expiresAt}`,
    );
  }
  const { data, ...rest } = d;
  return data === undefined ? rest : { ...rest, dataJson: serializeData(data) };
};

/**
 * Holds the definitions of one client and builds `define()`. Every `mutate()`
 * waits for `configure()`, refuses a key that `configure()` did not list,
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
  // Every handle define() returned, so register() takes only this client's.
  const created = new WeakMap<object, AnyDefinition>();
  const definedKeys = new Set<string>();
  let registered = false;
  let release: () => void = () => undefined;
  const configured = new Promise<void>((resolve) => {
    release = resolve;
  });

  const register = (list: readonly AnyDefined[]): void => {
    if (!Array.isArray(list)) {
      throw new Error('configure: definitions must be an array');
    }
    const byKey = new Map<string, AnyDefinition>();
    list.forEach((handle) => {
      const definition =
        typeof handle === 'object' && handle !== null
          ? created.get(handle)
          : undefined;
      if (!definition) {
        throw new Error(
          "configure: every definition must be one that this client's define() returned",
        );
      }
      if (byKey.has(definition.key)) {
        throw new Error(`configure: "${definition.key}" is listed twice`);
      }
      byKey.set(definition.key, definition);
    });
    if (registered) {
      const sameKeys =
        byKey.size === definitions.size &&
        [...byKey.keys()].every((key) => definitions.has(key));
      if (!sameKeys) {
        throw new Error(
          'configure: the first call fixes the definitions. A later call must list the same keys.',
        );
      }
      return;
    }
    byKey.forEach((definition, key) => definitions.set(key, definition));
    registered = true;
    release();
  };

  // The overloads on Define keep the with-parser and without-parser shapes
  // apart for callers. One implementation serves both.
  const define = (<V extends Vars, T>(
    definition: Definition<V, T>,
  ): Defined<V, T> => {
    const { key } = definition;
    if (typeof key !== 'string' || key.length === 0) {
      throw new Error('define: key must be a non-empty string');
    }
    if (typeof definition.request !== 'function') {
      throw new Error(`define: "${key}" needs a request function`);
    }
    // The warnings catch two modules that share a key by mistake, and a
    // module that loads too late to be listed.
    if (isDev()) {
      if (registered && !definitions.has(key)) {
        warn(
          `define: "${key}" is defined after configure() and is not in its definitions, so its mutate() rejects.`,
        );
      } else if (definedKeys.has(key)) {
        warn(
          registered
            ? `define: "${key}" is already defined. The new definition replaces it.`
            : `define: "${key}" is already defined. configure() routes the key to the one definition it lists.`,
        );
      }
    }
    definedKeys.add(key);
    // Hot reload re-evaluates a module after configure(), so a registered
    // key takes the new definition.
    if (definitions.has(key)) {
      definitions.set(key, definition);
    }

    const enqueue = async ({
      id,
      varsJson,
      descriptor,
      calledAt,
    }: {
      id: string;
      varsJson: string;
      descriptor: NativeDescriptor;
      calledAt: number;
    }): Promise<void> => {
      await configured;
      if (!definitions.has(key)) {
        throw new Error(
          `mutate: "${key}" is not in configure().definitions, so its outcome would have no handler`,
        );
      }
      const settings = getSettings();
      const bytes = utf8ByteLength(varsJson);
      if (bytes > settings.maxVarsBytes) {
        throw new Error(
          `mutate: vars for "${key}" is ${bytes} bytes; the limit is ${settings.maxVarsBytes} (configure().maxVarsBytes)`,
        );
      }
      const provided = settings.headers?.() ?? {};
      if (!isPlainObject(provided)) {
        throw new Error('mutate: configure().headers() must return an object');
      }
      const entry: EnqueueEntry = {
        id,
        key,
        varsJson,
        descriptor: {
          ...descriptor,
          headers: mergeHeaders(provided, descriptor.headers),
          expiresAt: descriptor.expiresAt ?? calledAt + settings.lifetimeMs,
        },
      };
      // Watchdog. Turn a native enqueue() that never settles into a rejection
      // with a name, and let delivery for this id proceed instead of waiting
      // forever. Enqueue includes the time to stage a copy of a `file` body
      // and of form `path` parts, so a large file takes longer. A timeout
      // means native did not answer, not that the request failed. If native
      // did persist the entry, its outcome still reaches the handlers, and a
      // same-id retry resumes instead of duplicating.
      await withTimeout(
        native.enqueue(entry),
        settings.enqueueTimeoutMs,
        () => {
          const message = `mutate: native enqueue for "${key}" (id ${id}) did not settle within ${settings.enqueueTimeoutMs} ms. Native did not answer; the entry may still be persisted.`;
          warn(message);
          return new Error(message);
        },
      );
    };

    const mutate = async (
      input: V | undefined,
      options?: { id?: string },
    ): Promise<{ id: string }> => {
      // A no-vars definition calls mutate() with nothing; native stores null.
      const vars = (input === undefined ? null : input) as V;
      const varsJson = serializeVars(vars);
      if (options?.id !== undefined && !options.id) {
        throw new Error('mutate: id must be a non-empty string when given');
      }
      // The vars, the descriptor and the call time are taken now, as the
      // caller passed them, even when the entry waits for configure(). The
      // registered definition under this key runs, so a replaced
      // definition's request() is the one that runs.
      const current = (definitions.get(key) ?? definition) as Definition<V, T>;
      const descriptor = validateDescriptor(current.request(vars));
      // The JS id is the one the caller may have chosen and the one the
      // entry carries. Native's return value is not trusted for it.
      const id = options?.id ?? uuidV4();
      const pending = enqueue({ id, varsJson, descriptor, calledAt: now() });
      // Tracked before it waits for configure(), so an outcome replayed for
      // this id is delivered after the caller has the id.
      trackMutate(id, pending);
      await pending;
      return { id };
    };

    const handle = { key, mutate } as Defined<V, T>;
    created.set(handle, definition);
    return handle;
  }) as Define;

  return { define, register };
};
