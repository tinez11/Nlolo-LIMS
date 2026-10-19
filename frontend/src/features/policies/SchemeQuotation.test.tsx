import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadSchemeQuotation } from '@/api/documents';
import { quoteGroupFuneral } from '@/api/groupFuneral';
import type { GroupFuneralQuoteView } from '@/api/types';
import * as download from '@/lib/download';
import { SchemeQuotation } from './SchemeQuotation';
import type { FamilyRow } from './groupFuneral';

vi.mock('@/api/groupFuneral', async (original) => ({
  ...(await original<typeof import('@/api/groupFuneral')>()),
  quoteGroupFuneral: vi.fn(),
}));
vi.mock('@/api/documents');
vi.mock('@/lib/download', () => ({ saveBlob: vi.fn() }));

const martin: FamilyRow = {
  reference: 'M001',
  mainName: 'Martin Lema',
  mainDateOfBirth: '1992-10-01',
  mainSex: '',
  beneficiaryName: '',
  beneficiaryRelationship: '',
  beneficiaryPhone: '',
  dependants: [
    { role: 'SPOUSE', fullName: 'Gorgina', dateOfBirth: '2002-10-01', sex: '', student: false },
    { role: 'EXTENDED', fullName: 'Martin Fredii', dateOfBirth: '1988-10-17', sex: '', student: false },
  ],
};

const QUOTE: GroupFuneralQuoteView = {
  planCode: 'NLevel2',
  rate: 140000,
  period: 'YEARLY',
  members: 1,
  bill: 140000,
  totalCover: 6000000,
  families: [{ reference: 'M001', mainMember: 'Martin Lema', lives: 2, cover: 6000000, problems: [] }],
  problems: [],
};

const renderPanel = (families: FamilyRow[] = [martin], association = 'assoc-1') =>
  render(
    <SchemeQuotation
      productId="prod-1"
      versionId="ver-12345678"
      planCode="NLevel2"
      commencement=""
      families={families}
      associationPartyId={association}
      currency="TZS"
    />,
  );

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(quoteGroupFuneral).mockResolvedValue(QUOTE);
});

/**
 * The scheme screen's Quotation panel (2026-10-19). The bill used to be "members x rate" multiplied on the page; it is
 * now the server's, and a family the plan will not take is named on its member while the scheme is written.
 */
describe('SchemeQuotation', () => {
  it("shows the server's bill and each family's cover, never one it multiplied", async () => {
    renderPanel();

    expect(await screen.findByText('Martin Lema')).toBeInTheDocument();
    expect(screen.getByLabelText('The bill')).toHaveTextContent('TZS 140,000.00');
    expect(screen.getByLabelText('The bill')).toHaveTextContent('a year');
    expect(screen.getAllByText('TZS 6,000,000.00').length).toBeGreaterThan(0);
  });

  it('sends each member with its lives, the main member first', async () => {
    renderPanel();

    await waitFor(() => expect(quoteGroupFuneral).toHaveBeenCalled());
    const [, , body] = vi.mocked(quoteGroupFuneral).mock.calls[0]!;
    expect(body.planCode).toBe('NLevel2');
    expect(body.families).toEqual([
      {
        memberReference: 'M001',
        lives: [
          { role: 'MAIN_MEMBER', name: 'Martin Lema', dateOfBirth: '1992-10-01', student: false },
          { role: 'SPOUSE', name: 'Gorgina', dateOfBirth: '2002-10-01', student: false },
          { role: 'EXTENDED', name: 'Martin Fredii', dateOfBirth: '1988-10-17', student: false },
        ],
      },
    ]);
  });

  /** The user's case: extended family on a plan that pays nothing for it, named before the scheme is submitted. */
  it('names a family member the plan does not cover, and offers no quotation to sign', async () => {
    vi.mocked(quoteGroupFuneral).mockResolvedValue({
      ...QUOTE,
      totalCover: null,
      families: [{ reference: 'M001', mainMember: 'Martin Lema', lives: 3, cover: null,
        problems: ['Martin Fredii: plan NLevel2 does not cover extended family'] }],
      problems: ['Member M001: Martin Fredii: plan NLevel2 does not cover extended family'],
    });
    renderPanel();

    expect(await screen.findByText('Martin Fredii: plan NLevel2 does not cover extended family')).toBeInTheDocument();
    expect(screen.getByText('Not until every family can join')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download the quotation as PDF' })).toBeDisabled();
  });

  it("downloads the association's quotation from the inputs, not the figures", async () => {
    vi.mocked(downloadSchemeQuotation).mockResolvedValue(new Blob(['pdf']));
    renderPanel();

    await screen.findByText('Martin Lema');
    await userEvent.click(screen.getByRole('button', { name: 'Download the quotation as PDF' }));

    await waitFor(() => expect(download.saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'scheme-quotation-ver-1234.pdf'));
    const sent = vi.mocked(downloadSchemeQuotation).mock.calls[0]![0];
    expect(sent).toMatchObject({ productId: 'prod-1', productVersionId: 'ver-12345678', associationPartyId: 'assoc-1',
      planCode: 'NLevel2' });
    expect(JSON.stringify(sent)).not.toContain('140000');
  });

  it('waits for the association before the quotation can be downloaded', async () => {
    renderPanel([martin], '');

    await screen.findByText('Martin Lema');
    expect(screen.getByRole('button', { name: 'Download the quotation as PDF' })).toBeDisabled();
  });
});
