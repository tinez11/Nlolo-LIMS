import type { Receipt } from '@/api/receipts';
import type { Gate } from './types';

/**
 * What confirming a recorded receipt asks first (2026-10-27): that it is still waiting, and that the viewer is not
 * the person who recorded it -- the same two-person rule the server and the database both hold.
 */
export function confirmReceiptGates(receipt: Receipt, viewerSubject: string | undefined): Gate[] {
  // An unknown viewer is not treated as the recorder; the server still enforces the rule.
  const sameAsRecorder = viewerSubject !== undefined && viewerSubject === receipt.recordedBy;
  return [
    {
      ok: receipt.status === 'AWAITING_APPROVAL',
      hard: true,
      title: 'Waiting for a decision',
      detail:
        receipt.status === 'AWAITING_APPROVAL'
          ? 'Recorded and not yet confirmed. Nothing is paid until it is.'
          : `This receipt is already ${receipt.status === 'CONFIRMED' ? 'confirmed' : 'refused'}.`,
    },
    {
      ok: !sameAsRecorder,
      hard: true,
      title: 'A second person confirms',
      detail: sameAsRecorder
        ? 'You recorded this receipt, so someone else must confirm it.'
        : 'You did not record this receipt.',
    },
  ];
}
