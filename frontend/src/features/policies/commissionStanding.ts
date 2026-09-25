import type { AgentView, CommissionPlanView } from '@/api/types';

/**
 * A plan rate, stored as the fraction the calculator multiplies premium by ("0.1250"), as the
 * percentage a person agreed ("12.5%"). String arithmetic -- the decimal point moved two places
 * -- so no double ever touches a commission rate.
 */
export function ratePercentLabel(rate: string | null | undefined): string | null {
  if (!rate || !/^\d+(\.\d+)?$/.test(rate)) return null;
  const [whole = '0', fraction = ''] = rate.split('.');
  const padded = fraction.padEnd(2, '0');
  const integer = String(Number(whole + padded.slice(0, 2)));
  const rest = padded.slice(2).replace(/0+$/, '');
  return `${integer}${rest ? `.${rest}` : ''}%`;
}

/** The FIRST_YEAR rate of a plan -- the only tier a single premium ever earns. */
export function firstYearRate(plan: CommissionPlanView | null | undefined): string | null {
  return plan?.rules?.find((r) => r.tierType === 'FIRST_YEAR')?.rate ?? null;
}

export type CommissionStanding =
  | { kind: 'earning'; ratePercent: string }
  | { kind: 'no-agent' }
  | { kind: 'not-the-lender' }
  | { kind: 'no-rate' };

/**
 * Whether this scheme will earn commission on its next file, and if not, the FIRST thing in the
 * way. Commission that silently came to zero was the defect: 67 of 72 schemes had no agent, the
 * rest had the wrong one, and no credit-life product had a plan -- all of it invisible.
 */
export function commissionStanding(input: {
  agentOfRecordId: string | null | undefined;
  lenderAgent: AgentView | null;
  plan: CommissionPlanView | null;
}): CommissionStanding {
  if (!input.agentOfRecordId) return { kind: 'no-agent' };
  if (!input.lenderAgent || input.lenderAgent.agentId !== input.agentOfRecordId) {
    return { kind: 'not-the-lender' };
  }
  const label = ratePercentLabel(firstYearRate(input.plan));
  return label ? { kind: 'earning', ratePercent: label } : { kind: 'no-rate' };
}
