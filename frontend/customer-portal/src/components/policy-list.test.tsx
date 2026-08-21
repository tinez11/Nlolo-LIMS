import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PolicyList } from './policy-list';

const POLICY = {
  policyNumber: 'POL-000123',
  status: 'ACTIVE' as const,
  issueDate: '2025-03-01',
  sumAssured: { amount: '50000000', currencyCode: 'TZS' },
  premium: { amount: '125000.50', currencyCode: 'TZS' },
  premiumFrequency: 'MONTHLY' as const,
  cashValue: { amount: '0', currencyCode: 'TZS' },
};

describe('PolicyList', () => {
  it('renders a policy card with formatted money', () => {
    render(<PolicyList policies={[POLICY]} />);
    expect(screen.getByText('POL-000123')).toBeInTheDocument();
    expect(screen.getByText('TZS 50,000,000.00')).toBeInTheDocument();
    expect(screen.getByText('TZS 125,000.50')).toBeInTheDocument();
  });

  it('renders the zero cash value as-is, with no caveat text', () => {
    render(<PolicyList policies={[POLICY]} />);
    expect(screen.getByText('TZS 0.00')).toBeInTheDocument();
    expect(screen.queryByText(/not yet available/i)).not.toBeInTheDocument();
  });

  it('shows an empty state rather than a blank page', () => {
    render(<PolicyList policies={[]} />);
    expect(screen.getByText(/no policies/i)).toBeInTheDocument();
  });
});
