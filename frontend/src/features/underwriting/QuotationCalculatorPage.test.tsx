import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getActiveSnapshot, listProducts, quotePremium } from '@/api/products';
import { downloadQuotation } from '@/api/documents';
import { registerIndividual } from '@/api/party';
import { openCase } from '@/api/underwriting';
import { renderScreen } from '@/test/renderScreen';
import { QuotationCalculatorPage } from './QuotationCalculatorPage';

vi.mock('@/api/products');
vi.mock('@/api/accumulation');
vi.mock('@/api/funeral');
vi.mock('@/api/documents');
vi.mock('@/api/party');
vi.mock('@/api/underwriting');
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

async function fillProspectAndPlan() {
  renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });
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

  it('offers only the products it can quote, and says who quotes the rest', async () => {
    renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });

    expect(await screen.findByRole('option', { name: 'Prospect Endowment (END1)' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Immediate Annuity (ANN1)' })).not.toBeInTheDocument();
    expect(screen.getByText(/Annuities, unit-linked and group cover are quoted by the office/)).toBeInTheDocument();
  });

  it('says what happens after the quotation, in the order it happens', async () => {
    renderScreen(<QuotationCalculatorPage />, { route: '/agents/quotation' });

    expect(await screen.findByRole('heading', { name: 'What happens next' })).toBeInTheDocument();
    expect(screen.getByText(/give the customer the quotation PDF to take away\. Nothing is saved\./)).toBeInTheDocument();
    expect(screen.getByText(/returns it to you with what to correct/)).toBeInTheDocument();
  });
});
