import { create } from 'zustand';
import {
  approvePrice,
  approveWithdrawal,
  closeFund,
  createFund,
  fileStatement,
  getPolicyUnits,
  getSplitHistory,
  getUnitLinkedTerms,
  getUnitLinkedChoice,
  getUnitLinkedReconciliation,
  listAdjustments,
  listFundPrices,
  listFunds,
  listStatements,
  listSwitches,
  listTopUps,
  listWaiting,
  listWithdrawals,
  namePayee,
  proposeCorrection,
  proposePrice,
  redirectPremiums,
  requestSwitch,
  requestTopUp,
  requestWithdrawal,
  settleAdjustment,
  waiveAdjustment,
  withdrawPrice,
} from '@/api/unitlinked';
import type {
  CreateFundRequest,
  FundPriceView,
  FundShare,
  FundView,
  PolicyUnitsView,
  PremiumSplitView,
  PriceAdjustmentView,
  ProposePriceRequest,
  SwitchRequestBody,
  SwitchView,
  UnitLinkedChoiceView,
  UnitLinkedReconciliationView,
  UnitLinkedTermsView,
  UnitLinkedTopUpRequest,
  UnitLinkedTopUpView,
  UnitLinkedWithdrawalView,
  UnitStatementView,
  WaitingCountView,
  WithdrawalRequestBody,
} from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Unit-linked (product step 6): the fund register, each fund's prices and waiting orders, the
 * price-adjustment queue, a policy's units and a case's fund split. Keyed as annuityStore is; after
 * every mutation what it changed is reread rather than patched -- an approval prices orders the screen
 * cannot see, so only the server knows what it moved.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface UnitLinkedState {
  funds: Resource<FundView[]>;
  prices: Keyed<FundPriceView[]>;
  waiting: Keyed<WaitingCountView[]>;
  adjustments: Resource<PriceAdjustmentView[]>;
  units: Keyed<PolicyUnitsView>;
  /** A case's choice; null for a case that has none. */
  choice: Keyed<UnitLinkedChoiceView | null>;
  reconciliation: Resource<UnitLinkedReconciliationView>;
  /** U2, keyed by policy number. */
  splits: Keyed<PremiumSplitView[]>;
  switches: Keyed<SwitchView[]>;
  withdrawals: Keyed<UnitLinkedWithdrawalView[]>;
  topUps: Keyed<UnitLinkedTopUpView[]>;
  statements: Keyed<UnitStatementView[]>;
  /** A version's terms, keyed by version id; null for a version that is not unit-linked. */
  terms: Keyed<UnitLinkedTermsView | null>;
  acting: Keyed<unknown>;

  loadFunds: () => Promise<void>;
  loadFund: (code: string) => Promise<void>;
  loadAdjustments: () => Promise<void>;
  loadUnits: (policyNumber: string) => Promise<void>;
  loadChoice: (caseId: string) => Promise<void>;
  loadReconciliation: () => Promise<void>;
  loadTerms: (productId: string, versionId: string) => Promise<void>;
  /** Everything U2 shows for one policy. */
  loadPolicyU2: (policyNumber: string) => Promise<void>;
  redirect: (policyNumber: string, split: FundShare[]) => Promise<void>;
  requestSwitch: (policyNumber: string, body: SwitchRequestBody) => Promise<void>;
  requestWithdrawal: (policyNumber: string, body: WithdrawalRequestBody) => Promise<void>;
  approveWithdrawal: (policyNumber: string, withdrawalId: string) => Promise<void>;
  requestTopUp: (policyNumber: string, body: UnitLinkedTopUpRequest, attempt: MutationAttempt) => Promise<void>;
  fileStatement: (policyNumber: string, from: string, to: string) => Promise<void>;
  createFund: (body: CreateFundRequest) => Promise<void>;
  closeFund: (code: string) => Promise<void>;
  proposePrice: (body: ProposePriceRequest) => Promise<void>;
  approvePrice: (price: FundPriceView) => Promise<void>;
  withdrawPrice: (price: FundPriceView) => Promise<void>;
  proposeCorrection: (price: FundPriceView, newPrice: string, reason: string) => Promise<void>;
  settleAdjustment: (adjustmentId: string, payeeRef: string) => Promise<void>;
  waiveAdjustment: (adjustmentId: string, reason: string) => Promise<void>;
  namePayee: (policyNumber: string, payeeRef: string) => Promise<void>;
}

export const useUnitLinkedStore = create<UnitLinkedState>((set, getState) => {
  /** Run one mutation under its own `acting` slot. track() never rethrows: a failure lands on acting[key]. */
  const act = (key: string, call: () => Promise<unknown>): Promise<void> =>
    track(
      `unitLinked.act.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        await call();
      },
    );

  return {
    funds: idle(),
    prices: {},
    waiting: {},
    adjustments: idle(),
    units: {},
    choice: {},
    reconciliation: idle(),
    splits: {},
    switches: {},
    withdrawals: {},
    topUps: {},
    statements: {},
    terms: {},
    acting: {},

    loadFunds: () => track('unitLinked.funds', getState().funds, (next) => set({ funds: next }), () => listFunds()),
    loadFund: async (code) => {
      await Promise.all([
        track(
          `unitLinked.prices.${code}`,
          getState().prices[code] ?? idle(),
          (next) => set((s) => ({ prices: { ...s.prices, [code]: next } })),
          () => listFundPrices(code),
        ),
        track(
          `unitLinked.waiting.${code}`,
          getState().waiting[code] ?? idle(),
          (next) => set((s) => ({ waiting: { ...s.waiting, [code]: next } })),
          () => listWaiting(code),
        ),
      ]);
    },
    loadAdjustments: () =>
      track('unitLinked.adjustments', getState().adjustments, (next) => set({ adjustments: next }), () => listAdjustments()),
    loadUnits: (policyNumber) =>
      track(
        `unitLinked.units.${policyNumber}`,
        getState().units[policyNumber] ?? idle(),
        (next) => set((s) => ({ units: { ...s.units, [policyNumber]: next } })),
        () => getPolicyUnits(policyNumber),
      ),
    loadChoice: (caseId) =>
      track(
        `unitLinked.choice.${caseId}`,
        getState().choice[caseId] ?? idle(),
        (next) => set((s) => ({ choice: { ...s.choice, [caseId]: next } })),
        () => getUnitLinkedChoice(caseId),
      ),
    loadReconciliation: () =>
      track(
        'unitLinked.reconciliation',
        getState().reconciliation,
        (next) => set({ reconciliation: next }),
        () => getUnitLinkedReconciliation(),
      ),

    createFund: (body) =>
      act('fund.create', async () => {
        await createFund(body);
        await getState().loadFunds();
      }),
    closeFund: (code) =>
      act(`fund.${code}`, async () => {
        await closeFund(code);
        await getState().loadFunds();
      }),
    proposePrice: (body) =>
      act(`price.propose.${body.fundCode}`, async () => {
        await proposePrice(body);
        await getState().loadFund(body.fundCode);
      }),
    approvePrice: (price) =>
      act(price.priceId, async () => {
        await approvePrice(price.priceId);
        // A correction may have raised adjustments on payouts already made.
        await Promise.all([getState().loadFund(price.fundCode), getState().loadAdjustments()]);
      }),
    withdrawPrice: (price) =>
      act(price.priceId, async () => {
        await withdrawPrice(price.priceId);
        await getState().loadFund(price.fundCode);
      }),
    proposeCorrection: (price, newPrice, reason) =>
      act(`correct.${price.priceId}`, async () => {
        await proposeCorrection(price.priceId, newPrice, reason);
        await getState().loadFund(price.fundCode);
      }),
    settleAdjustment: (adjustmentId, payeeRef) =>
      act(adjustmentId, async () => {
        await settleAdjustment(adjustmentId, payeeRef);
        await getState().loadAdjustments();
      }),
    waiveAdjustment: (adjustmentId, reason) =>
      act(adjustmentId, async () => {
        await waiveAdjustment(adjustmentId, reason);
        await getState().loadAdjustments();
      }),
    namePayee: (policyNumber, payeeRef) =>
      act(`payee.${policyNumber}`, async () => {
        await namePayee(policyNumber, payeeRef);
        await getState().loadUnits(policyNumber);
      }),

    loadTerms: (productId, versionId) =>
      track(
        `unitLinked.terms.${versionId}`,
        getState().terms[versionId] ?? idle(),
        (next) => set((s) => ({ terms: { ...s.terms, [versionId]: next } })),
        () => getUnitLinkedTerms(productId, versionId),
      ),
    loadPolicyU2: async (policyNumber) => {
      const keyed = <K extends 'splits' | 'switches' | 'withdrawals' | 'topUps' | 'statements', T>(
        slot: K,
        load: () => Promise<T>,
      ) =>
        track(
          `unitLinked.${slot}.${policyNumber}`,
          (getState()[slot] as Keyed<T>)[policyNumber] ?? idle<T>(),
          (next) => set((s) => ({ [slot]: { ...(s[slot] as Keyed<T>), [policyNumber]: next } }) as Partial<UnitLinkedState>),
          load,
        );
      await Promise.all([
        keyed('splits', () => getSplitHistory(policyNumber)),
        keyed('switches', () => listSwitches(policyNumber)),
        keyed('withdrawals', () => listWithdrawals(policyNumber)),
        keyed('topUps', () => listTopUps(policyNumber)),
        keyed('statements', () => listStatements(policyNumber)),
      ]);
    },
    redirect: (policyNumber, split) =>
      act(`split.${policyNumber}`, async () => {
        await redirectPremiums(policyNumber, split);
        await getState().loadPolicyU2(policyNumber);
      }),
    requestSwitch: (policyNumber, body) =>
      act(`switch.${policyNumber}`, async () => {
        await requestSwitch(policyNumber, body);
        await Promise.all([getState().loadPolicyU2(policyNumber), getState().loadUnits(policyNumber)]);
      }),
    requestWithdrawal: (policyNumber, body) =>
      act(`withdrawal.${policyNumber}`, async () => {
        await requestWithdrawal(policyNumber, body);
        await getState().loadPolicyU2(policyNumber);
      }),
    approveWithdrawal: (policyNumber, withdrawalId) =>
      act(withdrawalId, async () => {
        await approveWithdrawal(withdrawalId);
        // The approval queues the sale: the units panel shows it waiting for its price.
        await Promise.all([getState().loadPolicyU2(policyNumber), getState().loadUnits(policyNumber)]);
      }),
    requestTopUp: (policyNumber, body, attempt) =>
      act(`topUp.${policyNumber}`, async () => {
        await requestTopUp(policyNumber, body, attempt);
        await getState().loadPolicyU2(policyNumber);
      }),
    fileStatement: (policyNumber, from, to) =>
      act(`statement.${policyNumber}`, async () => {
        await fileStatement(policyNumber, from, to);
        await getState().loadPolicyU2(policyNumber);
      }),
  };
});
