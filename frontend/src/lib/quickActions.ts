import {
  ArrowDownToLine, BadgeCheck, Banknote, ClipboardPlus, FilePlus2, HandCoins, Landmark, Pencil, Play, RotateCcw, Send, Users, Wallet,
} from 'lucide-react';
import { can, canAny } from '@/auth/access';
import type { QuickAction } from '@/components/QuickActions';
import { claimableStatus } from '@/lib/policyCapacity';

const enc = encodeURIComponent;

/**
 * The everyday acts on a person or group, for staff (2026-11-04, the user: find someone, see your actions, skip the
 * clicking). Only what this person's permissions allow; each opens the page that does it, the client filled in.
 *
 * @param onlyPolicy their one policy, when they hold exactly one: a payment is then recorded against it directly
 */
export function clientActions(client: { partyId: string; kycStatus?: string | null | undefined; partyType?: string | null | undefined },
  onlyPolicy?: string | null): QuickAction[] {
  const id = enc(client.partyId);
  const actions: QuickAction[] = [];
  if (can('claims.register')) {
    actions.push({ label: 'Register a claim', to: `/staff/claims/new?claimant=${id}`, icon: ClipboardPlus });
  }
  if (can('underwriting.propose')) {
    actions.push({ label: 'New case', to: `/staff/underwriting/new?applicant=${id}`, icon: FilePlus2 });
  }
  if (can('collections.manage')) {
    actions.push({
      label: 'Record deposit',
      to: onlyPolicy ? `/staff/receipts/new?policy=${enc(onlyPolicy)}` : `/staff/parties/${id}?tab=policies`,
      icon: HandCoins,
    });
  }
  if (can('clients.kyc') && client.kycStatus === 'PENDING') {
    actions.push({ label: 'Verify KYC', to: `/staff/parties/${id}?tab=kyc`, icon: BadgeCheck });
  }
  if (can('clients.portal') && client.partyType === 'INDIVIDUAL') {
    actions.push({ label: 'Invite to portal', to: `/staff/parties/${id}?tab=portal`, icon: Send });
  }
  if (can('clients.edit')) {
    actions.push({ label: 'Correct details', to: `/staff/parties/${id}/edit`, icon: Pencil });
  }
  return actions;
}

/** Policy.canSurrender. */
const SURRENDERABLE = ['ACTIVE', 'REINSTATED', 'PAID_UP', 'LAPSED'];

/**
 * The everyday acts on one policy, for staff. `tabs` is the policy page's own tabs, so a button is offered only where
 * the policy has the part it opens -- no Account button on a term policy.
 */
export function policyActions(policy: {
  policyNumber: string;
  policyholderPartyId?: string | null | undefined;
  status?: string | null | undefined;
  productCategory?: string | null | undefined;
}, tabs: readonly string[], valuePanel: boolean): QuickAction[] {
  const n = enc(policy.policyNumber);
  const here = `/staff/policies/${n}`;
  const actions: QuickAction[] = [];
  // Only where the server would take it (claimableStatus): never on an offer not yet on cover.
  if (can('claims.register') && claimableStatus(policy.status, policy.productCategory)) {
    // The holder files on their own policy, and on credit life the lender is the only claimant there is. On a group
    // scheme the holder is the employer, who is not owed a member's death benefit, so nobody is filled in.
    const claimant = policy.policyholderPartyId && policy.productCategory !== 'GROUP_LIFE'
      ? `&claimant=${enc(policy.policyholderPartyId)}` : '';
    actions.push({ label: 'Register a claim', to: `/staff/claims/new?policy=${n}${claimant}`, icon: ClipboardPlus });
  }
  if (can('collections.manage')) {
    // The act's one name since money movement: the Balance panel's button says "Record deposit" too.
    actions.push({ label: 'Record deposit', to: `/staff/receipts/new?policy=${n}`, icon: HandCoins });
  }
  // The endpoint's rule (POST /invoices/{id}/payment-request): servicing or managing collections, not merely seeing them.
  if (canAny('collections.manage', 'policies.service') && tabs.includes('billing')) {
    actions.push({ label: 'Request payment', to: `${here}?tab=billing`, icon: Wallet });
  }
  if (can('policies.service') && tabs.includes('beneficiaries')) {
    actions.push({ label: 'Change beneficiaries', to: `${here}?tab=beneficiaries`, icon: Users });
  }
  if (can('accounts.transact') && tabs.includes('account')) {
    actions.push({ label: 'Withdrawal', to: `${here}?tab=account`, icon: ArrowDownToLine });
  }
  if (can('loans.manage') && tabs.includes('loans')) {
    actions.push({ label: 'Loan', to: `${here}?tab=loans`, icon: Landmark });
  }
  if (valuePanel && SURRENDERABLE.includes(policy.status ?? '') && canAny('policies.surrender', 'policies.surrender-approve')) {
    actions.push({ label: 'Surrender', to: `${here}?focus=value`, icon: Banknote });
  }
  // Named as the Lifecycle panel names its act, and only where it has one. Suspend is left to the panel: whether a kind
  // of policy may be suspended at all is reference data read there.
  if (can('policies.status') && policy.status === 'SUSPENDED') {
    actions.push({ label: 'Resume', to: `${here}?focus=lifecycle`, icon: Play });
  }
  if (can('policies.status') && policy.status === 'LAPSED') {
    actions.push({ label: 'Reinstate', to: `${here}?focus=lifecycle`, icon: RotateCcw });
  }
  return actions;
}
