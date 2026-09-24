import {
  createRegistry,
  DEFAULT_LIFETIME_MS,
  MAX_VARS_BYTES,
  utf8ByteLength,
  uuidV4,
  type AnyDefinition,
  type EnqueueEntry,
  type Settings,
} from '../registry';

const NOW = 1_700_000_000_000;

const setup = (settings: Partial<Settings> = {}) => {
  const enqueue = jest.fn(async (entry: EnqueueEntry) => entry.id);
  const definitions = new Map<string, AnyDefinition>();
  const trackMutate = jest.fn();
  const warn = jest.fn();
  const current: Settings = {
    lifetimeMs: DEFAULT_LIFETIME_MS,
    enqueueTimeoutMs: 10_000,
    maxVarsBytes: MAX_VARS_BYTES,
    ...settings,
  };
  const registry = createRegistry({
    native: { enqueue },
    definitions,
    getSettings: () => current,
    trackMutate,
    warn,
    now: () => NOW,
  });
  const lastEntry = (): EnqueueEntry => enqueue.mock.calls.at(-1)![0];
  return { ...registry, enqueue, definitions, trackMutate, warn, lastEntry };
};

const jsonPost = (vars: { n: number }) => ({
  url: `https://example.com/items/${vars.n}`,
  data: { n: vars.n },
});

describe('define', () => {
  it('registers the definition under its key and returns { key, mutate }', () => {
    const { define, definitions } = setup();
    const defined = define({ key: 'item.create', request: jsonPost });
    expect(defined.key).toBe('item.create');
    expect(typeof defined.mutate).toBe('function');
    expect(definitions.get('item.create')?.key).toBe('item.create');
  });

  it('replaces a duplicate key and warns', () => {
    const { define, definitions, warn } = setup();
    const first = { key: 'dup', request: jsonPost };
    const second = { key: 'dup', request: jsonPost, onSuccess: jest.fn() };
    define(first);
    expect(warn).not.toHaveBeenCalled();
    define(second);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toMatch(/"dup" is already defined/);
    expect(definitions.get('dup')).toBe(second);
  });

  it('runs the replacement request() from a mutate on the earlier handle', async () => {
    const { define, lastEntry } = setup();
    const old = define({ key: 'k', request: jsonPost });
    define({
      key: 'k',
      request: (vars: { n: number }) => ({ url: 'https://new', data: vars }),
    });
    await old.mutate({ n: 1 });
    expect(lastEntry().descriptor.url).toBe('https://new');
  });

  it('rejects an empty key or a missing request', () => {
    const { define } = setup();
    expect(() => define({ key: '', request: jsonPost })).toThrow(/key/);
    expect(() =>
      define({ key: 'x', request: undefined as unknown as typeof jsonPost }),
    ).toThrow(/request/);
  });
});

describe('mutate', () => {
  it('runs request(vars) exactly once and enqueues { id, key, vars, descriptor }', async () => {
    const { define, enqueue, lastEntry } = setup();
    const request = jest.fn(jsonPost);
    const create = define({ key: 'item.create', request });
    const result = await create.mutate({ n: 7 }, { id: 'local-7' });
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith({ n: 7 });
    expect(enqueue).toHaveBeenCalledTimes(1);
    expect(lastEntry()).toMatchObject({
      id: 'local-7',
      key: 'item.create',
      vars: { n: 7 },
      descriptor: { url: 'https://example.com/items/7', data: { n: 7 } },
    });
    expect(result).toEqual({ id: 'local-7' });
  });

  it('stores null vars for a mutate() with no arguments', async () => {
    const request = jest.fn(() => ({ url: 'https://x', data: null }));
    const { define, lastEntry } = setup();
    const ping = define({ key: 'ping', request });
    await ping.mutate();
    expect(request).toHaveBeenCalledWith(null);
    expect(lastEntry().vars).toBeNull();
    await ping.mutate(undefined, { id: 'fixed' });
    expect(lastEntry()).toMatchObject({ id: 'fixed', vars: null });
  });

  it('resolves with the entry id, not the id native returns', async () => {
    const { define, enqueue } = setup();
    enqueue.mockResolvedValueOnce('native-id');
    const create = define({ key: 'k', request: jsonPost });
    await expect(create.mutate({ n: 1 }, { id: 'mine' })).resolves.toEqual({
      id: 'mine',
    });
  });

  it('generates a UUID v4 when no id is given', async () => {
    const { define, lastEntry } = setup();
    const create = define({ key: 'k', request: jsonPost });
    const { id } = await create.mutate({ n: 1 });
    expect(id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(lastEntry().id).toBe(id);
  });

  it('hands the enqueue promise to trackMutate under the entry id', async () => {
    const { define, trackMutate } = setup();
    const create = define({ key: 'k', request: jsonPost });
    await create.mutate({ n: 1 }, { id: 'abc' });
    expect(trackMutate).toHaveBeenCalledWith('abc', expect.any(Promise));
  });

  it('rejects when native enqueue rejects', async () => {
    const { define, enqueue } = setup();
    enqueue.mockRejectedValueOnce(new Error('E_NOT_IMPLEMENTED'));
    const create = define({ key: 'k', request: jsonPost });
    await expect(create.mutate({ n: 1 })).rejects.toThrow('E_NOT_IMPLEMENTED');
  });

  it('rejects when request() throws, without reaching native', async () => {
    const { define, enqueue } = setup();
    const boom = define({
      key: 'k',
      request: (_vars: null) => {
        throw new Error('no url yet');
      },
    });
    await expect(boom.mutate(null)).rejects.toThrow('no url yet');
    expect(enqueue).not.toHaveBeenCalled();
  });

  describe('vars cap', () => {
    it('defaults to 1 MB', () => {
      expect(MAX_VARS_BYTES).toBe(1_048_576);
    });

    it('accepts vars at exactly the default cap and rejects one byte over', async () => {
      const { define, enqueue } = setup();
      const send = define({
        key: 'k',
        request: (_vars: { s: string }) => ({ url: 'https://x', data: null }),
      });
      // JSON.stringify({ s }) adds {"s":""} = 8 bytes around the payload.
      const fits = 'a'.repeat(MAX_VARS_BYTES - 8);
      await expect(send.mutate({ s: fits })).resolves.toBeDefined();
      await expect(send.mutate({ s: fits + 'a' })).rejects.toThrow(
        /vars for "k" is 1048577 bytes; the limit is 1048576 \(configure\(\)\.maxVarsBytes\)/,
      );
      expect(enqueue).toHaveBeenCalledTimes(1);
    });

    it('reads the configured cap at mutate()', async () => {
      const { define, enqueue } = setup({ maxVarsBytes: 16 });
      const send = define({
        key: 'k',
        request: (_vars: { s: string }) => ({ url: 'https://x', data: null }),
      });
      await expect(send.mutate({ s: 'a'.repeat(8) })).resolves.toBeDefined();
      await expect(send.mutate({ s: 'a'.repeat(9) })).rejects.toThrow(
        /is 17 bytes; the limit is 16/,
      );
      expect(enqueue).toHaveBeenCalledTimes(1);
    });

    it('counts UTF-8 bytes, not UTF-16 code units', async () => {
      const { define } = setup({ maxVarsBytes: 4096 });
      const send = define({
        key: 'k',
        request: (_vars: { s: string }) => ({ url: 'https://x', data: null }),
      });
      // 1400 three-byte characters = 4200 bytes but only 1400 code units.
      await expect(send.mutate({ s: '€'.repeat(1400) })).rejects.toThrow(
        /4208 bytes/,
      );
      expect(utf8ByteLength('a')).toBe(1);
      expect(utf8ByteLength('é')).toBe(2);
      expect(utf8ByteLength('€')).toBe(3);
      expect(utf8ByteLength('\u{1F600}')).toBe(4);
    });
  });

  describe('vars serializability', () => {
    const anyVars = () => {
      const { define, enqueue, lastEntry } = setup();
      const send = define({
        key: 'k',
        request: (_vars: object) => ({ url: 'https://x', data: null }),
      });
      return { send, enqueue, lastEntry };
    };

    it('rejects a cycle', async () => {
      const { send, enqueue } = anyVars();
      const loop: { self?: object } = {};
      loop.self = loop;
      await expect(send.mutate(loop)).rejects.toThrow(
        /^mutate: vars is not JSON-serializable: /,
      );
      expect(enqueue).not.toHaveBeenCalled();
    });

    it('rejects a function or a primitive as vars', async () => {
      const { send, enqueue } = anyVars();
      await expect(send.mutate(() => 1)).rejects.toThrow(
        'mutate: vars must be an object, an array, or null, got function',
      );
      await expect(send.mutate('s' as unknown as object)).rejects.toThrow(
        /got string/,
      );
      expect(enqueue).not.toHaveBeenCalled();
    });

    it('rejects an object that serializes to a primitive', async () => {
      const { send } = anyVars();
      await expect(send.mutate(new Date(0))).rejects.toThrow(
        'mutate: vars must serialize to a JSON object, an array, or null',
      );
      await expect(send.mutate({ toJSON: () => undefined })).rejects.toThrow(
        /must serialize to a JSON object/,
      );
    });

    it('accepts a class instance and passes it through unchanged', async () => {
      class Point {
        constructor(public x: number, public y: number) {}
        norm() {
          return Math.hypot(this.x, this.y);
        }
      }
      const { send, lastEntry } = anyVars();
      const point = new Point(1, 2);
      await expect(send.mutate(point)).resolves.toBeDefined();
      // Only validated. Native stringifies, which drops the method.
      expect(lastEntry().vars).toBe(point);
    });

    it('accepts nested undefined fields and an array', async () => {
      const { send, lastEntry } = anyVars();
      const vars = { title: undefined, ids: ['a'] as readonly string[] };
      await expect(send.mutate(vars)).resolves.toBeDefined();
      expect(lastEntry().vars).toBe(vars);
      await expect(send.mutate([1, 2])).resolves.toBeDefined();
    });
  });

  describe('descriptor validation', () => {
    const mutateWith = (descriptor: unknown) => {
      const { define, enqueue } = setup();
      const d = define({
        key: 'k',
        request: (_vars: null) => descriptor as ReturnType<typeof jsonPost>,
      });
      return { promise: d.mutate(null), enqueue };
    };

    it('allows at most one body kind', async () => {
      // A DELETE has no body. So does a POST whose meaning is in the URL.
      const { promise, enqueue } = mutateWith({
        url: 'https://x',
        method: 'DELETE',
      });
      await expect(promise).resolves.toEqual({ id: expect.any(String) });
      expect(enqueue).toHaveBeenCalledTimes(1);
      await expect(
        mutateWith({ url: 'https://x', data: {}, file: '/f' }).promise,
      ).rejects.toThrow(/at most one of data, form, file; got data, file/);
    });

    it('accepts each body kind alone', async () => {
      await expect(
        mutateWith({ url: 'https://x', data: null }).promise,
      ).resolves.toBeDefined();
      await expect(
        mutateWith({ url: 'https://x', file: '/f' }).promise,
      ).resolves.toBeDefined();
      await expect(
        mutateWith({
          url: 'https://x',
          form: [
            { name: 'meta', contentType: 'application/json', string: '{}' },
            { name: 'photo', contentType: 'image/jpeg', path: '/p.jpg' },
          ],
        }).promise,
      ).resolves.toBeDefined();
    });

    it('rejects data that cannot serialize', async () => {
      await expect(
        mutateWith({ url: 'https://x', data: () => 1 }).promise,
      ).rejects.toThrow(
        'mutate: data must be a JSON-serializable value, got function',
      );
      const loop: { self?: object } = {};
      loop.self = loop;
      await expect(
        mutateWith({ url: 'https://x', data: loop }).promise,
      ).rejects.toThrow(/^mutate: data is not JSON-serializable: /);
      await expect(
        mutateWith({ url: 'https://x', data: { n: 1n } }).promise,
      ).rejects.toThrow(/data is not JSON-serializable/);
    });

    it('passes data with nested undefined fields through unchanged', async () => {
      const data = { title: undefined, value: { any: 1 } };
      const { promise, enqueue } = mutateWith({ url: 'https://x', data });
      await expect(promise).resolves.toBeDefined();
      expect(enqueue.mock.calls[0][0].descriptor.data).toBe(data);
    });

    it('rejects a form part without exactly one of string, path', async () => {
      await expect(
        mutateWith({
          url: 'https://x',
          form: [{ name: 'a', contentType: 'text/plain' }],
        }).promise,
      ).rejects.toThrow(/form\[0\] must set exactly one of string, path/);
    });

    it('allows parts only with file', async () => {
      await expect(
        mutateWith({
          data: {},
          parts: [{ url: 'https://p', range: { start: 0, end: 1 } }],
        }).promise,
      ).rejects.toThrow(/parts requires file/);
    });

    it('requires url unless parts is set', async () => {
      await expect(mutateWith({ data: {} }).promise).rejects.toThrow(
        /url is required unless parts is set/,
      );
      await expect(mutateWith({ url: '', data: {} }).promise).rejects.toThrow(
        /url must be a non-empty string/,
      );
      await expect(
        mutateWith({
          file: '/f',
          parts: [{ url: 'https://p', range: { start: 0, end: 1 } }],
        }).promise,
      ).resolves.toBeDefined();
    });

    it('rejects a method outside the union', async () => {
      await expect(
        mutateWith({ url: 'https://x', data: {}, method: 'FETCH' }).promise,
      ).rejects.toThrow(/method must be one of/);
    });

    it.each([NaN, Infinity, 0, -5])(
      'rejects expiresAt %p',
      async (expiresAt) => {
        await expect(
          mutateWith({ url: 'https://x', data: {}, expiresAt }).promise,
        ).rejects.toThrow(/expiresAt/);
      },
    );

    it('rejects a non-object descriptor', async () => {
      await expect(mutateWith(undefined).promise).rejects.toThrow(
        /request\(\) must return a descriptor object/,
      );
    });

    it('rejects an unknown descriptor field and suggests the nearest known one', async () => {
      await expect(
        mutateWith({ url: 'https://x', data: {}, header: { A: 'b' } }).promise,
      ).rejects.toThrow(
        'mutate: unknown descriptor field "header". Did you mean "headers"?',
      );
      await expect(
        mutateWith({ url: 'https://x', data: {}, expiryAt: 5 }).promise,
      ).rejects.toThrow(/unknown descriptor field "expiryAt".*"expiresAt"/);
      await expect(
        mutateWith({ url: 'https://x', data: {}, timeout: 5 }).promise,
      ).rejects.toThrow(/^mutate: unknown descriptor field "timeout".$/);
    });

    it('rejects an unknown field on a part or a form part', async () => {
      await expect(
        mutateWith({
          file: '/f',
          parts: [
            { url: 'https://p', header: {}, range: { start: 0, end: 1 } },
          ],
        }).promise,
      ).rejects.toThrow(/unknown parts\[0\] field "header".*"headers"/);
      await expect(
        mutateWith({
          url: 'https://x',
          form: [
            {
              name: 'photo',
              contentType: 'image/jpeg',
              path: '/p.jpg',
              filename: 'a.jpg',
            },
          ],
        }).promise,
      ).rejects.toThrow(/unknown form\[0\] field "filename".*"fileName"/);
    });

    it('rejects an unknown field inside retry, accept, android or a part range', async () => {
      const base = { url: 'https://x', data: {} };
      await expect(
        mutateWith({ ...base, retry: { terminalHTTP: {} } }).promise,
      ).rejects.toThrow(/unknown retry field "terminalHTTP".*"terminalHttp"/);
      await expect(
        mutateWith({ ...base, retry: { backoff: { base: 1 } } }).promise,
      ).rejects.toThrow(/unknown retry\.backoff field "base".*"baseMs"/);
      await expect(
        mutateWith({ ...base, retry: { terminalHttp: { exmpt: [] } } }).promise,
      ).rejects.toThrow(/unknown retry\.terminalHttp field "exmpt".*"exempt"/);
      await expect(
        mutateWith({ ...base, accept: [{ statsu: 409 }] }).promise,
      ).rejects.toThrow(/unknown accept\[0\] field "statsu".*"status"/);
      await expect(
        mutateWith({ ...base, android: { noNotifications: true } }).promise,
      ).rejects.toThrow(/unknown android field "noNotifications"/);
      await expect(
        mutateWith({
          file: '/f',
          parts: [{ url: 'https://p', range: { start: 0, end: 1, length: 1 } }],
        }).promise,
      ).rejects.toThrow(/unknown parts\[0\]\.range field "length"/);
    });

    it('rejects the wrong value shape inside retry, accept and android', async () => {
      const base = { url: 'https://x', data: {} };
      await expect(mutateWith({ ...base, accept: {} }).promise).rejects.toThrow(
        /accept must be an array/,
      );
      await expect(
        mutateWith({ ...base, accept: [{ status: '409' }] }).promise,
      ).rejects.toThrow(/accept\[0\]\.status must be a number/);
      await expect(
        mutateWith({ ...base, accept: [{ status: 409, bodyIncludes: 5 }] })
          .promise,
      ).rejects.toThrow(/accept\[0\]\.bodyIncludes must be a string/);
      await expect(mutateWith({ ...base, retry: [] }).promise).rejects.toThrow(
        /retry must be a plain object/,
      );
      await expect(
        mutateWith({ ...base, retry: { terminalHttp: { exempt: [404, 'x'] } } })
          .promise,
      ).rejects.toThrow(
        /retry\.terminalHttp\.exempt must be an array of numbers/,
      );
      await expect(
        mutateWith({ ...base, android: { noNotification: 'yes' } }).promise,
      ).rejects.toThrow(/android\.noNotification must be a boolean/);
    });

    it('accepts valid retry, accept and android shapes', async () => {
      await expect(
        mutateWith({
          url: 'https://x',
          data: {},
          retry: {
            backoff: { baseMs: 1000, maxMs: 60_000, jitter: 0.2 },
            terminalHttp: { exempt: [] },
          },
          accept: [{ status: 409 }, { status: 400, bodyIncludes: 'dup' }],
          android: { noNotification: true },
        }).promise,
      ).resolves.toBeDefined();
      await expect(
        mutateWith({ url: 'https://x', data: {}, retry: {}, accept: [] })
          .promise,
      ).resolves.toBeDefined();
    });

    it('never reaches native on a rejected descriptor', async () => {
      const { promise, enqueue } = mutateWith({ data: {} });
      await expect(promise).rejects.toThrow();
      expect(enqueue).not.toHaveBeenCalled();
    });
  });

  describe('parts tiling (v9 rules)', () => {
    const parts = [
      { url: 'https://p/1', range: { start: 0, end: 10 } },
      { url: 'https://p/2', range: { start: 10, end: 20 } },
    ];
    const chunked = (override: unknown[]) => {
      const { define } = setup();
      return define({
        key: 'k',
        request: (_vars: null) => ({
          file: '/f',
          parts: override as typeof parts,
        }),
      }).mutate(null);
    };

    it('accepts a tiled plan', async () => {
      await expect(chunked(parts)).resolves.toBeDefined();
    });

    it('rejects empty parts', async () => {
      await expect(chunked([])).rejects.toThrow(/non-empty array/);
    });

    it.each([
      { start: -1, end: 10 },
      { start: 10, end: 10 },
      { start: 11, end: 10 },
      { start: 0.5, end: 10 },
      { start: 0, end: NaN },
    ])('rejects range %p', async (range) => {
      await expect(chunked([{ ...parts[0], range }])).rejects.toThrow(
        /0 <= start < end/,
      );
    });

    it('rejects a nonzero first start', async () => {
      await expect(
        chunked([{ ...parts[0], range: { start: 5, end: 10 } }, parts[1]]),
      ).rejects.toThrow(/parts\[0\]\.range\.start must be 0/);
    });

    it('rejects a gap', async () => {
      await expect(
        chunked([{ ...parts[0], range: { start: 0, end: 8 } }, parts[1]]),
      ).rejects.toThrow(/no gaps or overlaps/);
    });

    it('rejects an overlap', async () => {
      await expect(
        chunked([{ ...parts[0], range: { start: 0, end: 12 } }, parts[1]]),
      ).rejects.toThrow(/no gaps or overlaps/);
    });

    it('rejects out-of-order parts', async () => {
      await expect(chunked([parts[1], parts[0]])).rejects.toThrow(
        /parts\[0\]\.range\.start must be 0/,
      );
    });

    it('rejects an empty part url', async () => {
      await expect(
        chunked([{ ...parts[0], url: '' }, parts[1]]),
      ).rejects.toThrow(/parts\[0\]\.url must be a non-empty string/);
    });

    it('rejects non-object part headers', async () => {
      await expect(
        chunked([{ ...parts[0], headers: 'nope' }, parts[1]]),
      ).rejects.toThrow(/parts\[0\]\.headers must be a plain object/);
    });
  });

  describe('headers', () => {
    it('merges the descriptor headers over the configured provider', async () => {
      const headers = jest.fn(() => ({
        Authorization: 'Bearer old',
        'X-App': 'app',
      }));
      const { define, lastEntry } = setup({ headers });
      const send = define({
        key: 'k',
        request: (_vars: null) => ({
          url: 'https://x',
          data: {},
          headers: { Authorization: 'Bearer mine', 'Content-Type': 'a/b' },
        }),
      });
      await send.mutate(null);
      expect(headers).toHaveBeenCalledTimes(1);
      expect(lastEntry().descriptor.headers).toEqual({
        Authorization: 'Bearer mine',
        'X-App': 'app',
        'Content-Type': 'a/b',
      });
    });

    it('matches header names without regard to case and keeps the descriptor spelling', async () => {
      const { define, lastEntry } = setup({
        headers: () => ({ Authorization: 'a' }),
      });
      const send = define({
        key: 'k',
        request: (_vars: null) => ({
          url: 'https://x',
          data: {},
          headers: { authorization: 'b' },
        }),
      });
      await send.mutate(null);
      expect(lastEntry().descriptor.headers).toEqual({ authorization: 'b' });
    });

    it('sends the provider headers alone when the descriptor has none', async () => {
      const { define, lastEntry } = setup({ headers: () => ({ A: '1' }) });
      const send = define({
        key: 'k',
        request: (_vars: null) => ({ url: 'https://x', data: {} }),
      });
      await send.mutate(null);
      expect(lastEntry().descriptor.headers).toEqual({ A: '1' });
    });

    it('sends an empty header map with no provider and no descriptor headers', async () => {
      const { define, lastEntry } = setup();
      const send = define({
        key: 'k',
        request: (_vars: null) => ({ url: 'https://x', data: {} }),
      });
      await send.mutate(null);
      expect(lastEntry().descriptor.headers).toEqual({});
    });
  });

  describe('expiresAt', () => {
    it('defaults to now + lifetimeMs', async () => {
      const { define, lastEntry } = setup({ lifetimeMs: 1000 });
      const send = define({
        key: 'k',
        request: (_vars: null) => ({ url: 'https://x', data: {} }),
      });
      await send.mutate(null);
      expect(lastEntry().descriptor.expiresAt).toBe(NOW + 1000);
    });

    it('defaults the lifetime to 14 days', async () => {
      const { define, lastEntry } = setup();
      const send = define({
        key: 'k',
        request: (_vars: null) => ({ url: 'https://x', data: {} }),
      });
      await send.mutate(null);
      expect(lastEntry().descriptor.expiresAt).toBe(
        NOW + 14 * 24 * 60 * 60 * 1000,
      );
    });

    it('keeps an explicit expiresAt', async () => {
      const { define, lastEntry } = setup();
      const send = define({
        key: 'k',
        request: (_vars: null) => ({
          url: 'https://x',
          data: {},
          expiresAt: 42,
        }),
      });
      await send.mutate(null);
      expect(lastEntry().descriptor.expiresAt).toBe(42);
    });
  });
});

describe('uuidV4', () => {
  it('produces distinct RFC 4122 v4 strings', () => {
    const ids = new Set(Array.from({ length: 100 }, uuidV4));
    expect(ids.size).toBe(100);
    ids.forEach((id) =>
      expect(id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      ),
    );
  });
});

describe('enqueue watchdog', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('rejects and warns when native enqueue never settles', async () => {
    const { define, enqueue, warn } = setup({ enqueueTimeoutMs: 10_000 });
    enqueue.mockImplementation(() => new Promise<string>(() => undefined));
    const defined = define({ key: 'item.create', request: jsonPost });
    const promise = defined.mutate({ n: 1 });
    // Attach the handler before the clock moves, so the rejection is observed.
    const outcome = promise.then(
      () => 'resolved',
      (e: Error) => e.message,
    );
    await jest.advanceTimersByTimeAsync(9_999);
    expect(warn).not.toHaveBeenCalled();
    await jest.advanceTimersByTimeAsync(1);
    expect(await outcome).toMatch(
      /native enqueue for "item.create" \(id [^)]+\) did not settle within 10000 ms/,
    );
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it('clears the watchdog when native settles in time', async () => {
    const { define, warn } = setup({ enqueueTimeoutMs: 10_000 });
    const defined = define({ key: 'item.create', request: jsonPost });
    await expect(defined.mutate({ n: 1 })).resolves.toEqual({
      id: expect.any(String),
    });
    await jest.advanceTimersByTimeAsync(20_000);
    expect(warn).not.toHaveBeenCalled();
  });

  it('hands delivery the raced promise, so a timed-out id does not block delivery', async () => {
    const { define, enqueue, trackMutate } = setup({ enqueueTimeoutMs: 1_000 });
    enqueue.mockImplementation(() => new Promise<string>(() => undefined));
    const defined = define({ key: 'item.create', request: jsonPost });
    const promise = defined.mutate({ n: 1 }).catch(() => 'timed out');
    await jest.advanceTimersByTimeAsync(1_000);
    expect(await promise).toBe('timed out');
    const tracked = trackMutate.mock.calls[0]![1] as Promise<unknown>;
    await expect(tracked).rejects.toThrow(/did not settle/);
  });
});
