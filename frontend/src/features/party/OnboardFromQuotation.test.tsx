import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useParams, useSearchParams } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { discardAgentQuotation, getAgentQuotation } from '@/api/agentQuotations';
import { registerIndividual } from '@/api/party';
import { listProducts } from '@/api/products';
import { renderScreen } from '@/test/renderScreen';
import { OnboardCustomerPage } from './OnboardCustomerPage';

vi.mock('@/api/agentQuotations');
vi.mock('@/api/party');
vi.mock('@/api/products');
// The segmented date control is tested on its own; a plain input stands in for it here.
vi.mock('@/components/DatePicker', () => ({
  DatePicker: ({ value, onChange }: { value: string | null; onChange: (v: string) => void }) => (
    <input aria-label="date" value={value ?? ''} onChange={(e) => onChange(e.target.value)} />
  ),
}));

/**
 * Onboarding a customer from the quotation they agreed to (2026-10-20): the details as quoted are already filled in,
 * cancelling discards the quotation, and registering leads to the proposal -- not to "Register another".
 */
const SAVED = {
  quotationId: 'q-1',
  applicantPartyId: null,
  productId: 'prod-end',
  productVersionId: 'ver-end',
  inputs: {
    productId: 'prod-end',
    productVersionId: 'ver-end',
    prospect: {
      fullName: 'Halima Prospect',
      phoneNumber: '+255713000111',
      dateOfBirth: '1985-05-01',
      sex: 'FEMALE',
      smokerStatus: 'NON_SMOKER',
      occupationClass: 'STANDARD',
    },
    sumAssured: 1000000,
    frequency: 'MONTHLY',
    policyTermMonths: 240,
  },
  quoted: { sumAssured: 1000000, currency: 'TZS', quote: { instalmentAmount: 1000, annualAfterFrequencyLoading: 12000 } },
  quotedOn: '2026-10-20',
  validUntil: '2026-11-19',
  caseId: null,
  proposalNumber: null,
};

/** What lib/http throws: an ApiError, already normalised. */
function apiError(status: number, detail: string) {
  return {
    status, kind: status === 404 ? 'notFound' : 'server', title: 'Failed', detail, traceId: 't', errorCode: null,
    fieldErrors: [], mayBeDenied: status === 404,
  };
}

function ProposalProbe() {
  const { quotationId } = useParams();
  const [params] = useSearchParams();
  return <p>proposal {quotationId} for {params.get('party')}</p>;
}

function renderOnboarding() {
  renderScreen(
    <Routes>
      <Route path="/agents">
        <Route path="customers/new" element={<OnboardCustomerPage />} />
        <Route path="quotation" element={<p>the calculator</p>} />
        <Route path="quotation/:quotationId" element={<ProposalProbe />} />
      </Route>
    </Routes>,
    { route: '/agents/customers/new?quotation=q-1' },
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getAgentQuotation).mockResolvedValue(SAVED as never);
  vi.mocked(listProducts).mockResolvedValue([{ productId: 'prod-end', productName: 'Prospect Endowment' }] as never);
});

describe('OnboardCustomerPage from a confirmed quotation', { timeout: 20_000 }, () => {
  it('fills the person in from the quotation, and shows what they agreed to', async () => {
    renderOnboarding();

    expect(await screen.findByLabelText('Full name')).toHaveValue('Halima Prospect');
    expect(screen.getByLabelText('Phone number (optional)')).toHaveValue('+255713000111');
    expect(screen.getByLabelText('Sex')).toHaveValue('FEMALE');
    expect(screen.getByLabelText('Smoker status')).toHaveValue('NON_SMOKER');
    expect(screen.getByLabelText('Occupation class')).toHaveValue('STANDARD');
    expect(screen.getAllByLabelText('date')[0]).toHaveValue('1985-05-01');
    expect(screen.getByRole('heading', { name: 'The accepted quotation' })).toBeInTheDocument();
    expect(await screen.findByText('Prospect Endowment')).toBeInTheDocument();
  });

  it('cancel discards the quotation and returns to the calculator, registering nobody', async () => {
    vi.mocked(discardAgentQuotation).mockResolvedValue(undefined);
    renderOnboarding();

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel and discard the quotation' }));

    await waitFor(() => expect(discardAgentQuotation).toHaveBeenCalledWith('q-1'));
    expect(registerIndividual).not.toHaveBeenCalled();
    expect(await screen.findByText('the calculator')).toBeInTheDocument();
  });

  it('a quotation that is gone says so, and how to go on', async () => {
    vi.mocked(getAgentQuotation).mockRejectedValue(apiError(404, 'No quotation q-1'));
    renderOnboarding();

    expect(await screen.findByText('This quotation is no longer available.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to the calculator' })).toHaveAttribute('href', '/agents/quotation');
    expect(screen.queryByLabelText('Full name')).not.toBeInTheDocument();
  });

  it('a discard that fails keeps the form and what was typed, and says why', async () => {
    vi.mocked(discardAgentQuotation).mockRejectedValue(apiError(500, 'Database unavailable'));
    renderOnboarding();
    const name = await screen.findByLabelText('Full name');
    await userEvent.clear(name);
    await userEvent.type(name, 'Halima P. Prospect');

    await userEvent.click(screen.getByRole('button', { name: 'Cancel and discard the quotation' }));

    expect(await screen.findByText(/The quotation could not be discarded/)).toBeInTheDocument();
    expect(screen.getByLabelText('Full name')).toHaveValue('Halima P. Prospect');
  });

  it('a discard of a quotation already gone goes back to the calculator', async () => {
    vi.mocked(discardAgentQuotation).mockRejectedValue(apiError(404, 'No quotation q-1'));
    renderOnboarding();

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel and discard the quotation' }));

    expect(await screen.findByText('the calculator')).toBeInTheDocument();
  });

  it('after registering, goes on to submit the proposal for the new customer', async () => {
    vi.mocked(registerIndividual).mockResolvedValue({ partyId: 'party-9', kycStatus: 'PENDING' } as never);
    renderOnboarding();
    await screen.findByLabelText('Full name');
    // Registration still asks for an identity document, which a quotation does not carry.
    await userEvent.selectOptions(screen.getByLabelText('ID type'), 'NATIONAL_ID');
    await userEvent.type(screen.getByLabelText('ID number'), '19850501-12345-00001-23');

    await userEvent.click(screen.getByRole('button', { name: 'Register individual' }));

    await waitFor(() => expect(registerIndividual).toHaveBeenCalledTimes(1));
    expect(vi.mocked(registerIndividual).mock.calls[0]![0]).toMatchObject({ fullName: 'Halima Prospect', sex: 'FEMALE' });
    expect(await screen.findByText('proposal q-1 for party-9')).toBeInTheDocument();
  });
});
