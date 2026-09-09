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

// vars must be JSON: no functions, no Dates, no undefined fields.
client.define({
  key: 'bad.vars',
  // @ts-expect-error a function is not Json
  request: (_vars: { cb: () => void }) => ({ url: 'https://x', data: null }),
});
client.define({
  key: 'bad.vars.date',
  // @ts-expect-error a Date is not Json
  request: (_vars: { when: Date }) => ({ url: 'https://x', data: null }),
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

// Known limits of the Json constraint. An interface has no implicit index
// signature, and a readonly array is not a Json[]. Use a type alias with
// mutable arrays. These lines pin the limit so a change to it shows up here.
interface InterfaceVars {
  a: string;
}
client.define({
  key: 'interface.vars',
  // @ts-expect-error an interface does not satisfy Json; use a type alias
  request: (_vars: InterfaceVars) => ({ url: 'https://x', data: null }),
});
client.define({
  key: 'readonly.vars',
  // @ts-expect-error readonly string[] is not a Json[]
  request: (_vars: { ids: readonly string[] }) => ({
    url: 'https://x',
    data: null,
  }),
});
// Aliases with optional fields, nested aliases and mutable arrays pass.
type NestedVars = { inner: { b: number }; ids: string[]; note?: string };
const nested = client.define({
  key: 'nested.vars',
  request: (_vars: NestedVars) => ({ url: 'https://x', data: null }),
});
void nested.mutate({ inner: { b: 1 }, ids: ['x'] });

// The client's define is the same overloaded signature.
declare const define: typeof client.define;
expectType<typeof client.define>(define);
