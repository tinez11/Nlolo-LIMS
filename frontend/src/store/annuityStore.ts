import { create } from 'zustand';
import { getAnnuityChoice, getPolicyAnnuity } from '@/api/annuity';
import {
  approveWithholdingRule,
  listWithholdingRules,
  proposeWithholdingRule,
  withdrawWithholdingRule,
  type WithholdingRuleBody,
} from '@/api/withholding';
import type { AnnuityChoiceView, AnnuityContractView, WithholdingRuleView } from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Immediate annuities and tax withholding (product step 5): one policy's annuity contract, and the
 * tenant's withholding rules.
 *
 * Keyed as bonusStore is. The contract resource holds `null` for a policy that is not an annuity -- an
 * answer, not an error -- which is how the policy page knows not to show the Annuity tab. After every
 * mutation the rules are reread rather than patched.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface AnnuityState {
  contract: Keyed<AnnuityContractView | null>;
  /** An underwriting case's annuity choice; null for a case that is not an annuity. */
  choice: Keyed<AnnuityChoiceView | null>;
  rules: Resource<WithholdingRuleView[]>;
  acting: Keyed<unknown>;

  loadContract: (policyNumber: string) => Promise<void>;
  loadChoice: (caseId: string) => Promise<void>;
  loadRules: () => Promise<void>;
  proposeRule: (body: WithholdingRuleBody, attempt: MutationAttempt) => Promise<void>;
  approveRule: (ruleId: string) => Promise<void>;
  withdrawRule: (ruleId: string) => Promise<void>;
}

export const useAnnuityStore = create<AnnuityState>((set, getState) => {
  /** Run one mutation under its own `acting` slot, then reread what it changed. */
  /** track() never rethrows: a failure lands on acting[key], and this resolves undefined. */
  const act = <T>(key: string, call: () => Promise<T>): Promise<T | undefined> => {
    let result: T | undefined;
    return track(
      `annuity.act.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
      },
    ).then(() => result);
  };

  return {
    contract: {},
    choice: {},
    rules: idle(),
    acting: {},

    loadContract: (policyNumber) =>
      track(
        `annuity.contract.${policyNumber}`,
        getState().contract[policyNumber] ?? idle(),
        (next) => set((s) => ({ contract: { ...s.contract, [policyNumber]: next } })),
        () => getPolicyAnnuity(policyNumber),
      ),
    loadChoice: (caseId) =>
      track(
        `annuity.choice.${caseId}`,
        getState().choice[caseId] ?? idle(),
        (next) => set((s) => ({ choice: { ...s.choice, [caseId]: next } })),
        () => getAnnuityChoice(caseId),
      ),
    loadRules: () => track('annuity.rules', getState().rules, (next) => set({ rules: next }), () => listWithholdingRules()),

    proposeRule: (body, attempt) =>
      act('rule.propose', async () => {
        await proposeWithholdingRule(body, attempt);
        await getState().loadRules();
      }),
    approveRule: (ruleId) =>
      act(ruleId, async () => {
        await approveWithholdingRule(ruleId);
        await getState().loadRules();
      }),
    withdrawRule: (ruleId) =>
      act(ruleId, async () => {
        await withdrawWithholdingRule(ruleId);
        await getState().loadRules();
      }),
  };
});
