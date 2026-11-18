// Type-level tests for attempts in doubt and an accepted response whose body
// is unknown. tsc compiles this file with the library (yarn typecheck);
// nothing here runs.
import type {
  AttemptEvent,
  DefinitionWithoutResponse,
  DefinitionWithResponse,
  Meta,
  OutcomeError,
  RawResponse,
  UploadClient,
} from '../types';
import type { FakeOutcome, SettledOverrides } from '../testing';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B
  ? 1
  : 2
  ? true
  : false;
const assertEqual = <A, B>(
  _proof: Equal<A, B> extends true ? true : never,
) => {};

const expectType = <Expected>(_actual: Expected): void => {};

declare const client: UploadClient;

// Meta always has the count. AttemptEvent may say how much body left.
assertEqual<Meta['ambiguousAttempts'], number>(true);
assertEqual<AttemptEvent['bodySent'], 'none' | 'partial' | 'full' | undefined>(
  true,
);
client.addListener('attempt', (e) => {
  expectType<'none' | 'partial' | 'full' | undefined>(e.bodySent);
});
// The flag is true or absent, and separate from bodyTruncated.
assertEqual<RawResponse['bodyUnknown'], true | undefined>(true);
assertEqual<RawResponse['bodyTruncated'], boolean>(true);
const flagged: RawResponse = {
  status: 201,
  bodyTruncated: false,
  bodyUnknown: true,
};
void flagged;
// @ts-expect-error bodyUnknown is true or absent, never false
const unflagged: RawResponse = { bodyTruncated: false, bodyUnknown: false };
void unflagged;

// A definition with a parser handles the case in onBodyUnknown, which gets
// the raw response, the vars and the meta.
type Dto = { id: string };
const create = client.define({
  key: 'note.create',
  request: (_vars: { noteId: string }) => ({ url: 'https://x', data: {} }),
  response: (body) => body as Dto,
  onSuccess: (_data) => {
    assertEqual<typeof _data, Dto>(true);
  },
  onBodyUnknown: (_response, _vars, _meta) => {
    assertEqual<typeof _response, RawResponse>(true);
    assertEqual<typeof _vars, { noteId: string }>(true);
    assertEqual<typeof _meta, Meta>(true);
  },
  onError: (_error) => {
    assertEqual<typeof _error, OutcomeError>(true);
  },
});
void create.mutate({ noteId: 'n' });
// An async onBodyUnknown is allowed, as for the other handlers.
client.define({
  key: 'note.create.async',
  request: (_vars: null) => ({ url: 'https://x', data: {} }),
  response: (body) => body as Dto,
  onBodyUnknown: async () => undefined,
});
// onBodyUnknown checks the vars type.
const wrongVars: DefinitionWithResponse<{ noteId: string }, Dto> = {
  key: 'note.create.wrong.vars',
  request: (_vars) => ({ url: 'https://x', data: {} }),
  response: (body) => body as Dto,
  // @ts-expect-error the handler's vars must match the request's
  onBodyUnknown: (_response: RawResponse, _vars: { other: number }) =>
    undefined,
};
void wrongVars;
// define() reports the same mismatch at the call, because no overload fits.
// @ts-expect-error the handler's vars must match the request's
client.define({
  key: 'note.create.wrong.vars.call',
  request: (_vars: { noteId: string }) => ({ url: 'https://x', data: {} }),
  response: (body) => body as Dto,
  onBodyUnknown: (_response: RawResponse, _vars: { other: number }) =>
    undefined,
});
// A definition without a parser gets the flag in onSuccess, so it takes no
// onBodyUnknown.
client.define({
  key: 'note.create.raw',
  request: (_vars: null) => ({ url: 'https://x', data: {} }),
  onSuccess: (response) => {
    expectType<true | undefined>(response.bodyUnknown);
  },
});
const rawWithHandler: DefinitionWithoutResponse<null> = {
  key: 'note.create.raw.handler',
  request: () => ({ url: 'https://x', data: {} }),
  // @ts-expect-error onBodyUnknown needs a response parser
  onBodyUnknown: () => undefined,
};
void rawWithHandler;
// @ts-expect-error onBodyUnknown needs a response parser
client.define({
  key: 'note.create.raw.handler.call',
  request: (_vars: null) => ({ url: 'https://x', data: {} }),
  onBodyUnknown: () => undefined,
});

// The fake native can settle the case and set the count.
const settledUnknown: FakeOutcome = {
  kind: 'completed',
  response: { status: 201, bodyUnknown: true },
};
void settledUnknown;
const overrides: SettledOverrides = { ambiguousAttempts: 2 };
void overrides;
