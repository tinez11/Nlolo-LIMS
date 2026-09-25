import { describe, expect, it } from 'vitest';
import type { AgentView, CommissionPlanView } from '@/api/types';
import { commissionStanding, ratePercentLabel } from './commissionStanding';

const plan = (rate: string) =>
  ({ planId: 'p', rules: [{ tierType: 'FIRST_YEAR', rate }] }) as unknown as CommissionPlanView;
const lender = { agentId: 'lender-agent', partyId: 'lender-party' } as AgentView;

describe('ratePercentLabel', () => {
  it('shows the stored fraction as the percentage agreed', () => {
    expect(ratePercentLabel('0.1250')).toBe('12.5%');
    expect(ratePercentLabel('0.1000')).toBe('10%');
    expect(ratePercentLabel('0.0075')).toBe('0.75%');
    expect(ratePercentLabel('1.0000')).toBe('100%');
  });

  it('shows nothing for a rate it cannot read', () => {
    expect(ratePercentLabel(null)).toBeNull();
    expect(ratePercentLabel('abc')).toBeNull();
  });
});

describe('commissionStanding', () => {
  it('earns when the lender is the agent and has a rate', () => {
    expect(commissionStanding({ agentOfRecordId: 'lender-agent', lenderAgent: lender, plan: plan('0.1250') }))
      .toEqual({ kind: 'earning', ratePercent: '12.5%' });
  });

  it('says a scheme with no agent earns nothing', () => {
    expect(commissionStanding({ agentOfRecordId: null, lenderAgent: lender, plan: plan('0.10') }).kind).toBe('no-agent');
  });

  it('flags an agent who is not the lender -- how GRP-B6CE9639 came to carry an individual', () => {
    expect(commissionStanding({ agentOfRecordId: 'someone-else', lenderAgent: lender, plan: plan('0.10') }).kind)
      .toBe('not-the-lender');
    expect(commissionStanding({ agentOfRecordId: 'someone-else', lenderAgent: null, plan: null }).kind)
      .toBe('not-the-lender');
  });

  it('flags the lender with no rate set', () => {
    expect(commissionStanding({ agentOfRecordId: 'lender-agent', lenderAgent: lender, plan: null }).kind).toBe('no-rate');
  });
});
