import { describe, expect, it } from 'vitest';
import { claimNextStep } from './claimNextStep';

const death = (status: Parameters<typeof claimNextStep>[0]['claim']['status']) => ({ status, claimType: 'DEATH' as const });
const none = { canAssess: false, canDecide: false, viewerAssessed: false };

describe('claimNextStep', () => {
  it('tells someone who only registered it that an assessor, then a manager, must act', () => {
    const step = claimNextStep({ claim: death('REGISTERED'), ...none });
    expect(step.tone).toBe('wait');
    expect(step.text).toBe(
      'Waiting for a claims assessor to assess it, then for a claims manager to decide. Neither is something you can do on this claim.',
    );
  });

  it('tells an assessor it is theirs to assess', () => {
    expect(claimNextStep({ claim: death('REGISTERED'), ...none, canAssess: true }).tone).toBe('act');
  });

  it('tells whoever assessed it that someone else decides -- even if they could otherwise decide', () => {
    const step = claimNextStep({ claim: death('UNDER_ASSESSMENT'), canAssess: true, canDecide: true, viewerAssessed: true });
    expect(step.tone).toBe('wait');
    expect(step.text).toMatch(/another claims manager decides it/);
  });

  it('tells a manager who did not assess it that the decision is theirs', () => {
    expect(claimNextStep({ claim: death('UNDER_ASSESSMENT'), ...none, canDecide: true }).text).toBe(
      'Assessed. Next: decide the settlement under Settlement.',
    );
  });

  it('says a maturity claim goes straight to a decision', () => {
    const step = claimNextStep({ claim: { status: 'REGISTERED', claimType: 'MATURITY' }, ...none });
    expect(step.text).toMatch(/needs no assessment/);
  });

  it('says where the money is once approved, and that a settled claim is finished', () => {
    expect(claimNextStep({ claim: death('SETTLEMENT_REQUESTED'), ...none }).text).toMatch(/Payments to make/);
    expect(claimNextStep({ claim: death('SETTLED'), ...none }).tone).toBe('done');
  });
});
