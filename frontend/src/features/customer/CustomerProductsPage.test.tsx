import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as portalApi from '@/api/portal';
import type { CustomerApplication, CustomerProduct, CustomerQuote } from '@/api/portal';
import { CustomerApplicationsPage } from './CustomerApplicationsPage';
import { CustomerApplyPage } from './CustomerApplyPage';
import { CustomerProductsPage } from './CustomerProductsPage';
import { CustomerQuotePage } from './CustomerQuotePage';
import { useLanguageStore } from './language';
import { useApplyDraft } from './selfService';

vi.mock('@/api/portal');

const NO_TERMS = { minTermYears: null, maxTermYears: null, minAmount: null, maxAmount: null, depositTermMonths: [], funeralPlans: [], funeralRoles: [] };
const QUESTIONS = [
  { code: 'GOOD_HEALTH', en: 'Are you in good health and able to do your normal work?', sw: 'Je, una afya njema?' },
  { code: 'TOBACCO_12M', en: 'Have you used tobacco in the last 12 months?', sw: 'Je, umetumia tumbaku?' },
];
const term: CustomerProduct = {
  productId: 'p-term', productName: 'Online Term Cover', category: 'TERM_LIFE', currency: 'TZS',
  summary: 'Cover for your family if you die', benefits: ['Pays the sum assured on death'], quotable: true,
  kind: 'PROTECTION', online: true, terms: { ...NO_TERMS, minTermYears: 5, maxTermYears: 20 }, questions: QUESTIONS,
};
const deposit: CustomerProduct = {
  productId: 'p-ftd', productName: 'Nlolo Fixed Deposit', category: 'ENDOWMENT', currency: 'TZS', summary: null, benefits: [],
  quotable: true, kind: 'DEPOSIT', online: true, terms: { ...NO_TERMS, minAmount: 500000, depositTermMonths: [3, 6, 12] }, questions: [],
};
const annuity: CustomerProduct = {
  productId: 'p-ann', productName: 'Pensheni', category: 'ANNUITY', currency: 'TZS', summary: 'Income for life', benefits: [],
  quotable: false, kind: 'ANNUITY', online: false, terms: NO_TERMS, questions: [],
};
const termQuote: CustomerQuote = {
  productId: 'p-term', productName: 'Online Term Cover', kind: 'PROTECTION', currency: 'TZS', sumAssured: 10000000,
  frequency: 'MONTHLY', instalment: 18500, yearly: 222000, ageAtEntry: 30, termYears: 10, totalOverTerm: 2220000,
  paidByYear: [], deposit: null, funeral: [],
};

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/customers/products" element={<CustomerProductsPage />} />
        <Route path="/customers/products/:productId" element={<CustomerQuotePage />} />
        <Route path="/customers/products/:productId/apply" element={<CustomerApplyPage />} />
        <Route path="/customers/applications" element={<CustomerApplicationsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useLanguageStore.setState({ language: 'en' });
  useApplyDraft.setState({ draft: null });
  vi.mocked(portalApi.getCustomerProducts).mockResolvedValue([term, deposit, annuity]);
  vi.mocked(portalApi.getMe).mockResolvedValue({ partyId: 'me', displayName: 'Asha Juma', email: null, phoneNumber: '+255700000001' });
});

describe('Get cover', () => {
  it('starts from what the customer needs, and lists what an adviser prices apart', async () => {
    renderAt('/customers/products');
    const needs = await screen.findByRole('radiogroup', { name: 'What would you like to do?' });
    expect(within(needs).getAllByRole('radio').map((r) => r.textContent)).toEqual([
      expect.stringContaining('Protect my family'), expect.stringContaining('Fixed deposit'),
    ]);
    await userEvent.click(within(needs).getByRole('radio', { name: /Protect my family/ }));
    expect(screen.getByRole('link', { name: /Online Term Cover/ })).toHaveAttribute('href', '/customers/products/p-term');
    expect(screen.getByText('Pensheni')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ask an adviser' })).toBeInTheDocument();
  });

  it('speaks Swahili when the customer chooses it', async () => {
    useLanguageStore.setState({ language: 'sw' });
    renderAt('/customers/products');
    expect(await screen.findByRole('radiogroup', { name: 'Ungependa kufanya nini?' })).toBeInTheDocument();
    expect(screen.getByText('Linda familia yangu')).toBeInTheDocument();
  });

  it('prices cover one question at a time and shows what is paid against what the family gets', async () => {
    vi.mocked(portalApi.quoteCustomerProduct).mockResolvedValue(termQuote);
    renderAt('/customers/products/p-term');

    expect(await screen.findByRole('heading', { name: 'How much should your family receive?' })).toBeInTheDocument();
    expect(screen.getByText('1 of 3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: /^10,000,000/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    // The version's own term bounds: 5 to 20 years.
    expect(screen.getAllByRole('radio').map((r) => r.textContent)).toEqual(['5 years', '10 years', '15 years', '20 years']);
    await userEvent.click(screen.getByRole('radio', { name: '10 years' }));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await userEvent.click(screen.getByRole('button', { name: 'Here is your price' }));

    expect(portalApi.quoteCustomerProduct).toHaveBeenCalledWith('p-term', { sumAssured: 10000000, frequency: 'MONTHLY', termYears: 10 });
    expect(await screen.findByText('TZS 18,500.00')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'The picture' })).toHaveTextContent('All you payTZS 2,220,000.00');
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled();
  });

  it('prices a deposit from its own terms, never as cover', async () => {
    renderAt('/customers/products/p-ftd');
    expect(await screen.findByRole('heading', { name: 'How much do you want to deposit?' })).toBeInTheDocument();
    expect(screen.getByText('Paid once. The smallest deposit is TZS 500,000.00.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: /^1,000,000/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getAllByRole('radio').map((r) => r.textContent)).toEqual(['3 months', '6 months', '1 year']);
  });
});

describe('Apply', () => {
  it('sends who receives the money and the health answers, with a few words where one worries', async () => {
    useApplyDraft.setState({ draft: { product: term, request: { sumAssured: 10000000, frequency: 'MONTHLY', termYears: 10 }, quote: termQuote } });
    vi.mocked(portalApi.applyForProduct).mockResolvedValue({} as CustomerApplication);
    renderAt('/customers/products/p-term/apply');

    expect(await screen.findByText('Asha Juma')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));

    await userEvent.type(screen.getByLabelText('Full name'), 'Neema Juma');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));

    const tobacco = screen.getByRole('radiogroup', { name: QUESTIONS[1]!.en });
    await userEvent.click(within(screen.getByRole('radiogroup', { name: QUESTIONS[0]!.en })).getByRole('radio', { name: 'Yes' }));
    await userEvent.click(within(tobacco).getByRole('radio', { name: 'Yes' }));
    // A yes to tobacco needs a few words before the next step.
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Tell us a little more'), 'Two cigarettes a day');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(screen.getByText('Neema Juma (100%)')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Send my application' }));
    expect(portalApi.applyForProduct).toHaveBeenCalledWith({
      productId: 'p-term', sumAssured: 10000000, frequency: 'MONTHLY', termYears: 10,
      beneficiaries: [{ name: 'Neema Juma', relationship: null, phone: null, sharePercent: 100 }],
      answers: [{ code: 'GOOD_HEALTH', answer: 'YES', notes: null }, { code: 'TOBACCO_12M', answer: 'YES', notes: 'Two cigarettes a day' }],
      language: 'EN',
    });
    expect(await screen.findByRole('heading', { name: 'Application sent' })).toBeInTheDocument();
  }, 20_000);

  it('sends the customer back to the price when there is none to apply on', async () => {
    renderAt('/customers/products/p-term/apply');
    expect(await screen.findByRole('heading', { name: 'Start from the price first' })).toBeInTheDocument();
  });
});

describe('My applications', () => {
  it('shows where each application is on its line, and what happens next', async () => {
    vi.mocked(portalApi.getCustomerApplications).mockResolvedValue([
      { caseId: 'c1', proposalNumber: 'PRP-1', productId: 'p-term', productName: 'Online Term Cover', sumAssured: 10000000,
        currency: 'TZS', status: 'DECIDED', statusText: 'Accepted — your offer is being prepared', stage: 'ACCEPTED', decidedAt: '2026-10-28T09:00:00Z' },
    ]);
    renderAt('/customers/applications');
    const card = await screen.findByRole('article');
    expect(within(card).getByRole('listitem', { current: 'step' })).toHaveTextContent('Pay first premium');
    expect(card).toHaveTextContent('Accepted. Your offer is being prepared');
    expect(within(card).getByRole('link', { name: 'Pay now' })).toHaveAttribute('href', '/customers/pay');
  });
});
