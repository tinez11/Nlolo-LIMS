import {
  ArrowDownToLine, BadgeCheck, Banknote, ClipboardPlus, FilePlus2, HandCoins, Landmark, Pencil, Play, RotateCcw, Send, Users,
} from 'lucide-react';
import { can, canAny } from '@/auth/access';
import type { QuickAction } from '@/components/QuickActions';
import { claimableStatus } from '@/lib/policyCapacity';

const enc = encodeURIComponent;

/** Who may take a deposit on the one Deposit page: record money received, or ask a phone for it (2026-11-07). */
const DEPOSIT_PERMISSIONS = ['collections.manage', 'policies.service', 'accounts.transact'];

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
  if (canAny(...DEPOSIT_PERMISSIONS)) {
    actions.push({
      label: 'Deposit',
      to: onlyPolicy ? `/staff/receipts/new?policy=${enc(onlyPolicy)}&from=policy` : `/staff/parties/${id}?tab=policies`,
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
  // One Deposit (2026-11-07, the user): every method on one page -- finance records money received, servicing asks a
  // phone for it. "Request payment" went with it.
  if (canAny(...DEPOSIT_PERMISSIONS)) {
    actions.push({ label: 'Deposit', to: `/staff/receipts/new?policy=${n}&from=policy`, icon: HandCoins });
  }
  if (can('policies.service') && tabs.includes('beneficiaries')) {
    actions.push({ label: 'Change beneficiaries', to: `${here}?tab=beneficiaries`, icon: Users });
  }
  if (can('accounts.transact') && tabs.includes('account')) {
    actions.push({ label: 'Withdraw', to: `${here}?tab=account`, icon: ArrowDownToLine });
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
