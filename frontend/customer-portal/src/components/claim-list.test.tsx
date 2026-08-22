import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ClaimList } from './claim-list';

const CLAIM = {
  claimId: '22222222-2222-2222-2222-222222222222',
  policyNumber: 'POL-1', claimType: 'DISABILITY' as const,
  status: 'UNDER_ASSESSMENT' as const, dateOfEvent: '2026-06-01',
  requiresContestabilityReview: false,
};

describe('ClaimList', () => {
  it('renders claim rows with type and status', () => {
    render(<ClaimList claims={[CLAIM]} />);
    expect(screen.getByText(/disability/i)).toBeInTheDocument();
    expect(screen.getByText(/under assessment/i)).toBeInTheDocument();
  });

  it('shows an approved amount when the backend supplies one', () => {
    render(<ClaimList claims={[{ ...CLAIM, status: 'APPROVED', approvedAmount: { amount: '4500000', currencyCode: 'TZS' } }]} />);
    expect(screen.getByText('TZS 4,500,000.00')).toBeInTheDocument();
  });

  it('does not expose the internal contestability-review flag as scary copy', () => {
    // requiresContestabilityReview is an internal assessment signal, not a customer-facing verdict.
    render(<ClaimList claims={[{ ...CLAIM, requiresContestabilityReview: true }]} />);
    expect(screen.queryByText(/contestability/i)).not.toBeInTheDocument();
  });

  it('shows an empty state', () => {
    render(<ClaimList claims={[]} />);
    expect(screen.getByText(/no claims/i)).toBeInTheDocument();
  });
});
