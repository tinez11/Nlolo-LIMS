import { create } from 'zustand';
import {
  getAnnuityChoice,
  getDeferredAnnuityChoice,
  getPolicyAnnuity,
  getPolicyVesting,
  listHeldVestings,
  reconfirmVestingAge,
  recordVestingInstruction,
} from '@/api/annuity';
import {
  approveWithholdingRule,
  endWithholdingRule,
  listWithholdingRules,
  proposeWithholdingRule,
  withdrawWithholdingRule,
  type WithholdingRuleBody,
} from '@/api/withholding';
import type {
  AnnuityChoiceView,
  AnnuityContractView,
  DeferredAnnuityChoiceView,
  VestingInstructionInput,
  VestingView,
  WithholdingRuleView,
} from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Immediate annuities and tax withholding (product step 5): one policy's annuity contract, and the
 * tenant's withholding rules. Deferred annuities (D2): a policy's vesting, a case's retirement age,
 * and the held vestings.
 *
 * Keyed as bonusStore is. The contract resource holds `null` for a policy that is not an annuity -- an
 * answer, not an error -- which is how the policy page knows not to show the Annuity tab; the vesting
 * resource holds `null` for a policy that is not a deferred annuity. After every mutation what it
 * changed is reread rather than patched.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface AnnuityState {
  contract: Keyed<AnnuityContractView | null>;
  /** An underwriting case's annuity choice; null for a case that is not an annuity. */
  choice: Keyed<AnnuityChoiceView | null>;
  /** A deferred annuity case's retirement age; null for any other case. */
  deferredChoice: Keyed<DeferredAnnuityChoiceView | null>;
  /** A pension's vesting; null for a policy that is not a deferred annuity. */
  vesting: Keyed<VestingView | null>;
  held: Resource<VestingView[]>;
  rules: Resource<WithholdingRuleView[]>;
  acting: Keyed<unknown>;

  loadContract: (policyNumber: string) => Promise<void>;
  loadChoice: (caseId: string) => Promise<void>;
  loadDeferredChoice: (caseId: string) => Promise<void>;
  loadVesting: (policyNumber: string) => Promise<void>;
  loadHeld: () => Promise<void>;
  recordInstruction: (policyNumber: string, body: VestingInstructionInput) => Promise<void>;
  reconfirmAge: (policyNumber: string) => Promise<void>;
  loadRules: () => Promise<void>;
  proposeRule: (body: WithholdingRuleBody, attempt: MutationAttempt) => Promise<void>;
  approveRule: (ruleId: string) => Promise<void>;
  withdrawRule: (ruleId: string) => Promise<void>;
  endRule: (ruleId: string, effectiveTo: string) => Promise<void>;
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
    deferredChoice: {},
    vesting: {},
    held: idle(),
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
    loadDeferredChoice: (caseId) =>
      track(
        `annuity.deferredChoice.${caseId}`,
        getState().deferredChoice[caseId] ?? idle(),
        (next) => set((s) => ({ deferredChoice: { ...s.deferredChoice, [caseId]: next } })),
        () => getDeferredAnnuityChoice(caseId),
      ),
    loadVesting: (policyNumber) =>
      track(
        `annuity.vesting.${policyNumber}`,
        getState().vesting[policyNumber] ?? idle(),
        (next) => set((s) => ({ vesting: { ...s.vesting, [policyNumber]: next } })),
        () => getPolicyVesting(policyNumber),
      ),
    loadHeld: () => track('annuity.held', getState().held, (next) => set({ held: next }), () => listHeldVestings()),

    recordInstruction: (policyNumber, body) =>
      act(`vesting.${policyNumber}`, async () => {
        await recordVestingInstruction(policyNumber, body);
        await getState().loadVesting(policyNumber);
      }),
    reconfirmAge: (policyNumber) =>
      act(`vesting.${policyNumber}`, async () => {
        await reconfirmVestingAge(policyNumber);
        await getState().loadVesting(policyNumber);
      }),

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
    endRule: (ruleId, effectiveTo) =>
      act(ruleId, async () => {
        await endWithholdingRule(ruleId, effectiveTo);
        await getState().loadRules();
      }),
    withdrawRule: (ruleId) =>
      act(ruleId, async () => {
        await withdrawWithholdingRule(ruleId);
        await getState().loadRules();
      }),
  };
});
