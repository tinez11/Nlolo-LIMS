import { describe, expect, it } from 'vitest';
import type { FundPriceView, FundView, PriceAdjustmentView } from '@/api/types';
import { approvePriceGates, cutOffPassed, decideAdjustmentGates } from './unitLinkedGates';

const fund = { code: 'EQ', cutOffTime: '16:00:00' } as FundView;
const price = (over: Partial<FundPriceView> = {}) =>
  ({ priceId: 'p1', fundCode: 'EQ', valuationDate: '2026-10-05', price: '1.000000', status: 'PROPOSED', proposedBy: 'alice', ...over }) as FundPriceView;

/** 2026-10-05 at the given East Africa Time (UTC+3). */
const eat = (time: string) => new Date(`2026-10-05T${time}+03:00`);

describe('the fund cut-off (forward pricing)', () => {
  it('has passed for an earlier valuation date, never for a later one', () => {
    expect(cutOffPassed('2026-10-04', '16:00:00', eat('09:00:00'))).toBe(true);
    expect(cutOffPassed('2026-10-06', '16:00:00', eat('23:00:00'))).toBe(false);
  });

  it("passes for today's date only once the cut-off time is reached, in EAT", () => {
    expect(cutOffPassed('2026-10-05', '16:00:00', eat('15:59:59'))).toBe(false);
    expect(cutOffPassed('2026-10-05', '16:00:00', eat('16:00:00'))).toBe(true);
    expect(cutOffPassed('2026-10-05', '16:00', eat('16:00:01'))).toBe(true);
  });
});

describe('approving a fund price', () => {
  it('is refused to the person who proposed it', () => {
    const gates = approvePriceGates(price(), fund, 'alice', eat('17:00:00'));
    expect(gates.find((g) => g.title === 'A second person approves')?.ok).toBe(false);
  });

  it("is refused before the date's cut-off, and allowed by a second person after it", () => {
    expect(approvePriceGates(price(), fund, 'bob', eat('10:00:00')).every((g) => g.ok)).toBe(false);
    expect(approvePriceGates(price(), fund, 'bob', eat('17:00:00')).every((g) => g.ok)).toBe(true);
  });

  it('never treats an unknown viewer as the proposer', () => {
    expect(approvePriceGates(price(), fund, undefined, eat('17:00:00')).every((g) => g.ok)).toBe(true);
  });
});

describe('deciding a price-correction adjustment', () => {
  const adjustment = { adjustmentId: 'a1', status: 'OPEN', proposedBy: 'carol' } as PriceAdjustmentView;

  it('is refused to whoever approved the correction that raised it', () => {
    expect(decideAdjustmentGates(adjustment, 'carol').some((g) => !g.ok)).toBe(true);
    expect(decideAdjustmentGates(adjustment, 'dave').every((g) => g.ok)).toBe(true);
  });

  it('is refused once decided', () => {
    expect(decideAdjustmentGates({ ...adjustment, status: 'SETTLED' }, 'dave').some((g) => !g.ok)).toBe(true);
  });
});
