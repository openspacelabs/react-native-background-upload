// Type-level tests for the fields the library no longer uses. tsc compiles
// this file (yarn typecheck); nothing here runs. The library has no
// deadlines, but these stay in the types until the next major release, so
// an app written for 11.3.0 compiles with no change.
import type {
  ConfigureOptions,
  ErrorKind,
  OutcomeError,
  RequestDescriptor,
} from '../types';

type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B
  ? 1
  : 2
  ? true
  : false;
const assertEqual = <A, B>(
  _proof: Equal<A, B> extends true ? true : never,
) => {};

// A descriptor that sets expiresAt still compiles. The value is ignored.
export const timed: RequestDescriptor = {
  url: 'https://api/x',
  data: {},
  expiresAt: Date.now() + 60_000,
};

// configure() with lifetimeMs still compiles. The value is ignored.
export const options: ConfigureOptions = { lifetimeMs: 10 * 365 * 86_400_000 };

// 'expired' stays in ErrorKind: an outcome that an earlier version journaled
// still reaches onError with it, so an exhaustive switch keeps its case.
assertEqual<
  ErrorKind,
  'http' | 'network' | 'file' | 'expired' | 'truncated' | 'unknown'
>(true);

export const label = (error: OutcomeError): string => {
  switch (error.errorKind) {
    case 'http':
    case 'network':
    case 'file':
    case 'truncated':
    case 'unknown':
      return error.errorKind;
    case 'expired':
      return 'expired under an earlier version';
  }
};
