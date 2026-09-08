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
  const current: Settings = { lifetimeMs: DEFAULT_LIFETIME_MS, ...settings };
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

  it('resolves with the id native returns', async () => {
    const { define, enqueue } = setup();
    enqueue.mockResolvedValueOnce('native-id');
    const create = define({ key: 'k', request: jsonPost });
    await expect(create.mutate({ n: 1 }, { id: 'mine' })).resolves.toEqual({
      id: 'native-id',
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
    it('accepts vars at exactly the cap and rejects one byte over', async () => {
      const { define, enqueue } = setup();
      const send = define({
        key: 'k',
        request: (_vars: { s: string }) => ({ url: 'https://x', data: null }),
      });
      // JSON.stringify({ s }) adds {"s":""} = 8 bytes around the payload.
      const fits = 'a'.repeat(MAX_VARS_BYTES - 8);
      await expect(send.mutate({ s: fits })).resolves.toBeDefined();
      await expect(send.mutate({ s: fits + 'a' })).rejects.toThrow(
        /vars for "k" is 4097 bytes; the limit is 4096/,
      );
      expect(enqueue).toHaveBeenCalledTimes(1);
    });

    it('counts UTF-8 bytes, not UTF-16 code units', async () => {
      const { define } = setup();
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

  describe('descriptor validation', () => {
    const mutateWith = (descriptor: unknown) => {
      const { define, enqueue } = setup();
      const d = define({
        key: 'k',
        request: (_vars: null) => descriptor as ReturnType<typeof jsonPost>,
      });
      return { promise: d.mutate(null), enqueue };
    };

    it('requires exactly one body kind', async () => {
      await expect(mutateWith({ url: 'https://x' }).promise).rejects.toThrow(
        /exactly one of data, form, file; got none/,
      );
      await expect(
        mutateWith({ url: 'https://x', data: {}, file: '/f' }).promise,
      ).rejects.toThrow(/exactly one of data, form, file; got data, file/);
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
