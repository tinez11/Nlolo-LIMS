import type { PayoutInstalmentView } from '@/api/types';
import { humanizeStatus } from '@/lib/status';
import type { Gate } from './types';

/**
 * What the platform will refuse before a payout is reviewed, approved or retried -- and nothing it
 * cannot prove from the instalment in hand.
 *
 * The wording is copied from `PayoutInstalment`'s own `PayoutStateException` messages, so a gate
 * and the 422 a person would otherwise meet say the same thing. One server rule is deliberately
 * NOT a gate: whether the policy is still payable on the due date. That is decided when the
 * instalment falls due, and by the time a reviewer sees it the answer is already written into
 * `status` and `statusReason` -- re-deriving it here from a PolicyView would be a second opinion
 * that could disagree with the record.
 */

const label = (status: string) => humanizeStatus(status);

export function reviewGates(payout: PayoutInstalmentView): Gate[] {
  const gates: Gate[] = [];

  if (payout.status === 'ON_HOLD') {
    // The server already said WHY, in words written for a person. Paraphrasing it here would
    // give a clerk two different explanations of the same hold.
    gates.push({
      ok: false,
      hard: true,
      title: 'This payout is on hold',
      detail: payout.statusReason ?? 'It is on hold and cannot be reviewed until that is cleared.',
    });
  } else {
    gates.push({
      ok: payout.status === 'DUE',
      hard: true,
      title: 'Awaiting review',
      detail:
        payout.status === 'DUE'
          ? 'Due, and not yet reviewed.'
          : `This payout is ${label(payout.status)}, so it cannot be reviewed.`,
    });
  }

  // Not a failure -- a requirement. It passes `ok` because the reviewer is about to supply it;
  // the form enforces it. Showing it as a gate is how the reviewer learns it is needed BEFORE
  // filling the rest in.
  if (payout.kind === 'SURVIVAL' || payout.kind === 'INCOME') {
    gates.push({
      ok: true,
      hard: true,
      title: 'Proof of life is required',
      detail: `A ${label(payout.kind).toLowerCase()} payout needs proof that the life assured is alive.`,
    });
  }

  return gates;
}

export function approveGates(payout: PayoutInstalmentView, viewerSubject: string | undefined): Gate[] {
  // An unknown viewer is NOT treated as the reviewer. Comparing two undefineds would refuse every
  // approval on a token with no subject; comparing undefined against a real reviewer passes, and
  // the server still enforces the rule.
  const sameAsReviewer = viewerSubject !== undefined && viewerSubject === payout.reviewedBy;

  return [
    {
      ok: payout.status === 'REVIEWED',
      hard: true,
      title: 'Reviewed',
      detail:
        payout.status === 'REVIEWED'
          ? `Reviewed by ${payout.reviewedBy ?? 'someone else'}.`
          : `This payout is ${label(payout.status)}, so it cannot be approved.`,
    },
    {
      ok: !sameAsReviewer,
      hard: true,
      title: 'A second person approves',
      detail: sameAsReviewer
        ? `A payout must be approved by someone other than the person who reviewed it (${payout.reviewedBy})`
        : 'You did not review this payout.',
    },
  ];
}

export function retryGates(payout: PayoutInstalmentView): Gate[] {
  if (payout.status === 'IN_DOUBT') {
    return [
      {
        ok: false,
        hard: true,
        title: 'This payout cannot be retried',
        detail:
          'The money may or may not have moved. Reconcile it against the provider’s statement first — '
          + 'retrying an in-doubt payout is how a customer is paid twice.',
      },
    ];
  }
  return [
    {
      ok: payout.status === 'FAILED',
      hard: true,
      title: 'The payment failed',
      detail:
        payout.status === 'FAILED'
          ? `Attempt ${payout.attempts} failed and no money moved, so it can be tried again.`
          : `This payout is ${label(payout.status)}, so there is nothing to retry.`,
    },
  ];
}
