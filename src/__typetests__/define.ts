// Type-level tests. tsc compiles this file with the library (yarn typecheck);
// nothing here runs. Each @ts-expect-error line fails the build when the
// error it expects goes away.
import type { Meta, OutcomeError, RawResponse, UploadClient } from '../types';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B
  ? 1
  : 2
  ? true
  : false;
const expectType = <Expected>(_actual: Expected): void => {};
const assertEqual = <A, B>(
  _proof: Equal<A, B> extends true ? true : never,
) => {};

declare const client: UploadClient;

type AddCommentVars = {
  siteId: string;
  customFieldNoteId: string;
  comment: string;
};
type Comment = { id: string; text: string };

// vars infer from the request parameter, data from the response parser.
const addComment = client.define({
  key: 'comment.add',
  request: ({ siteId, customFieldNoteId, comment }: AddCommentVars) => ({
    url: `https://api/sites/${siteId}/notes/${customFieldNoteId}/comments`,
    data: { comment },
  }),
  response: (raw) => (raw as { content: Comment[] }).content,
  onSuccess: (content, vars, meta) => {
    expectType<Comment[]>(content);
    expectType<AddCommentVars>(vars);
    expectType<Meta>(meta);
    assertEqual<typeof content, Comment[]>(true);
    assertEqual<typeof vars, AddCommentVars>(true);
  },
  onError: (error, vars) => {
    expectType<OutcomeError>(error);
    expectType<AddCommentVars>(vars);
  },
});

// The vars type flows into mutate().
void addComment.mutate({ siteId: 's', customFieldNoteId: 'n', comment: 'hi' });
void addComment.mutate(
  { siteId: 's', customFieldNoteId: 'n', comment: 'hi' },
  { id: 'local-1' },
);
expectType<Promise<{ id: string }>>(
  addComment.mutate({ siteId: 's', customFieldNoteId: 'n', comment: 'hi' }),
);
expectType<string>(addComment.key);

// Wrong vars are a type error.
// @ts-expect-error siteId must be a string
void addComment.mutate({ siteId: 1, customFieldNoteId: 'n', comment: 'hi' });
// @ts-expect-error comment is required
void addComment.mutate({ siteId: 's', customFieldNoteId: 'n' });
void addComment.mutate({
  siteId: 's',
  customFieldNoteId: 'n',
  comment: 'hi',
  // @ts-expect-error unknown field
  extra: 1,
});

// Without a response parser, onSuccess receives the RawResponse.
const putFile = client.define({
  key: 'file.put',
  request: ({ path, url }: { path: string; url: string }) => ({
    url,
    method: 'PUT',
    file: path,
  }),
  onSuccess: (_data) => {
    assertEqual<typeof _data, RawResponse>(true);
  },
});
void putFile.mutate({ path: '/tmp/a', url: 'https://x' });

// vars must be an object or null. A primitive is a type error. Whether the
// object serializes is checked at mutate(), not here.
client.define({
  key: 'bad.vars.primitive',
  // @ts-expect-error a string is not an object
  request: (_vars: string) => ({ url: 'https://x', data: null }),
});

// The descriptor is checked against RequestDescriptor.
client.define({
  key: 'bad.descriptor',
  // @ts-expect-error method must be one of the union
  request: (_vars: { a: string }) => ({
    url: 'https://x',
    data: 1,
    method: 'FETCH',
  }),
});

// A parser that throws away its input still fixes T.
const ping = client.define({
  key: 'ping',
  request: (_vars: null) => ({ url: 'https://x', data: null }),
  response: () => 42 as const,
  onSuccess: (_data) => {
    assertEqual<typeof _data, 42>(true);
  },
});
void ping.mutate(null);

// An onSuccess annotated with another type, and no response parser, does not
// compile. At runtime the handler would receive the RawResponse.
type Dto = { total: number };
client.define({
  key: 'annotated.no.parser',
  request: (_vars: { a: string }) => ({ url: 'https://x', data: null }),
  // @ts-expect-error onSuccess wants a Dto but there is no response parser
  onSuccess: (data: Dto) => {
    void data.total;
  },
});
declare const handleDto: (data: Dto, vars: { a: string }, meta: Meta) => void;
client.define({
  key: 'named.no.parser',
  request: (_vars: { a: string }) => ({ url: 'https://x', data: null }),
  // @ts-expect-error a named handler typed for a Dto also needs the parser
  onSuccess: handleDto,
});
// With the parser, the same handler compiles.
client.define({
  key: 'named.with.parser',
  request: (_vars: { a: string }) => ({ url: 'https://x', data: null }),
  response: (raw) => raw as Dto,
  onSuccess: handleDto,
});
// An onSuccess that spells out RawResponse compiles without a parser.
client.define({
  key: 'raw.annotated',
  request: (_vars: { a: string }) => ({ url: 'https://x', data: null }),
  onSuccess: (data: RawResponse) => {
    void data.bodyTruncated;
  },
});
// A definition with only onError infers too.
const errorOnly = client.define({
  key: 'error.only',
  request: (_vars: { a: string }) => ({ url: 'https://x', data: null }),
  onError: (_error, _vars) => {
    assertEqual<typeof _vars, { a: string }>(true);
  },
});
void errorOnly.mutate({ a: 'x' });

// A zod-style parser fixes T from its return type.
declare const schema: { parse: (input: unknown) => Dto };
const parsed = client.define({
  key: 'zod',
  request: (_vars: { a: string }) => ({ url: 'https://x', data: null }),
  response: schema.parse,
  onSuccess: (_data) => {
    assertEqual<typeof _data, Dto>(true);
  },
});
void parsed.mutate({ a: 'x' });

// An async parser is typed honestly: onSuccess sees the Promise.
client.define({
  key: 'async.parser',
  request: (_vars: { a: string }) => ({ url: 'https://x', data: null }),
  response: async (raw) => raw as Dto,
  onSuccess: (_data) => {
    assertEqual<typeof _data, Promise<Dto>>(true);
  },
});

// A request that takes no vars gives mutate() no arguments, and rejects a
// stray value.
const noVars = client.define({
  key: 'no.vars',
  request: () => ({ url: 'https://x', data: null }),
  onSuccess: (_data, _vars) => {
    assertEqual<typeof _data, RawResponse>(true);
    assertEqual<typeof _vars, null>(true);
  },
});
void noVars.mutate();
void noVars.mutate(null);
void noVars.mutate(undefined, { id: 'fixed' });
// @ts-expect-error a no-vars definition takes no vars
void noVars.mutate('anything goes');
// @ts-expect-error a no-vars definition takes no vars
void noVars.mutate({ arbitrary: [1, 2, 3] });

// Generated API types pass as they are: an interface with optional fields, a
// readonly array, a field typed `object`, a nullable string, a nested DTO.
interface InterfaceVars {
  siteId: string;
  title?: string | null;
}
const interfaceVars = client.define({
  key: 'interface.vars',
  request: (_vars: InterfaceVars) => ({ url: 'https://x', data: null }),
});
void interfaceVars.mutate({ siteId: 's' });
void interfaceVars.mutate({ siteId: 's', title: null });
const readonlyVars = client.define({
  key: 'readonly.vars',
  request: (_vars: { readonly ids: readonly string[] }) => ({
    url: 'https://x',
    data: null,
  }),
});
void readonlyVars.mutate({ ids: ['a'] });
interface UpsertDto {
  valuesToUpsert: Array<{ propertyKey: string; value?: object }>;
  title?: string | null;
}
type UpsertRequest = { readonly siteId: string; readonly body: UpsertDto };
const upsert = client.define({
  key: 'upsert.vars',
  request: ({ siteId, body }: UpsertRequest) => ({
    url: `https://x/${siteId}`,
    data: body,
  }),
  onSuccess: (_data, _vars) => {
    assertEqual<typeof _vars, UpsertRequest>(true);
  },
});
void upsert.mutate({
  siteId: 's',
  body: { valuesToUpsert: [{ propertyKey: 'k', value: { any: 1 } }] },
});
// Aliases with optional fields, nested aliases and arrays pass too.
type NestedVars = { inner: { b: number }; ids: string[]; note?: string };
const nested = client.define({
  key: 'nested.vars',
  request: (_vars: NestedVars) => ({ url: 'https://x', data: null }),
});
void nested.mutate({ inner: { b: 1 }, ids: ['x'] });

// The client's define is the same overloaded signature.
declare const define: typeof client.define;
expectType<typeof client.define>(define);
