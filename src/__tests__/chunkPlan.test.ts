import { chunkPlan } from '../chunkPlan';

const MB = 2 ** 20;
const MIN = 8 * MB;
const MAX = 20 * MB;

const assertContiguousCovering = (
  ranges: Array<{ start: number; end: number }>,
  size: number,
) => {
  expect(ranges.length).toBeGreaterThan(0);
  expect(ranges[0].start).toBe(0);
  expect(ranges[ranges.length - 1].end).toBe(size);
  for (let i = 1; i < ranges.length; i++) {
    expect(ranges[i].start).toBe(ranges[i - 1].end);
  }
};

describe('chunkPlan', () => {
  it('is deterministic', () => {
    expect(chunkPlan(137 * MB + 3)).toEqual(chunkPlan(137 * MB + 3));
  });

  it('walks greedy max-size chunks, contiguous and covering', () => {
    const size = 100 * MB;
    const ranges = chunkPlan(size);
    assertContiguousCovering(ranges, size);
    expect(ranges).toHaveLength(5);
    ranges.forEach((r) => expect(r.end - r.start).toBe(MAX));
  });

  it('an exact multiple of max yields equal chunks with no absorption', () => {
    const ranges = chunkPlan(60 * MB);
    expect(ranges).toEqual([
      { start: 0, end: 20 * MB },
      { start: 20 * MB, end: 40 * MB },
      { start: 40 * MB, end: 60 * MB },
    ]);
  });

  it('absorbs a sub-min tail into the previous chunk', () => {
    const size = 40 * MB + (MIN - 1);
    const ranges = chunkPlan(size);
    assertContiguousCovering(ranges, size);
    expect(ranges).toHaveLength(2);
    // The documented ceiling: the last chunk can reach max + min - 1.
    expect(ranges[1].end - ranges[1].start).toBe(MAX + MIN - 1);
  });

  it('keeps a tail of exactly min as its own chunk', () => {
    const size = 40 * MB + MIN;
    const ranges = chunkPlan(size);
    assertContiguousCovering(ranges, size);
    expect(ranges).toHaveLength(3);
    expect(ranges[2]).toEqual({ start: 40 * MB, end: size });
  });

  it('a file smaller than min is a single chunk', () => {
    expect(chunkPlan(5 * MB)).toEqual([{ start: 0, end: 5 * MB }]);
    expect(chunkPlan(1)).toEqual([{ start: 0, end: 1 }]);
  });

  it('throws on size 0 and other non-positive-integer sizes', () => {
    expect(() => chunkPlan(0)).toThrow(/positive integer/);
    expect(() => chunkPlan(-1)).toThrow(/positive integer/);
    expect(() => chunkPlan(1.5)).toThrow(/positive integer/);
    expect(() => chunkPlan(NaN)).toThrow(/positive integer/);
  });

  it('throws on nonsensical min/max', () => {
    expect(() => chunkPlan(100, { min: 0, max: 20 })).toThrow(/min/);
    expect(() => chunkPlan(100, { min: 8, max: 4 })).toThrow(/max/);
  });

  // A scaled-down min/max sweep over every size from 1 byte to 10×max. It
  // shows that the shape properties hold at every boundary, not only at the
  // sampled sizes above.
  it('every plan is contiguous, covering, and never has a tiny part', () => {
    const min = 8;
    const max = 20;
    for (let size = 1; size <= 200; size++) {
      const ranges = chunkPlan(size, { min, max });
      assertContiguousCovering(ranges, size);
      ranges.forEach((r, i) => {
        const len = r.end - r.start;
        expect(len).toBeGreaterThan(0);
        expect(len).toBeLessThanOrEqual(max + min - 1);
        // In a multi-part plan, every part meets the server minimum. Only a
        // whole file smaller than min can be under it, as its only part.
        if (ranges.length > 1) {
          expect(len).toBeGreaterThanOrEqual(min);
        }
        if (i < ranges.length - 1) {
          expect(len).toBe(max);
        }
      });
    }
  });
});
