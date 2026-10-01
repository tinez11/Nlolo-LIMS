import { z } from 'zod';
import type { ProofOfLifeMethod } from '@/api/types';

/**
 * Reviewing one payout: where the money goes, and for a benefit paid to a LIVING person, how that
 * person was confirmed alive.
 *
 * **The schema depends on the payout's kind**, which is why this is a factory rather than a
 * constant. A maturity or premium return is owed by the calendar alone and needs no proof; a
 * survival or income payout does, and `PayoutInstalment.review` refuses it server-side. The screen
 * only renders the field when it is needed, so the required half is the only reachable branch —
 * it is validated anyway, because the rule belongs to the contract and not to which inputs happen
 * to be on screen.
 */

export const PROOF_OF_LIFE_METHODS: readonly { value: ProofOfLifeMethod; label: string }[] = [
  { value: 'IN_PERSON', label: 'Seen in person' },
  { value: 'PHONE_OR_VIDEO', label: 'Phone or video call' },
  { value: 'LIFE_CERTIFICATE', label: 'Life certificate received' },
];

export interface PayoutReviewValues {
  payeeRef: string;
  /** '' is "none chosen", which is valid only when no proof is required. */
  proofOfLifeMethod: '' | ProofOfLifeMethod;
}

export function blankPayoutReview(): PayoutReviewValues {
  return { payeeRef: '', proofOfLifeMethod: '' };
}

export function payoutReviewSchema(needsProofOfLife: boolean) {
  return z
    .object({
      payeeRef: z
        .string()
        .trim()
        .min(1, 'A payout needs a payee reference')
        .max(200, 'A payee reference is at most 200 characters'),
      proofOfLifeMethod: z.enum(['', 'IN_PERSON', 'PHONE_OR_VIDEO', 'LIFE_CERTIFICATE']),
    })
    .refine((values) => !needsProofOfLife || values.proofOfLifeMethod !== '', {
      path: ['proofOfLifeMethod'],
      message: 'Proof that the life assured is alive is required',
    });
}
