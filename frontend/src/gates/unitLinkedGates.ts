import type { FundPriceView, FundView, PriceAdjustmentView } from '@/api/types';
import type { Gate } from './types';

/**
 * What the platform will refuse before a fund price is approved or an adjustment decided (product
 * step 6), in FundRegister's and PriceCorrectionAdjustment's words. The cut-off is the fund's, in East
 * Africa Time: a price cannot be approved before its date's cut-off has passed, because orders received
 * up to the cut-off are priced by it. An unknown viewer is never treated as the proposer.
 */

const isSame = (viewer: string | undefined, other: string | null | undefined) =>
  viewer !== undefined && other != null && viewer === other;

/** The civil date and time now in Dar es Salaam, as `YYYY-MM-DD` and `HH:mm:ss`. */
export function eatNow(now: Date = new Date()): { date: string; time: string } {
  const eat = new Date(now.getTime() + 3 * 60 * 60 * 1000).toISOString();
  return { date: eat.slice(0, 10), time: eat.slice(11, 19) };
}

/** Whether a valuation date's cut-off has passed: an earlier date always has; today's once the time is reached. */
export function cutOffPassed(valuationDate: string, cutOffTime: string, now: Date = new Date()): boolean {
  const { date, time } = eatNow(now);
  if (valuationDate < date) return true;
  if (valuationDate > date) return false;
  return time >= cutOffTime.slice(0, 8).padEnd(8, ':00');
}

export function approvePriceGates(
  price: FundPriceView,
  fund: FundView | undefined,
  viewerSubject: string | undefined,
  now: Date = new Date(),
): Gate[] {
  const proposed = price.status === 'PROPOSED';
  const samePerson = isSame(viewerSubject, price.proposedBy);
  const passed = fund ? cutOffPassed(price.valuationDate, fund.cutOffTime, now) : true;
  return [
    {
      ok: proposed,
      hard: true,
      title: 'Awaiting approval',
      detail: proposed ? 'Proposed, not yet approved.' : `This price is ${price.status.toLowerCase()}, not awaiting approval`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person approves',
      detail: samePerson
        ? 'A fund price must be approved by someone other than the person who proposed it'
        : 'You did not propose this price.',
    },
    {
      ok: passed,
      hard: true,
      title: 'The cut-off has passed',
      detail: passed
        ? 'Every order this price can execute has been received.'
        : `Orders for ${price.valuationDate} are still being received until ${fund?.cutOffTime ?? 'the cut-off'}; the price cannot be approved before then`,
    },
  ];
}

export function decideAdjustmentGates(adjustment: PriceAdjustmentView, viewerSubject: string | undefined): Gate[] {
  const open = adjustment.status === 'OPEN';
  const samePerson = isSame(viewerSubject, adjustment.proposedBy);
  return [
    {
      ok: open,
      hard: true,
      title: 'Not yet decided',
      detail: open ? 'Waiting to be settled or waived.' : `This adjustment is ${adjustment.status.toLowerCase()}`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person decides',
      detail: samePerson
        ? 'This adjustment arose from a correction you approved; a second person settles or waives it'
        : 'You did not approve the correction that raised it.',
    },
  ];
}
