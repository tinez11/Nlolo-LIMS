import { describe, expect, it } from 'vitest';
import type { AgentView } from '@/api/types';
import { commissionStanding } from './commissionStanding';

const lender = { agentId: 'lender-agent', partyId: 'lender-party' } as AgentView;

describe('commissionStanding', () => {
  it('earns the agreement in force’s commission when the lender is the agent', () => {
    expect(commissionStanding({ agentOfRecordId: 'lender-agent', lenderAgent: lender, agreedCommissionPercent: '12.50' }))
      .toEqual({ kind: 'earning', ratePercent: '12.5%' });
  });

  it('says a scheme with no agent earns nothing', () => {
    expect(commissionStanding({ agentOfRecordId: null, lenderAgent: lender, agreedCommissionPercent: '10' }).kind)
      .toBe('no-agent');
  });

  it('flags an agent who is not the lender -- how GRP-B6CE9639 came to carry an individual', () => {
    expect(commissionStanding({ agentOfRecordId: 'someone-else', lenderAgent: lender, agreedCommissionPercent: '10' })
      .kind).toBe('not-the-lender');
    expect(commissionStanding({ agentOfRecordId: 'someone-else', lenderAgent: null, agreedCommissionPercent: null })
      .kind).toBe('not-the-lender');
  });

  it('earns nothing where the agreement pays no commission, or no agreement covers today', () => {
    expect(commissionStanding({ agentOfRecordId: 'lender-agent', lenderAgent: lender, agreedCommissionPercent: '0.00' })
      .kind).toBe('no-commission');
    expect(commissionStanding({ agentOfRecordId: 'lender-agent', lenderAgent: lender, agreedCommissionPercent: null })
      .kind).toBe('no-commission');
  });
});
