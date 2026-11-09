import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as reporting from '@/api/reporting';
import type { SolvencyFactorSet } from '@/api/reporting';
import { grantAccess } from '@/test/access';
import { SolvencyFactorsPage } from './SolvencyFactorsPage';

vi.mock('@/api/reporting');

beforeEach(() => vi.clearAllMocks());

const BUILT_IN: SolvencyFactorSet = {
  version: 0,
  name: 'Placeholder risk charges (built in)',
  effectiveFrom: '1900-01-01',
  placeholder: true,
  createdBy: null,
  createdAt: null,
  factors: {
    insurance: [{ key: 'life', label: 'Life contracts (GMM and VFA)', accounts: ['2110', '2120'], percent: 4 }],
    market: [{ key: 'equities', label: 'Listed equities', accounts: ['1230'], percent: 25 }],
    credit: [{ key: 'receivables', label: 'Receivables outside IFRS 17', accounts: ['1300'], percent: 5 }],
    operationalPercent: 3,
    inadmissible: ['1610'],
    combination: 'ROOT_SUM_OF_SQUARES',
    thresholds: [{ key: 'MCR', label: 'Minimum capital requirement (MCR)', ratioPercent: 100 }],
    shocks: [
      { key: 'equities', label: 'Equities fall 20%', kind: 'ASSETS', accounts: ['1230'], percent: -20, amount: null },
      { key: 'cat', label: 'A catastrophe claim', kind: 'LOSS', accounts: [], percent: null, amount: 100000000 },
    ],
  },
};

function renderPage() {
  return render(<MemoryRouter><SolvencyFactorsPage /></MemoryRouter>);
}

/** 2026-11-09, decision D5: placeholder factors until TIRA's guideline is loaded -- and said so. */
describe('the solvency factors page', () => {
  it('shows the factors in use and says they are placeholders', async () => {
    vi.mocked(reporting.listSolvencyFactorSets).mockResolvedValue([BUILT_IN]);
    grantAccess('reports.view');
    renderPage();

    expect(await screen.findByText(/Placeholder risk charges, not TIRA/)).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Insurance risk' })).toHaveTextContent('Life contracts (GMM and VFA)');
    expect(screen.getByRole('table', { name: 'Market risk' })).toHaveTextContent('25%');
    expect(screen.getByRole('table', { name: 'Stress shocks' })).toHaveTextContent('-20%');
    // Reading is not publishing.
    expect(screen.queryByRole('button', { name: 'New version' })).not.toBeInTheDocument();
  });

  it('publishes a new version with what was typed, as numbers', async () => {
    vi.mocked(reporting.listSolvencyFactorSets).mockResolvedValue([BUILT_IN]);
    vi.mocked(reporting.publishSolvencyFactorSet).mockResolvedValue({ ...BUILT_IN, version: 1 });
    grantAccess('reports.view', 'reports.solvency-factors');
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New version' }));
    await userEvent.type(screen.getByLabelText('Name *'), 'TIRA RBC 2025');
    await userEvent.click(screen.getByLabelText("These are placeholders, not the regulator's factors"));
    const equities = screen.getByLabelText(/^Listed equities/);
    await userEvent.clear(equities);
    await userEvent.type(equities, '30');
    await userEvent.click(screen.getByRole('button', { name: 'Publish version' }));

    expect(reporting.publishSolvencyFactorSet).toHaveBeenCalledTimes(1);
    const sent = vi.mocked(reporting.publishSolvencyFactorSet).mock.calls[0]![0];
    expect(sent.name).toBe('TIRA RBC 2025');
    expect(sent.placeholder).toBe(false);
    expect(sent.factors.market[0]!.percent).toBe(30);
    expect(sent.factors.shocks[1]).toMatchObject({ kind: 'LOSS', amount: 100000000, percent: null });
  });
});
