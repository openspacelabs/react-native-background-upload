// Type-level tests for RetryPolicy. tsc compiles this file (yarn typecheck);
// nothing here runs.
import type { RetryPolicy, TransientRule } from '../types';

// A full RetryPolicy as an 11.1.0 caller wrote it, with no transient field,
// still compiles. An absent transient means no rules.
export const withoutTransient: RetryPolicy = {
  backoff: { baseMs: 1_000, maxMs: 7_200_000, jitter: 0.2 },
  terminalHttp: { exempt: [404] },
};

export const withTransient: RetryPolicy = {
  ...withoutTransient,
  transient: [{ status: 400, bodyIncludes: 'Bad Request', maxRetries: 3 }],
};

export const rules: TransientRule[] | undefined = withoutTransient.transient;
