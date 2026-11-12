import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as reportingApi from '@/api/reporting';
import type { ReportDefinition } from '@/api/reporting';
import { idle } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';
import { renderScreen } from '@/test/renderScreen';
import { FiguresGuidePage } from './FiguresGuidePage';

vi.mock('@/api/reporting', async (original) => ({
  ...(await original<typeof import('@/api/reporting')>()),
  getCatalogue: vi.fn(),
  getFigureDefinitions: vi.fn(),
}));

const collections = {
  key: 'premium-collections', title: 'Premium collections', description: 'Premiums collected in the period.',
  category: 'MANAGEMENT', dateFilterKind: 'PERIOD', groupByOptions: [], comparable: false, requiresAccount: false,
  comparesPeriods: true, sources: ['REPORTING_COPY', 'BILLING'], figures: [],
} as ReportDefinition;

beforeEach(() => {
  vi.clearAllMocks();
  useReportingStore.setState({ catalogue: idle() });
  vi.mocked(reportingApi.getCatalogue).mockResolvedValue([collections]);
  vi.mocked(reportingApi.getFigureDefinitions).mockResolvedValue([{
    title: 'Collections',
    figures: [{
      key: 'rate', name: 'Collection rate',
      meaning: 'Of the premiums that fell due in the period, the share that has been paid.',
      counts: 'Premiums paid ÷ premiums due, to one decimal. None when nothing fell due.',
      leavesOut: 'The same as Premiums due.', comesFrom: 'Billing.', dateBasis: 'As Premiums due.',
    }],
  }]);
});

describe('FiguresGuidePage', () => {
  it('explains each Collections figure, and where every report comes from', async () => {
    renderScreen(<FiguresGuidePage />);

    const rate = await screen.findByRole('article', { name: 'Collection rate' });
    expect(within(rate).getByText('Counts')).toBeInTheDocument();
    expect(within(rate).getByText('Premiums paid ÷ premiums due, to one decimal. None when nothing fell due.')).toBeInTheDocument();

    expect(await screen.findByRole('link', { name: 'Premium collections' })).toHaveAttribute('href', '/staff/reports/premium-collections');
    expect(screen.getByText("The reports' own copy, updated as things happen and billing")).toBeInTheDocument();
    expect(screen.getByText('Between two days')).toBeInTheDocument();
    expect(screen.getByText('The period before, or the same period last year')).toBeInTheDocument();
  });
});
