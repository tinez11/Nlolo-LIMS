import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '@/api/organisation';
import type { OrganisationSettings } from '@/api/organisation';
import { OrganisationPage } from './OrganisationPage';

vi.mock('@/api/organisation');
vi.mock('@/store/organisationStore', () => ({
  useOrganisationStore: (pick: (s: { loadBranches: () => Promise<void> }) => unknown) => pick({ loadBranches: async () => {} }),
  useBranchCodes: () => [{ code: 'DSM', label: 'Dar es Salaam' }, { code: 'ARU', label: 'Arusha' }],
}));

const SETTINGS: OrganisationSettings = {
  profile: { legalName: 'Nlolo Life', tradingName: null, registrationNumber: null, tin: null, address: null, phone: null,
    email: null, website: null, tagline: null, headOfficeBranch: 'DSM' },
  branding: { primaryColour: '#17324D', secondaryColour: '#0E8C7F', typeface: 'SANS', layout: 'CLASSIC', paper: 'A4',
    footerText: null, reportConfidentiality: 'Confidential.', hasLogo: false },
  signatories: [],
  branches: [
    { code: 'DSM', name: 'Dar es Salaam', address: null, phone: null, email: null, manager: null, active: true, headOffice: true },
    { code: 'ARU', name: 'Arusha', address: null, phone: null, email: null, manager: null, active: true, headOffice: false },
  ],
};

function renderPage(tab?: string) {
  render(
    <MemoryRouter initialEntries={[`/staff/organisation${tab ? `?tab=${tab}` : ''}`]}>
      <OrganisationPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getOrganisationSettings).mockResolvedValue(SETTINGS);
  vi.mocked(api.previewDocument).mockResolvedValue(new Blob(['%PDF'], { type: 'application/pdf' }));
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:preview', revokeObjectURL: () => {} }));
});

/** 2026-11-09, the user: "give the organization more freedom to market themselves in documents". */
// Typed keystroke by keystroke; under a full run's load these pass 5 s.
describe('the organisation settings', { timeout: 15_000 }, () => {
  it('saves the company details and the chosen layout', async () => {
    const user = userEvent.setup();
    vi.mocked(api.saveProfile).mockImplementation(async (p) => p);
    vi.mocked(api.saveBranding).mockImplementation(async (b) => b);
    renderPage();

    const name = await screen.findByLabelText(/Legal name/);
    await user.clear(name);
    await user.type(name, 'Bima Bora Life Assurance Ltd');
    await user.click(screen.getByRole('button', { name: 'Save company details' }));
    expect(api.saveProfile).toHaveBeenCalledWith(expect.objectContaining({ legalName: 'Bima Bora Life Assurance Ltd' }));
    expect(await screen.findByText('Saved. New documents use it now.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^Banded/ }));
    await user.click(screen.getByRole('button', { name: 'Save branding' }));
    expect(api.saveBranding).toHaveBeenCalledWith(expect.objectContaining({ layout: 'BANDED' }));
  });

  it('says how to write a colour it cannot use', async () => {
    const user = userEvent.setup();
    renderPage();
    const main = await screen.findByLabelText('Main colour');
    await user.clear(main);
    await user.type(main, 'navy');
    expect(screen.getByText('Write it as #RRGGBB, for example #17324D.')).toBeInTheDocument();
  });

  it('adds a signatory and what they sign', async () => {
    const user = userEvent.setup();
    vi.mocked(api.addSignatory).mockResolvedValue({ signatoryId: 's-1', name: 'Neema Mushi', title: 'Head of Finance',
      effectiveFrom: '2026-11-09', retiredOn: null, signs: ['RECEIPT'], hasSignature: false });
    renderPage('signatories');

    const form = await screen.findByRole('heading', { name: 'Add a signatory' });
    const panel = form.closest('section')!;
    await user.type(within(panel).getByLabelText(/^Name/), 'Neema Mushi');
    await user.type(within(panel).getByLabelText(/^Title/), 'Head of Finance');
    await user.click(within(panel).getByLabelText('Receipts'));
    await user.click(within(panel).getByRole('button', { name: 'Add the signatory' }));
    expect(api.addSignatory).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Neema Mushi', title: 'Head of Finance', signs: ['RECEIPT'] }));
  });

  it('lists the branches with the head office marked, and adds one', async () => {
    const user = userEvent.setup();
    vi.mocked(api.saveBranch).mockImplementation(async (b) => b);
    renderPage('branches');

    const table = await screen.findByRole('table', { name: 'Branches' });
    expect(within(table).getByText('Head office')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add a branch' }));
    await user.type(screen.getByLabelText(/^Code/), 'mwz');
    await user.type(screen.getByLabelText(/^Name/), 'Mwanza');
    await user.click(screen.getByRole('button', { name: 'Add the branch' }));
    expect(api.saveBranch).toHaveBeenCalledWith(expect.objectContaining({ code: 'MWZ', name: 'Mwanza', active: true }));
  });
});
