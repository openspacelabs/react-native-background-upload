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
// Also when the definition is built first, where no excess-property check
// applies: the handler would never run, so it does not compile.
const builtFirst = {
  key: 'note.create.raw.built',
  request: (_vars: null) => ({ url: 'https://x', data: {} }),
  onBodyUnknown: () => undefined,
};
// @ts-expect-error onBodyUnknown needs a response parser
client.define(builtFirst);

// The fake native can settle the case and set the count.
const settledUnknown: FakeOutcome = {
  kind: 'completed',
  response: { status: 201, bodyUnknown: true },
};
void settledUnknown;
const overrides: SettledOverrides = { ambiguousAttempts: 2 };
void overrides;

// The README examples for this feature, as written there. A Redux-style
// dispatch returns the action, so a handler must use a block body to return
// void.
type NoteVars = { noteId: string; siteId: string; note: { text: string } };
type Note = { id: string; text: string };
type Action = { type: string };
declare const store: { dispatch: <A extends Action>(action: A) => A };
declare const notesUrl: (vars: NoteVars) => string;
declare const noteSchema: { parse: (input: unknown) => Note };
declare const noteSaved: (note: Note) => Action;
declare const fetchNote: (noteId: string) => Action;
declare const analytics: {
  track: (name: string, props: Record<string, unknown>) => void;
};
declare const uploads: UploadClient;

const createNote = uploads.define({
  key: 'note.create',
  request: (vars: NoteVars) => ({ url: notesUrl(vars), data: vars.note }),
  response: (raw) => noteSchema.parse(raw),
  onSuccess: (note) => {
    store.dispatch(noteSaved(note));
  },
  // The server has the note, but its answer was lost. Fetch it.
  onBodyUnknown: (_response, vars) => {
    store.dispatch(fetchNote(vars.noteId));
  },
});
void createNote;
// The expression-body form the README used before does not compile.
uploads.define({
  key: 'note.create.expression',
  request: (vars: NoteVars) => ({ url: notesUrl(vars), data: vars.note }),
  response: (raw) => noteSchema.parse(raw),
  // @ts-expect-error a handler returns void or a Promise, not the action
  onSuccess: (note) => store.dispatch(noteSaved(note)),
});

uploads.define({
  key: 'note.count',
  request: (_vars: null) => ({ url: 'https://x' }),
  onSuccess: (data, vars, meta) => {
    if (meta.ambiguousAttempts > 0) {
      analytics.track('upload sent in doubt', {
        key: meta.key,
        count: meta.ambiguousAttempts,
      });
    }
  },
});
