import { create } from 'zustand';
import {
  approvePrice,
  closeFund,
  createFund,
  getPolicyUnits,
  getUnitLinkedChoice,
  getUnitLinkedReconciliation,
  listAdjustments,
  listFundPrices,
  listFunds,
  listWaiting,
  namePayee,
  proposeCorrection,
  proposePrice,
  settleAdjustment,
  waiveAdjustment,
  withdrawPrice,
} from '@/api/unitlinked';
import type {
  CreateFundRequest,
  FundPriceView,
  FundView,
  PolicyUnitsView,
  PriceAdjustmentView,
  ProposePriceRequest,
  UnitLinkedChoiceView,
  UnitLinkedReconciliationView,
  WaitingCountView,
} from '@/api/types';
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
  acting: Keyed<unknown>;

  loadFunds: () => Promise<void>;
  loadFund: (code: string) => Promise<void>;
  loadAdjustments: () => Promise<void>;
  loadUnits: (policyNumber: string) => Promise<void>;
  loadChoice: (caseId: string) => Promise<void>;
  loadReconciliation: () => Promise<void>;
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
  };
});
