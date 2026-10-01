import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import type { PolicyView, SurrenderQuote, SurrenderRequestView } from '@/api/types';
import { usePolicyStore } from '@/store/policyStore';
import { ValueActions } from './ValueActions';

/**
 * Surrender and paid-up, as the person doing them meets them.
 *
 * The subject is the pair of refusals the platform makes and the console must make first: a
 * policy with no value cannot be surrendered, and the requester cannot approve their own
 * surrender. Both are server rules; both would otherwise be learned from a 409 after the second,
 * deliberate click.
 */

vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'token-for-alice' } }),
}));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({ subject: 'alice' }),
  canSeeFinance: () => true,
}));

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });

const policy = (over: Partial<PolicyView> = {}): PolicyView =>
  ({
    policyNumber: 'POL-000001',
    status: 'ACTIVE',
    productCategory: 'ENDOWMENT',
    cashValue: TZS('100000.00'),
    ...over,
  }) as PolicyView;

const quote: SurrenderQuote = {
  policyNumber: 'POL-000001',
  quotedValue: TZS('90000.00'),
  quotedAt: '2026-10-01T09:00:00Z',
} as SurrenderQuote;

const request = (over: Partial<SurrenderRequestView> = {}): SurrenderRequestView =>
  ({
    surrenderRequestId: 'sr-1',
    policyNumber: 'POL-000001',
    status: 'REQUESTED',
    quotedValue: TZS('90000.00'),
    payeeRef: '+255712345678',
    requestedBy: 'alice',
    ...over,
  }) as SurrenderRequestView;

beforeEach(() => {
  usePolicyStore.setState({
    surrenderQuote: {},
    surrenderRequest: {},
    requestingSurrender: {},
    approvingSurrender: {},
    makingPaidUp: {},
  });
  vi.spyOn(policiesApi, 'getSurrenderValue').mockResolvedValue(quote);
  vi.spyOn(policiesApi, 'getSurrenderRequest').mockResolvedValue('');
});

describe('ValueActions surrender', () => {
  it('offers a surrender on a savings policy with value, naming what would be paid', async () => {
    render(<ValueActions policy={policy()} />);
    await waitFor(() => expect(screen.getByText('TZS 90,000.00 would be paid.')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Request surrender' })).toBeDisabled(); // no payee yet
  });

  it('refuses a policy with no cash value, and says so rather than failing on submit', async () => {
    vi.spyOn(policiesApi, 'getSurrenderValue').mockResolvedValue(
      { ...quote, quotedValue: TZS('0.00') } as SurrenderQuote,
    );
    render(<ValueActions policy={policy({ cashValue: TZS('0.00') })} />);
    await waitFor(() =>
      expect(screen.getByText('This policy has no cash value to surrender.')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Request surrender' })).toBeDisabled();
  });

  it('names the real figures in the confirmation, not a summary of them', async () => {
    const user = userEvent.setup();
    render(<ValueActions policy={policy()} />);
    await waitFor(() => expect(screen.getByText('TZS 90,000.00 would be paid.')).toBeInTheDocument());

    await user.type(screen.getByLabelText('Payee (mobile money or bank destination)'), '+255712345678');
    await user.click(screen.getByRole('button', { name: 'Request surrender' }));

    const confirm = screen.getByRole('group', { name: 'Request this surrender?' });
    expect(confirm).toHaveTextContent('TZS 90,000.00');
    expect(confirm).toHaveTextContent('+255712345678');
    // Cover does NOT stop on a request, and the copy has to say so: this is the one surrender
    // step that changes nothing.
    expect(confirm).toHaveTextContent('does not stop yet');
  });

  it('refuses the requester as approver, in the server wording', async () => {
    vi.spyOn(policiesApi, 'getSurrenderRequest').mockResolvedValue(request({ requestedBy: 'alice' }));
    render(<ValueActions policy={policy()} />);
    await waitFor(() =>
      expect(
        screen.getByText(
          'A surrender must be approved by someone other than the person who requested it (alice).',
        ),
      ).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Approve surrender' })).toBeDisabled();
  });

  it('lets a different person approve, and says the money leaves', async () => {
    const user = userEvent.setup();
    vi.spyOn(policiesApi, 'getSurrenderRequest').mockResolvedValue(request({ requestedBy: 'bob' }));
    render(<ValueActions policy={policy()} />);

    const approve = await screen.findByRole('button', { name: 'Approve surrender' });
    expect(approve).toBeEnabled();
    await user.click(approve);

    const confirm = screen.getByRole('group', { name: 'Approve this surrender?' });
    expect(confirm).toHaveTextContent('TZS 90,000.00');
    expect(confirm).toHaveTextContent('stop cover');
  });
});

describe('ValueActions paid-up', () => {
  it('offers paid-up on an active savings policy with value', async () => {
    render(<ValueActions policy={policy()} />);
    expect(await screen.findByRole('button', { name: 'Make paid-up' })).toBeEnabled();
  });

  it('refuses a status the server refuses', async () => {
    render(<ValueActions policy={policy({ status: 'SUSPENDED' })} />);
    expect(await screen.findByRole('button', { name: 'Make paid-up' })).toBeDisabled();
    expect(
      screen.getByText(
        'This policy is Suspended. Only an active, reinstated or lapsed policy can be made paid-up.',
      ),
    ).toBeInTheDocument();
  });

  it('says what a paid-up policy already is, rather than offering it again', async () => {
    render(<ValueActions policy={policy({ status: 'PAID_UP' })} />);
    await waitFor(() =>
      expect(screen.getByText(/no premium is due and it stays on cover/)).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Make paid-up' })).not.toBeInTheDocument();
  });
});
