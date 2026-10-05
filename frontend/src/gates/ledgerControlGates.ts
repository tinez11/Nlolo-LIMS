import type { AccountingPeriodView, PolicyElectionView } from '@/api/types';
import type { Gate } from './types';

/**
 * What the platform refuses before a second person decides an accounting policy election or a period's
 * reopening (IFRS 17 I1). The wording is PolicyElection.java's and AccountingPeriod.java's. An unknown
 * viewer is never treated as the proposer, for the reason accumulationGates gives.
 */

const isSame = (viewer: string | undefined, other: string | null | undefined) =>
  viewer !== undefined && other != null && viewer === other;

export function decideElectionGates(election: PolicyElectionView, viewerSubject: string | undefined): Gate[] {
  const samePerson = isSame(viewerSubject, election.proposedBy);
  return [
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person approves',
      detail: samePerson
        ? 'A second person approves an accounting policy election'
        : 'You did not propose this election.',
    },
  ];
}

export function approveReopenGates(period: AccountingPeriodView, viewerSubject: string | undefined): Gate[] {
  const samePerson = isSame(viewerSubject, period.reopenRequestedBy);
  return [
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person approves',
      detail: samePerson ? 'A second person approves reopening a period' : 'You did not ask for this reopening.',
    },
  ];
}
