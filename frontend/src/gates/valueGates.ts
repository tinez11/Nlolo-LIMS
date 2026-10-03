import type { PolicyView, SurrenderQuote, SurrenderRequestView } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { humanizeStatus } from '@/lib/status';
import type { Gate } from './types';

/**
 * What the platform will refuse before a surrender or a paid-up conversion -- and nothing it
 * cannot prove from what this page holds.
 *
 * The wording comes from `PolicyApiImpl.requestSurrender` and `makePaidUp`, so a gate and the
 * 409 a person would otherwise meet say the same thing. Two server rules are deliberately NOT
 * gates: a policy loan outstanding and the product's minimum years. Neither figure is on
 * `PolicyView` -- the loan balance lives in `policyloan` and the minimum in the product version --
 * so a gate asserting either would be inventing a judgement, which is exactly what the gate
 * doctrine forbids. The submit surfaces them.
 */

/** Policy.canSurrender. */
const SURRENDERABLE: readonly string[] = ['ACTIVE', 'REINSTATED', 'PAID_UP', 'LAPSED'];
/** Policy.canMakePaidUp. */
const CONVERTIBLE: readonly string[] = ['ACTIVE', 'REINSTATED', 'LAPSED'];
/** A surrender already in flight -- the partial unique index allows only one. */
const IN_FLIGHT: readonly string[] = ['REQUESTED', 'APPROVED'];

const isPositive = (amount: string | undefined) => Number(amount ?? '0') > 0;
/** An absent status is the em dash this console uses everywhere for a value the API did not give. */
const label = (status: string | undefined) => (status ? humanizeStatus(status) : '—');

export function surrenderGates(
  policy: PolicyView | null,
  quote: SurrenderQuote | null,
  latestRequest: SurrenderRequestView | null,
): Gate[] {
  if (!policy) return [];
  const status = policy.status;
  const live = latestRequest && IN_FLIGHT.includes(latestRequest.status ?? '');
  // PolicyApiImpl.requestSurrender: a policy whose only surrender value is its attached bonuses
  // still has one (product step 4), so either figure being positive passes.
  const hasCash = isPositive(policy.cashValue?.amount);
  const hasBonusValue = isPositive(quote?.bonusSurrenderValue?.amount);
  const gates: Gate[] = [
    {
      ok: SURRENDERABLE.includes(status ?? ''),
      hard: true,
      title: 'A surrenderable status',
      detail: SURRENDERABLE.includes(status ?? '')
        ? `${label(status)} — this policy can be surrendered.`
        : `This policy is ${label(status)}. Only an in-force, paid-up or lapsed policy can be surrendered.`,
    },
    {
      ok: hasCash || hasBonusValue,
      hard: true,
      title: 'The policy has cash value',
      detail: hasCash
        ? `${formatMoney(policy.cashValue)} of cash value stands behind it.`
        : hasBonusValue
          ? `No cash value, but its attached bonuses are worth ${formatMoney(quote?.bonusSurrenderValue)} on surrender.`
          : 'This policy has no cash value to surrender.',
    },
    {
      ok: !live,
      hard: true,
      title: 'No surrender in flight',
      detail: live
        ? `A surrender is already ${label(latestRequest?.status).toLowerCase()} on this policy.`
        : 'Nothing else is in flight.',
    },
  ];
  // Only once the quote is in: before that there is nothing to say, and a gate that failed
  // while loading would read as a refusal.
  if (quote) {
    gates.push({
      ok: isPositive(quote.quotedValue?.amount),
      hard: true,
      title: 'Something is left after charges',
      detail: isPositive(quote.quotedValue?.amount)
        ? `${formatMoney(quote.quotedValue)} would be paid.`
        : `The quote is ${formatMoney(quote.quotedValue)} after the surrender charge, so there is nothing to pay.`,
    });
  }
  return gates;
}

export function approveSurrenderGates(
  request: SurrenderRequestView | null,
  viewerSubject: string | undefined,
): Gate[] {
  if (!request) return [];
  const sameAsRequester = viewerSubject !== undefined && viewerSubject === request.requestedBy;
  return [
    {
      ok: request.status === 'REQUESTED',
      hard: true,
      title: 'Awaiting approval',
      detail: request.status === 'REQUESTED'
        ? `Requested by ${request.requestedBy}.`
        : `This surrender is ${label(request.status)}, not awaiting approval.`,
    },
    {
      ok: !sameAsRequester,
      hard: true,
      title: 'A second person approves',
      detail: sameAsRequester
        ? `A surrender must be approved by someone other than the person who requested it (${request.requestedBy}).`
        : 'You did not request this surrender.',
    },
  ];
}

export function paidUpGates(policy: PolicyView | null): Gate[] {
  if (!policy) return [];
  const status = policy.status;
  return [
    {
      ok: CONVERTIBLE.includes(status ?? ''),
      hard: true,
      title: 'A convertible status',
      detail: CONVERTIBLE.includes(status ?? '')
        ? `${label(status)} — this policy can be made paid-up.`
        : `This policy is ${label(status)}. Only an active, reinstated or lapsed policy can be made paid-up.`,
    },
    {
      ok: isPositive(policy.cashValue?.amount),
      hard: true,
      title: 'The policy has value',
      detail: isPositive(policy.cashValue?.amount)
        ? `${formatMoney(policy.cashValue)} of value stands behind the reduced cover.`
        : 'This policy has no value yet, so there is no reduced cover to keep.',
    },
    {
      // Soft, and it has to be: the premiums paid and the product's minimum are both off this
      // page, so this states the rule without claiming to have applied it.
      ok: true,
      hard: false,
      title: 'Minimum years is checked on submit',
      detail:
        'A savings product pays no value until its first two or three full years are paid. The server checks this policy against its own product and refuses if it falls short.',
    },
  ];
}
