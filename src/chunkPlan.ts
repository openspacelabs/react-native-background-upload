export type ChunkPlanOptions = {
  /** The smallest chunk that the server accepts in a multi-part upload. */
  min?: number;
  /** The target chunk size for the greedy walk. */
  max?: number;
};

const DEFAULT_MIN = 8 * 2 ** 20;
const DEFAULT_MAX = 20 * 2 ** 20;

/**
 * Splits `sizeBytes` into contiguous, end-exclusive ranges that cover
 * [0, size). The walk is greedy and deterministic: it emits `max`-sized
 * chunks. When the final remainder is smaller than `min`, the walk absorbs it
 * into the previous chunk. Thus the last chunk can hold up to `max + min - 1`
 * bytes. A file smaller than `min` is a single chunk, because the server's
 * minimum applies to the parts of a multi-part upload, not to the whole file.
 *
 * The function is pure and deterministic on purpose. The consumer calls it
 * one time, and derives the create request's part count and the `parts` array
 * from the same result. Thus the two can never disagree.
 */
export const chunkPlan = (
  sizeBytes: number,
  opts: ChunkPlanOptions = {},
): Array<{ start: number; end: number }> => {
  const min = opts.min ?? DEFAULT_MIN;
  const max = opts.max ?? DEFAULT_MAX;
  if (!Number.isInteger(sizeBytes) || sizeBytes <= 0) {
    throw new Error(
      `chunkPlan: sizeBytes must be a positive integer, got ${sizeBytes}`,
    );
  }
  if (!Number.isInteger(min) || min < 1) {
    throw new Error(`chunkPlan: min must be a positive integer, got ${min}`);
  }
  if (!Number.isInteger(max) || max < min) {
    throw new Error(`chunkPlan: max must be an integer >= min, got ${max}`);
  }

  const ranges: Array<{ start: number; end: number }> = [];
  let start = 0;
  while (sizeBytes - start >= max) {
    ranges.push({ start, end: start + max });
    start += max;
  }
  const remainder = sizeBytes - start;
  if (remainder > 0) {
    const last = ranges[ranges.length - 1];
    if (remainder < min && last) {
      last.end = sizeBytes;
    } else {
      ranges.push({ start, end: sizeBytes });
    }
  }
  return ranges;
};
