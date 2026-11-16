// Type-level tests for the row hooks. tsc compiles this file with the library
// (yarn typecheck); nothing here runs.
import type {
  DefinedRequestRow,
  RequestRow,
  RequestsFilter,
  UploadClient,
} from '../types';

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

// The client hooks take the getRequests() filter plus includeCancelled.
const rows = client.useRequests();
assertEqual<typeof rows, readonly RequestRow[]>(true);
client.useRequests({ key: 'k', id: 'i', includeCancelled: true });
client.useRequestsWithProgress({ key: 'k' });
// @ts-expect-error unknown filter field
client.useRequests({ keys: ['k'] });
// @ts-expect-error includeCancelled is a boolean
client.useRequests({ includeCancelled: 'yes' });

// The array is shared with every other reader, so it is read-only.
// @ts-expect-error push does not exist on a readonly array
rows.push(rows[0]!);

// Outside React.
expectType<readonly RequestRow[]>(client.getCachedRequests({ key: 'k' }));
const subscription = client.addRequestsListener((current) => {
  expectType<readonly RequestRow[]>(current);
});
subscription.remove();
const filter: RequestsFilter = { includeCancelled: false };
client.getCachedRequests(filter);

// A definition's hooks type the vars, and take no key.
type CaptureVars = { captureId: string; index: number };
const capture = client.define({
  key: 'capture.upload',
  request: ({ captureId }: CaptureVars) => ({
    url: `https://api/captures/${captureId}`,
  }),
});
const captureRows = capture.useRequests();
assertEqual<typeof captureRows, readonly DefinedRequestRow<CaptureVars>[]>(
  true,
);
const first = captureRows[0];
if (first) {
  assertEqual<typeof first.vars, CaptureVars>(true);
  assertEqual<typeof first.state, RequestRow['state']>(true);
}
capture.useRequests({ id: 'x', includeCancelled: true });
capture.useRequestsWithProgress({ id: 'x' });
// @ts-expect-error the key is the definition's
capture.useRequests({ key: 'other' });

// A definition with a response parser types its rows the same way.
const parsed = client.define({
  key: 'note.add',
  request: (_vars: { noteId: string }) => ({ url: 'https://api/notes' }),
  response: (raw) => raw as { id: string },
});
expectType<readonly DefinedRequestRow<{ noteId: string }>[]>(
  parsed.useRequestsWithProgress(),
);

// A definition without vars has rows whose vars are null.
const ping = client.define({
  key: 'ping',
  request: () => ({ url: 'https://api/ping' }),
});
expectType<readonly DefinedRequestRow<null>[]>(ping.useRequests());
