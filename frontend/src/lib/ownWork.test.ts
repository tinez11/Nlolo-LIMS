import { describe, expect, it } from 'vitest';
import { approveWithdrawalGates } from '@/gates/accumulationGates';
import { confirmReceiptGates } from '@/gates/receiptGates';
import { approveSurrenderGates } from '@/gates/valueGates';
import { canDecide } from '@/features/finance/enginePeriod';
import { grantAccess } from '@/test/access';
import { barredAsOwn, mayActAlone, SIGNING_OWN } from './ownWork';

const ME = 'subject-me';

describe('approving one’s own work (2026-11-04)', () => {
  it('bars a person from their own work unless they hold their area’s permission', () => {
    grantAccess('collections.approve');
    expect(barredAsOwn(ME, ME, 'receipts')).toBe(true);
    expect(barredAsOwn(ME, 'someone-else', 'receipts')).toBe(false);
    expect(barredAsOwn(undefined, ME, 'receipts')).toBe(false);

    grantAccess('collections.approve', 'collections.approve-own');
    expect(mayActAlone('receipts')).toBe(true);
    expect(barredAsOwn(ME, ME, 'receipts')).toBe(false);
    // Only that area: their own payouts still wait for someone else.
    expect(barredAsOwn(ME, ME, 'payouts')).toBe(true);
  });

  it('opens a gate on one’s own item and says it is recorded by name', () => {
    const withdrawal = { status: 'REQUESTED', requestedBy: ME } as Parameters<typeof approveWithdrawalGates>[0];
    grantAccess('accounts.approve');
    const refused = approveWithdrawalGates(withdrawal, ME)[1]!;
    expect(refused.ok).toBe(false);

    grantAccess('accounts.approve', 'accounts.approve-own');
    const allowed = approveWithdrawalGates(withdrawal, ME)[1]!;
    expect(allowed.ok).toBe(true);
    expect(allowed.detail).toBe(SIGNING_OWN);
  });

  it('applies the same way to receipts, surrenders and IFRS 17 runs', () => {
    const receipt = { status: 'AWAITING_APPROVAL', recordedBy: ME } as Parameters<typeof confirmReceiptGates>[0];
    const surrender = { status: 'REQUESTED', requestedBy: ME } as NonNullable<Parameters<typeof approveSurrenderGates>[0]>;
    const run = { status: 'VALIDATED', uploadedBy: ME };

    grantAccess();
    expect(confirmReceiptGates(receipt, ME)[1]!.ok).toBe(false);
    expect(approveSurrenderGates(surrender, ME)[1]!.ok).toBe(false);
    expect(canDecide(run, ME, true)).toBe(false);

    grantAccess('collections.approve-own', 'policies.surrender-approve-own', 'ifrs17.approve-own');
    expect(confirmReceiptGates(receipt, ME)[1]!.ok).toBe(true);
    expect(approveSurrenderGates(surrender, ME)[1]!.ok).toBe(true);
    expect(canDecide(run, ME, true)).toBe(true);
  });
});
