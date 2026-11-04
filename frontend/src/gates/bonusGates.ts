import type { BonusDeclarationView } from '@/api/types';
import type { Gate } from './types';
import { mayActAlone, SIGNING_OWN } from '@/lib/ownWork';

/**
 * What the platform will refuse before a bonus declaration is approved. The wording is
 * Declaration.java's, so a gate and the 422 a person would otherwise meet say the same thing. An
 * unknown viewer is never treated as the proposer, for the reason accumulationGates gives.
 */

const isSame = (viewer: string | undefined, other: string | null | undefined) =>
  viewer !== undefined && other != null && viewer === other;

export function approveDeclarationGates(declaration: BonusDeclarationView, viewerSubject: string | undefined): Gate[] {
  const proposed = declaration.status === 'PROPOSED';
  const own = isSame(viewerSubject, declaration.proposedBy);
  const samePerson = own && !mayActAlone('declarations');
  return [
    {
      ok: proposed,
      hard: true,
      title: 'Awaiting approval',
      detail: proposed
        ? 'Proposed, not yet approved.'
        : `This bonus declaration is ${declaration.status.toLowerCase()}, not awaiting approval`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person approves',
      detail: samePerson
        ? 'A bonus declaration must be approved by someone other than the person who proposed it'
        : own ? SIGNING_OWN : 'You did not propose this declaration.',
    },
  ];
}
