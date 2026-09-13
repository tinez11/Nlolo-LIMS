import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { replaceBeneficiaries } from '@/api/policies';
import { usePolicyStore } from '@/store/policyStore';
import { BeneficiariesPanel } from './BeneficiariesPanel';

vi.mock('@/api/policies');

/**
 * Panel-level proof that a designation the client rejects NEVER reaches the network.
 *
 * Two e2e tests used to carry this, and both said in their own names that they never reached the
 * network -- one of them listening on `page.on('request')` to prove it. They paid
 * staff-beneficiaries.spec.ts's beforeEach, which issues a whole policy through underwriting
 * (open a case, assess it, decide it, manually issue it) to get a policy page to open the editor
 * on, and then asserted a zod rule. Together they cost about two minutes of a 46-minute suite, and
 * they were two of the three tests that failed when that suite ran under load -- on the fixed 15s
 * navigation timeout in their setup, not on anything about beneficiaries.
 *
 * The RULES they covered were never the unique part: beneficiaryForm.test.ts pins the sum-to-100
 * rule across four totals where the e2e pinned one, and pins the both-set rule identically. What
 * only a rendered panel can show is the SHORT-CIRCUIT -- that `saveBeneficiaries` is not called at
 * all, so the server's matching rule is never what produced the message on screen. That is what
 * this file keeps, at the layer where the assertion is cheap and cannot be lost in a suite-wide
 * timeout.
 *
 * Asserting on `replaceBeneficiaries` rather than on the store action is deliberate: it is the
 * last thing before the wire, so it proves the whole form -> store -> api chain stayed shut.
 */
const EXISTING = [
  { type: 'FREEFORM' as const, freeformDesignee: 'The estate', sharePercent: 100, revocable: true },
];

beforeEach(() => {
  vi.clearAllMocks();
  usePolicyStore.setState({ savingBeneficiaries: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function openTheEditor(user: ReturnType<typeof userEvent.setup>) {
  render(<BeneficiariesPanel policyNumber="POL-000001" beneficiaries={EXISTING} />);
  await user.click(screen.getByRole('button', { name: 'Edit beneficiaries' }));
}

describe('BeneficiariesPanel short-circuits a designation the client rejects', () => {
  it('does not send shares that fail the sum-to-100 rule', async () => {
    const user = userEvent.setup();
    await openTheEditor(user);

    // 100 -> 50 on the only row, so the array sums to 50 and nothing else is wrong with it.
    const share = screen.getByRole('spinbutton');
    await user.clear(share);
    await user.type(share, '50');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    // A whole-array zod issue, rendered from errors.beneficiaries.root -- not a server banner,
    // which is exactly the confusion the deleted e2e test's own comment recorded: an earlier
    // version of it claimed to have confirmed "the real backend 422" and was reading this.
    await waitFor(() => expect(screen.getByText(/must sum to 100, got 50/i)).toBeInTheDocument());
    expect(replaceBeneficiaries).not.toHaveBeenCalled();

    // The editor stays open with the rejected input on screen; a failed save that silently
    // closed would discard what the user typed.
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
    expect(share).toHaveValue(50);
  });

  /*
   * The deleted "both a party id and a freeform designee" e2e test has no twin here, and that is
   * the point rather than an omission. Its RULE is pinned in beneficiaryForm.test.ts ('rejects a
   * row with BOTH partyId and freeformDesignee set'). The only other thing it showed is the
   * short-circuit above -- the same property, not a second one -- and reaching the both-set shape
   * through a rendered panel means driving the party picker with a mocked search just to re-prove
   * it. Writing that would be the duplication this whole change is removing.
   */
});
