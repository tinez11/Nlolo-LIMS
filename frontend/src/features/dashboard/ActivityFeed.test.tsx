import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { describeActivity } from './activity';
import { ActivityFeed } from './ActivityFeed';

const item = (
  eventType: string,
  refs: Record<string, string> = {},
  amount: { amount: string; currencyCode: string } | null = null,
) => ({ eventType, occurredAt: '2026-10-14T08:00:00Z', refs, amount });

describe('describeActivity', () => {
  it('words a known event and links it by its reference', () => {
    expect(describeActivity(item('claims.ClaimSettled', { claimId: 'c-1' }))).toEqual({
      text: 'Claim settled',
      to: '/staff/claims/c-1',
    });
    expect(
      describeActivity(item('underwriting.UnderwritingDecisionMade', { caseId: 'u-1', outcome: 'LOADED' })),
    ).toEqual({ text: 'Case decided: accepted with a loading', to: '/staff/underwriting/u-1' });
    expect(describeActivity(item('billing.PremiumCollected', { policyNumber: 'POL-1' }))).toEqual({
      text: 'Premium collected',
      to: '/staff/policies/POL-1',
    });
  });

  /** An event the console has no words for is shown by its own name, never dropped. */
  it('humanises an event it does not know', () => {
    expect(describeActivity(item('payment.SomethingNewHappened'))).toEqual({ text: 'Something new happened' });
  });

  it('does not link without the reference it needs', () => {
    expect(describeActivity(item('claims.ClaimSettled'))).toEqual({ text: 'Claim settled' });
  });
});

describe('ActivityFeed', () => {
  /** The audit log records no actor, so the feed must say what happened -- and that it does not say who. */
  it('says it records what happened, not who', () => {
    render(
      <MemoryRouter>
        <ActivityFeed
          items={[item('claims.ClaimSettled', { claimId: 'c-1' }, { amount: '10000000.00', currencyCode: 'TZS' })]}
        />
      </MemoryRouter>,
    );

    expect(screen.getByText(/not who/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Claim settled' })).toHaveAttribute('href', '/staff/claims/c-1');
    expect(screen.getByText('TZS 10,000,000.00')).toBeInTheDocument();
  });

  it('says so when nothing has happened', () => {
    render(
      <MemoryRouter>
        <ActivityFeed items={[]} />
      </MemoryRouter>,
    );

    expect(screen.getByText('Nothing recorded yet.')).toBeInTheDocument();
  });
});
