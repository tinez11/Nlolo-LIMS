import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import type { ReportDefinition } from '@/api/reporting';
import { grantAccess } from '@/test/access';
import { ReportList } from './ReportList';
import { reportAreas } from './reportAreas';

const report = (key: string, title: string, category: ReportDefinition['category']): ReportDefinition => ({
  key, title, description: `${title} description`, category, dateFilterKind: 'MONTH', groupByOptions: [],
  comparable: false, requiresAccount: false, comparesPeriods: category === 'ACCOUNTING',
});

const CATALOGUE = [
  report('income-statement', 'Income statement', 'ACCOUNTING'),
  report('balance-sheet', 'Balance sheet', 'ACCOUNTING'),
  report('trial-balance', 'Trial balance', 'ACCOUNTING'),
  report('policies-in-force', 'Policies in force', 'MANAGEMENT'),
  report('clients', 'Clients', 'MANAGEMENT'),
];

beforeEach(() => grantAccess('reports.view'));

function list(currentKey: string | null) {
  render(<MemoryRouter><ReportList areas={reportAreas(CATALOGUE)} currentKey={currentKey} /></MemoryRouter>);
  return screen.getByRole('navigation', { name: 'Reports' });
}

/** 2026-11-09, the user: one menu item; inside, the areas, each closed until opened. */
describe('the report list', () => {
  it('starts with every area closed except the one holding the open report', () => {
    const nav = list('balance-sheet');
    expect(within(nav).getByRole('button', { name: /Accounting/ })).toHaveAttribute('aria-expanded', 'true');
    expect(within(nav).getByRole('button', { name: /Policies/ })).toHaveAttribute('aria-expanded', 'false');
    expect(within(nav).getByRole('link', { name: 'Balance sheet' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).queryByRole('link', { name: 'Policies in force' })).not.toBeInTheDocument();
  });

  it('opens an area when its heading is pressed', async () => {
    const nav = list(null);
    await userEvent.click(within(nav).getByRole('button', { name: /Policies/ }));
    expect(within(nav).getByRole('link', { name: 'Policies in force' })).toHaveAttribute('href', '/staff/reports/policies-in-force');
  });

  it('searches every area and shows only the matches', async () => {
    const nav = list(null);
    await userEvent.type(within(nav).getByRole('textbox', { name: 'Search reports' }), 'balance');
    expect(within(nav).getByRole('link', { name: 'Balance sheet' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Trial balance' })).toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Income statement' })).not.toBeInTheDocument();
    expect(within(nav).queryByRole('button', { name: /Clients/ })).not.toBeInTheDocument();
  });

  it('lists a report on another page only for someone who may open it', () => {
    grantAccess('reports.view', 'ifrs17.view');
    const withIfrs = reportAreas(CATALOGUE).find((a) => a.id === 'accounting')!;
    expect(withIfrs.entries.map((e) => e.title)).toContain('IFRS 17 statements');

    grantAccess('reports.view');
    const without = reportAreas(CATALOGUE).find((a) => a.id === 'accounting')!;
    expect(without.entries.map((e) => e.title)).not.toContain('IFRS 17 statements');
  });
});

describe('the claims area', () => {
  it('holds the claims reports under Claims', () => {
    const claims = reportAreas([...CATALOGUE, report('claims-incurred', 'Claims incurred variance', 'CLAIMS')])
      .find((a) => a.id === 'claims');
    expect(claims?.entries.map((e) => e.title)).toEqual(['Claims incurred variance']);
  });
});
