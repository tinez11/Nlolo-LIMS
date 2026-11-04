import type { WithholdingRuleView } from '@/api/types';
import type { Gate } from './types';
import { mayActAlone, SIGNING_OWN } from '@/lib/ownWork';

/**
 * What the platform will refuse before a withholding rule is approved. The wording is
 * WithholdingRule.java's, so a gate and the 422 a person would otherwise meet say the same thing. An
 * unknown viewer is never treated as the proposer, for the reason accumulationGates gives.
 */

const isSame = (viewer: string | undefined, other: string | null | undefined) =>
  viewer !== undefined && other != null && viewer === other;

export function approveRuleGates(rule: WithholdingRuleView, viewerSubject: string | undefined): Gate[] {
  const proposed = rule.status === 'PROPOSED';
  const own = isSame(viewerSubject, rule.proposedBy);
  const samePerson = own && !mayActAlone('payouts');
  return [
    {
      ok: proposed,
      hard: true,
      title: 'Awaiting approval',
      detail: proposed ? 'Proposed, not yet approved.' : `This withholding rule is ${rule.status.toLowerCase()}, not awaiting approval`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person approves',
      detail: samePerson
        ? 'A withholding rule must be approved by someone other than the person who proposed it'
        : own ? SIGNING_OWN : 'You did not propose this rule.',
    },
  ];
}
