import type { AccountView, AdjustmentView, RateDeclarationView, WithdrawalView } from '@/api/types';
import type { Gate } from './types';

/**
 * What the platform will refuse before money moves on a savings account -- and nothing it cannot
 * prove from what is in hand.
 *
 * The wording is copied from AccumulationApiImpl's AccumulationStateException messages, so a gate
 * and the 422 a person would otherwise meet say the same thing. The minimum-balance rule is
 * deliberately NOT a gate: it needs the loan lien, which this page does not hold, and a second
 * opinion that disagreed with the server would be worse than the server's own refusal shown in its
 * own words.
 *
 * An unknown viewer is never treated as the requester, for the reason payoutGates gives: comparing
 * two undefineds would refuse every approval on a token with no subject, and the server enforces
 * the rule regardless.
 */

const isSame = (viewer: string | undefined, other: string | null | undefined) =>
  viewer !== undefined && other != null && viewer === other;

export function requestWithdrawalGates(account: AccountView, withdrawals: WithdrawalView[]): Gate[] {
  const open = account.status === 'OPEN';
  const inFlight = withdrawals.some((w) => w.status === 'REQUESTED' || w.status === 'APPROVED');
  return [
    {
      ok: open,
      hard: true,
      title: 'The account is open',
      detail: open ? 'Open.' : `The account is closed (${account.closedReason ?? 'closed'}).`,
    },
    {
      ok: !inFlight,
      hard: true,
      title: 'No other withdrawal in flight',
      detail: inFlight ? `A withdrawal is already in flight on policy ${account.policyNumber}` : 'None in flight.',
    },
  ];
}

export function approveWithdrawalGates(withdrawal: WithdrawalView, viewerSubject: string | undefined): Gate[] {
  const requested = withdrawal.status === 'REQUESTED';
  const samePerson = isSame(viewerSubject, withdrawal.requestedBy);
  return [
    {
      ok: requested,
      hard: true,
      title: 'Awaiting approval',
      detail: requested ? 'Requested, not yet approved.' : `This withdrawal is ${withdrawal.status.toLowerCase()}.`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person approves',
      detail: samePerson
        ? 'A withdrawal must be approved by someone other than the person who requested it'
        : 'You did not request this withdrawal.',
    },
  ];
}

export function decideAdjustmentGates(adjustment: AdjustmentView, viewerSubject: string | undefined): Gate[] {
  const proposed = adjustment.status === 'PROPOSED';
  const samePerson = isSame(viewerSubject, adjustment.proposedBy);
  return [
    {
      ok: proposed,
      hard: true,
      title: 'Awaiting a decision',
      detail: proposed ? 'Proposed, not yet decided.' : `This adjustment is ${adjustment.status.toLowerCase()}.`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person decides',
      detail: samePerson
        ? 'An adjustment must be decided by someone other than the person who proposed it'
        : 'You did not propose this adjustment.',
    },
  ];
}

export function approveRateGates(declaration: RateDeclarationView, viewerSubject: string | undefined): Gate[] {
  const proposed = declaration.status === 'PROPOSED';
  const samePerson = isSame(viewerSubject, declaration.proposedBy);
  return [
    {
      ok: proposed,
      hard: true,
      title: 'Awaiting approval',
      detail: proposed ? 'Proposed, not yet approved.' : `This rate declaration is ${declaration.status.toLowerCase()}, not awaiting approval`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person approves',
      detail: samePerson
        ? 'A declared rate must be approved by someone other than the person who proposed it'
        : 'You did not propose this rate.',
    },
  ];
}
