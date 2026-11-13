import type { CommissionPlanView } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { fractionToPercent } from './createCommissionPlanForm';

type CommissionRule = CommissionPlanView['rules'][number];

/** `FIRST_YEAR` → `First year`. */
export const tierLabel = (tier: string) => {
  const words = tier.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
};

export const ruleValue = (rule: CommissionRule) =>
  rule.flatAmount ? formatMoney(rule.flatAmount) : rule.rate == null ? '—' : `${fractionToPercent(rule.rate)}% of the premium`;

/** "First year: 10% of the premium · Renewal: 3% of the premium" */
export const describeRules = (plan: CommissionPlanView) =>
  plan.rules.map((r) => `${tierLabel(r.tierType)}: ${ruleValue(r)}`).join(' · ');
