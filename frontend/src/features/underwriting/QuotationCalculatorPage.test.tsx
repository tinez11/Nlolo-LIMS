import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getActiveSnapshot, listProducts, quotePremium } from '@/api/products';
import { downloadQuotation } from '@/api/documents';
import { registerIndividual } from '@/api/party';
import { openCase } from '@/api/underwriting';
import { renderScreen } from '@/test/renderScreen';
import { acceptQuotation } from '@/api/agentQuotations';
import { Route, Routes, useSearchParams } from 'react-router-dom';
import { QuotationCalculatorPage } from './QuotationCalculatorPage';

vi.mock('@/api/products');
vi.mock('@/api/accumulation');
vi.mock('@/api/funeral');
vi.mock('@/api/documents');
vi.mock('@/api/party');
vi.mock('@/api/underwriting');
vi.mock('@/api/agentQuotations');
vi.mock('@/lib/download', () => ({ saveBlob: vi.fn() }));
// The segmented date control is tested on its own; a plain input stands in for it here.
vi.mock('@/components/DatePicker', () => ({
  DatePicker: ({ onChange }: { onChange: (v: string) => void }) => (
    <input aria-label="date" onChange={(e) => onChange(e.target.value)} />
  ),
}));

const ENDOWMENT = {
  productId: 'prod-end',
  productCode: 'END1',
  productName: 'Prospect Endowment',
  category: 'ENDOWMENT',
  portfolioCode: 'END',
  defaultCurrency: 'TZS',
};
const ANNUITY = {
  productId: 'prod-ann',
  productCode: 'ANN1',
  productName: 'Immediate Annuity',
  category: 'ANNUITY',
  portfolioCode: 'IANN',
  defaultCurrency: 'TZS',
};
const DEPOSIT = {
  productId: 'prod-dep',
  productCode: 'FTD1',
  productName: 'Fixed Term Deposit',
  category: 'EDUCATION_SAVINGS',
  portfolioCode: 'DEP',
  defaultCurrency: 'TZS',
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(listProducts).mockResolvedValue([ENDOWMENT, ANNUITY] as never);
  vi.mocked(getActiveSnapshot).mockResolvedValue({ productVersionId: 'ver-end', eligibility: null } as never);
  vi.mocked(quotePremium).mockResolvedValue({
    productVersionId: 'ver-end',
    currency: 'TZS',
    ageAtEntry: 40,
    instalmentAmount: 1000,
    annualAfterFrequencyLoading: 12000,
    annualBase: 12000,
    totalOverTerm: 240000,
    instalmentsOverTerm: 240,
  } as never);
  vi.mocked(downloadQuotation).mockResolvedValue(new Blob(['%PDF']));
});

/** Nested as App.tsx nests a realm, so the page's relative navigation lands where it does in the app. */
function OnboardingProbe() {
  const [params] = useSearchParams();
  return <p>onboarding {params.get('quotation')}</p>;
}

async function fillProspectAndPlan() {
  renderScreen(
    <Routes>
      <Route path="/agents">
        <Route path="quotation" element={<QuotationCalculatorPage />} />
        <Route path="customers/new" element={<OnboardingProbe />} />
      </Route>
    </Routes>,
    { route: '/agents/quotation' },
  );
  await screen.findByRole('option', { name: 'Prospect Endowment (END1)' });
  await userEvent.type(screen.getByLabelText('Full name'), 'Halima Prospect');
  // The first date on the page is the person's date of birth; the second is when cover would start.
  await userEvent.type(screen.getAllByLabelText('date')[0]!, '1985-05-01');
  await userEvent.selectOptions(screen.getByLabelText('Sex'), 'MALE');
  await userEvent.selectOptions(screen.getByLabelText('Smoker status'), 'NON_SMOKER');
  await userEvent.selectOptions(screen.getByLabelText('Product'), 'prod-end');
  await waitFor(() => expect(getActiveSnapshot).toHaveBeenCalledWith('prod-end'));
  await userEvent.type(screen.getByLabelText('Sum assured or amount'), '1000000');
  await userEvent.selectOptions(screen.getByLabelText('Premium frequency'), 'MONTHLY');
  await userEvent.type(screen.getByLabelText('Term (months)'), '240');
}

// Typing a whole person and plan character by character outruns the default 5s under a full parallel run.
describe('QuotationCalculatorPage', { timeout: 20_000 }, () => {
  it("prices a prospect who is not registered, from the person's details as typed", async () => {
    await fillProspectAndPlan();

    await waitFor(() =>
      expect(quotePremium).toHaveBeenLastCalledWith(
        'prod-end',
        expect.objectContaining({
          dateOfBirth: '1985-05-01',
          sex: 'MALE',
          smokerStatus: 'NON_SMOKER',
          frequency: 'MONTHLY',
        }),
      ),
    );
    expect(await screen.findByText('TZS 1,000.00')).toBeInTheDocument();
  });

  it('downloads the prospect quotation with the inputs only -- never a figure from the screen', async () => {
    await fillProspectAndPlan();
    await screen.findByText('TZS 1,000.00');

    await userEvent.click(screen.getByRole('button', { name: 'Download the quotation as PDF' }));

    await waitFor(() => expect(downloadQuotation).toHaveBeenCalledTimes(1));
    const request = vi.mocked(downloadQuotation).mock.calls[0]![0];
    expect(request).toMatchObject({
      productId: 'prod-end',
      productVersionId: 'ver-end',
      prospect: { fullName: 'Halima Prospect', dateOfBirth: '1985-05-01', sex: 'MALE', smokerStatus: 'NON_SMOKER' },
      sumAssured: 1000000,
      frequency: 'MONTHLY',
      policyTermMonths: 240,
    });
    expect(request).not.toHaveProperty('applicantPartyId');
    // The quoted figures stay on the screen; the server prices the paper again from the inputs.
    expect(JSON.stringify(request)).not.toMatch(/1000\b|12000|240000|instalment|premium"|total/i);
    expect(screen.getByText('The figures above, for the prospect to take away and study.')).toBeInTheDocument();
  });

  it('saves nothing: quoting and downloading register nobody and open no case', async () => {
    await fillProspectAndPlan();
    await screen.findByText('TZS 1,000.00');
    await userEvent.click(screen.getByRole('button', { name: 'Download the quotation as PDF' }));
    await waitFor(() => expect(downloadQuotation).toHaveBeenCalled());

    expect(registerIndividual).not.toHaveBeenCalled();
    expect(openCase).not.toHaveBeenCalled();
  });

  it('proceed, then confirm: saves the inputs -- never the figures -- and goes on to onboard the customer', async () => {
    vi.mocked(acceptQuotation).mockResolvedValue({ quotationId: 'q-1' } as never);
    await fillProspectAndPlan();
    await screen.findByText('TZS 1,000.00');

    await userEvent.click(screen.getByRole('button', { name: 'Proceed to onboarding' }));
    // Nothing is sent by proceeding: the second click is the one that writes.
    expect(acceptQuotation).not.toHaveBeenCalled();
    expect(screen.getByRole('group', { name: 'Has the customer agreed to this quotation?' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm and start onboarding' }));

    await waitFor(() => expect(acceptQuotation).toHaveBeenCalledTimes(1));
    const request = vi.mocked(acceptQuotation).mock.calls[0]![0];
    expect(request).toMatchObject({ productVersionId: 'ver-end', prospect: { fullName: 'Halima Prospect' }, sumAssured: 1000000 });
    expect(JSON.stringify(request)).not.toMatch(/instalment|premium"|total|12000|240000/i);
    expect(await screen.findByText('onboarding q-1')).toBeInTheDocument();
  });

  it('proceed, then cancel: sends nothing and returns to the calculator', async () => {
    await fillProspectAndPlan();
    await screen.findByText('TZS 1,000.00');

    await userEvent.click(screen.getByRole('button', { name: 'Proceed to onboarding' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(acceptQuotation).not.toHaveBeenCalled();
    expect(registerIndividual).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Proceed to onboarding' })).toBeInTheDocument();
    expect(screen.getByLabelText('Full name')).toHaveValue('Halima Prospect');
  });

  it('says beside the field when an amount is not digits, and sends nothing until it is', async () => {
    renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });
    await screen.findByRole('option', { name: 'Prospect Endowment (END1)' });
    await userEvent.type(screen.getByLabelText('Full name'), 'Halima Prospect');
    await userEvent.type(screen.getAllByLabelText('date')[0]!, '1985-05-01');
    await userEvent.selectOptions(screen.getByLabelText('Sex'), 'MALE');
    await userEvent.selectOptions(screen.getByLabelText('Product'), 'prod-end');
    await userEvent.selectOptions(screen.getByLabelText('Premium frequency'), 'MONTHLY');
    await userEvent.type(screen.getByLabelText('Sum assured or amount'), '1,000,000');
    await userEvent.type(screen.getByLabelText('Term (months)'), '10 years');

    expect(screen.getByText(/Digits only, e.g. 1500000/)).toBeInTheDocument();
    expect(screen.getAllByText(/A whole number of months/).length).toBeGreaterThan(0);
    // Typing "1,000,000" passes through "1", a real amount, on the way -- what matters is that nothing malformed
    // ever went out: every amount sent was a number.
    for (const [, request] of vi.mocked(quotePremium).mock.calls) {
      expect(Number.isFinite(request.sumAssuredAmount)).toBe(true);
      expect(request.policyTermMonths === undefined || Number.isInteger(request.policyTermMonths)).toBe(true);
    }
    expect(screen.getByRole('button', { name: 'Download the quotation as PDF' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Proceed to onboarding' })).toBeDisabled();
  });

  it('takes a phone written the everyday way and sends it as +255; holds back one that is not Tanzanian', async () => {
    await fillProspectAndPlan();
    await screen.findByText('TZS 1,000.00');
    const phone = screen.getByLabelText('Phone number (optional)');

    await userEvent.type(phone, '0713 000 11');
    expect(screen.getByText(/Enter a Tanzanian number/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download the quotation as PDF' })).toBeDisabled();
    expect(screen.getByText('Correct the phone number, or leave it blank, to proceed.')).toBeInTheDocument();

    await userEvent.type(phone, '1');
    expect(screen.queryByText(/Enter a Tanzanian number/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Download the quotation as PDF' }));
    await waitFor(() => expect(downloadQuotation).toHaveBeenCalled());
    expect(vi.mocked(downloadQuotation).mock.calls[0]![0].prospect?.phoneNumber).toBe('+255713000111');
  });

  it('does not offer Proceed on a quotation the server refused, and says why', async () => {
    vi.mocked(quotePremium).mockRejectedValue({
      status: 422, kind: 'validation', title: 'Unprocessable', detail: 'No base rate for this age', traceId: 't',
      fieldErrors: [], errorCode: null, mayBeDenied: false,
    });
    await fillProspectAndPlan();

    expect(await screen.findByText(/the quotation above was refused/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Proceed to onboarding' })).toBeDisabled();
    expect(acceptQuotation).not.toHaveBeenCalled();
  });

  it('says what is missing while Proceed is not available', async () => {
    renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });
    await screen.findByRole('option', { name: 'Prospect Endowment (END1)' });

    expect(screen.getByText("Enter the person's name, the product and the plan to proceed.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Proceed to onboarding' })).toBeDisabled();
  });

  it('shows the server refusing the save inside the confirmation, and stays on the calculator', async () => {
    vi.mocked(acceptQuotation).mockRejectedValue({
      status: 422, kind: 'validation', title: 'Cannot do that', detail: 'This deposit is not offered over 12 months',
      traceId: 't', fieldErrors: [], errorCode: null, mayBeDenied: false,
    });
    await fillProspectAndPlan();
    await screen.findByText('TZS 1,000.00');
    await userEvent.click(screen.getByRole('button', { name: 'Proceed to onboarding' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm and start onboarding' }));

    expect(await screen.findByText(/This deposit is not offered over 12 months/)).toBeInTheDocument();
    expect(screen.queryByText(/^onboarding/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm and start onboarding' })).toBeEnabled();
  });

  // Annuities joined on 2026-10-23: the income a purchase price buys, quoted for a client or a prospect.
  it('offers the products it can quote, annuities among them, and says who quotes the rest', async () => {
    renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });

    expect(await screen.findByRole('option', { name: 'Prospect Endowment (END1)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Immediate Annuity (ANN1)' })).toBeInTheDocument();
    expect(screen.getByText(/Unit-linked and group cover are quoted by the office/)).toBeInTheDocument();
  });

  // 2026-10-21: a MONTHLY deposit used to pass the calculator and fail only at submission, after the agent had
  // already onboarded the customer ("A fixed-term deposit is paid once: choose SINGLE, not MONTHLY"). Filtering the
  // choice here means that can no longer happen.
  it('a fixed-term deposit offers no recurring frequency -- it is paid once', async () => {
    vi.mocked(listProducts).mockResolvedValue([ENDOWMENT, ANNUITY, DEPOSIT] as never);
    renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });

    await screen.findByRole('option', { name: 'Fixed Term Deposit (FTD1)' });
    await userEvent.selectOptions(screen.getByLabelText('Product'), 'prod-dep');
    await waitFor(() => expect(getActiveSnapshot).toHaveBeenCalledWith('prod-dep'));

    const frequency = screen.getByLabelText('Premium frequency') as HTMLSelectElement;
    await waitFor(() => expect(frequency).toHaveValue('SINGLE'));
    expect(frequency).toBeDisabled();
    expect(screen.queryByRole('option', { name: 'Monthly' })).not.toBeInTheDocument();
    expect(screen.getByText(/takes no recurring premium/)).toBeInTheDocument();
  });

  it('says what happens after the quotation, in the order it happens', async () => {
    renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });

    expect(await screen.findByRole('heading', { name: 'What happens next' })).toBeInTheDocument();
    expect(screen.getByText(/give the customer the quotation PDF to take away\. Nothing is saved\./)).toBeInTheDocument();
    expect(screen.getByText(/returns it to you with what to correct/)).toBeInTheDocument();
  });
});
