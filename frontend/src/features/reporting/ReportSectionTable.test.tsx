import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ReportSection } from '@/api/reporting';
import { renderScreen } from '@/test/renderScreen';
import { ReportSectionTable } from './ReportSectionTable';

const section: ReportSection = {
  title: 'Policies',
  columns: [
    { key: 'policyNumber', label: 'Policy', type: 'TEXT', link: 'POLICY' },
    { key: 'client', label: 'Client', type: 'TEXT', link: 'CLIENT' },
    { key: 'premium', label: 'Premium', type: 'MONEY', link: null },
  ],
  rows: [{ policyNumber: 'POL-1', client: 'Asha Juma', _clientId: 'party-1', premium: '15000.00' }],
  totals: { policyNumber: 'Total', client: '', premium: '15000.00' },
};

describe('ReportSectionTable', () => {
  it('opens a policy and its client from their cells, and leaves the total row plain', () => {
    renderScreen(<ReportSectionTable section={section} />);

    expect(screen.getByRole('link', { name: 'POL-1' })).toHaveAttribute('href', '/staff/policies/POL-1');
    expect(screen.getByRole('link', { name: 'Asha Juma' })).toHaveAttribute('href', '/staff/parties/party-1');
    const total = screen.getByText('Total').closest('tr') as HTMLElement;
    expect(within(total).queryByRole('link')).not.toBeInTheDocument();
  });
});
