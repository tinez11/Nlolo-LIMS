import { create } from 'zustand';
import {
  approveAdjustment,
  approveRate,
  approveWithdrawal,
  generateStatement,
  getAccount,
  getClosingQuote,
  getDeposit,
  listAwaitingPayee,
  payOutDeposit,
  recordMaturityInstruction,
  type MaturityInstructionBody,
  listAdjustments,
  listRates,
  listStatements,
  listTopUps,
  listTransfersIn,
  listWithdrawals,
  proposeAdjustment,
  proposeRate,
  recordTransferIn,
  rejectAdjustment,
  requestTopUp,
  requestWithdrawal,
  withdrawRate,
  type AdjustmentBody,
  type RateDeclarationBody,
  type TopUpBody,
  type TransferInBody,
  type WithdrawalBody,
} from '@/api/accumulation';
import type {
  AccountView,
  AwaitingPayeeView,
  DepositView,
  AdjustmentView,
  ClosingQuoteView,
  RateDeclarationView,
  StatementRecordView,
  TopUpView,
  TransferInView,
  WithdrawalView,
} from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Savings accounts (product step 3): one policy's account and every list beside it, and one
 * product's declared rates.
 *
 * Keyed throughout, as benefitPayoutStore is and for its reason. The account resource holds `null`
 * for a scale-valued policy -- an answer, not an error -- which is how the policy page knows not to
 * show the Account tab. After every mutation the ACCOUNT is reread rather than patched: a withdrawal,
 * a top-up or an adjustment each change the ledger the server owns, and a guessed balance on screen
 * would be the one figure on this platform that must never be guessed.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface AccumulationState {
  account: Keyed<AccountView | null>;
  quote: Keyed<ClosingQuoteView>;
  withdrawals: Keyed<WithdrawalView[]>;
  topUps: Keyed<TopUpView[]>;
  transfers: Keyed<TransferInView[]>;
  adjustments: Keyed<AdjustmentView[]>;
  statements: Keyed<StatementRecordView[]>;
  rates: Keyed<RateDeclarationView[]>;
  /** null for an account that is not a fixed-term deposit -- an answer, like account's null. */
  deposit: Keyed<DepositView | null>;
  awaiting: Resource<AwaitingPayeeView[]>;
  acting: Keyed<unknown>;

  loadAccount: (policyNumber: string) => Promise<void>;
  loadQuote: (policyNumber: string) => Promise<void>;
  loadMovements: (policyNumber: string) => Promise<void>;
  loadStatements: (policyNumber: string) => Promise<void>;
  loadRates: (productId: string) => Promise<void>;
  loadDeposit: (policyNumber: string) => Promise<void>;
  loadAwaiting: () => Promise<void>;
  instruct: (policyNumber: string, body: MaturityInstructionBody, attempt: MutationAttempt) => Promise<void>;
  payOutDeposit: (policyNumber: string, payeeRef: string, attempt: MutationAttempt) => Promise<void>;

  withdraw: (policyNumber: string, body: WithdrawalBody, attempt: MutationAttempt) => Promise<void>;
  approveWithdrawal: (policyNumber: string, withdrawalId: string, attempt: MutationAttempt) => Promise<void>;
  topUp: (policyNumber: string, body: TopUpBody, attempt: MutationAttempt) => Promise<void>;
  transferIn: (policyNumber: string, body: TransferInBody, attempt: MutationAttempt) => Promise<void>;
  proposeAdjustment: (policyNumber: string, body: AdjustmentBody, attempt: MutationAttempt) => Promise<void>;
  decideAdjustment: (policyNumber: string, adjustmentId: string, approve: boolean, attempt: MutationAttempt) => Promise<void>;
  /** Resolves with the filing, or undefined when it failed -- the error is on acting[`statement.${policyNumber}`]. */
  fileStatement: (policyNumber: string, from: string, to: string, attempt: MutationAttempt) => Promise<StatementRecordView | undefined>;

  proposeRate: (productId: string, body: RateDeclarationBody, attempt: MutationAttempt) => Promise<void>;
  approveRate: (productId: string, declarationId: string, attempt: MutationAttempt) => Promise<void>;
  withdrawRate: (productId: string, declarationId: string, attempt: MutationAttempt) => Promise<void>;
}

export const useAccumulationStore = create<AccumulationState>((set, getState) => {
  const keyed = <K extends keyof AccumulationState>(slot: K, key: string, scope: string, load: () => Promise<unknown>) =>
    track(
      `accumulation.${scope}.${key}`,
      ((getState()[slot] as Keyed<unknown>)[key] ?? idle()) as Resource<unknown>,
      (next) => set((s) => ({ [slot]: { ...(s[slot] as Keyed<unknown>), [key]: next } }) as Partial<AccumulationState>),
      load,
    );

  /** Run one mutation under its own `acting` slot, then reread what it changed. */
  /** track() never rethrows: a failure lands on acting[key], and this resolves undefined. */
  const act = <T>(key: string, call: () => Promise<T>): Promise<T | undefined> => {
    let result: T | undefined;
    return track(
      `accumulation.act.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
      },
    ).then(() => result);
  };

  const rereadAccount = async (policyNumber: string) => {
    await Promise.all([getState().loadAccount(policyNumber), getState().loadMovements(policyNumber)]);
  };

  return {
    account: {},
    quote: {},
    withdrawals: {},
    topUps: {},
    transfers: {},
    adjustments: {},
    statements: {},
    rates: {},
    deposit: {},
    awaiting: idle(),
    acting: {},

    loadAccount: (policyNumber) => keyed('account', policyNumber, 'account', () => getAccount(policyNumber)),
    loadQuote: (policyNumber) => keyed('quote', policyNumber, 'quote', () => getClosingQuote(policyNumber)),
    loadMovements: async (policyNumber) => {
      await Promise.all([
        keyed('withdrawals', policyNumber, 'withdrawals', () => listWithdrawals(policyNumber)),
        keyed('topUps', policyNumber, 'topUps', () => listTopUps(policyNumber)),
        keyed('transfers', policyNumber, 'transfers', () => listTransfersIn(policyNumber)),
        keyed('adjustments', policyNumber, 'adjustments', () => listAdjustments(policyNumber)),
      ]);
    },
    loadStatements: (policyNumber) => keyed('statements', policyNumber, 'statements', () => listStatements(policyNumber)),
    loadRates: (productId) => keyed('rates', productId, 'rates', () => listRates(productId)),
    loadDeposit: (policyNumber) => keyed('deposit', policyNumber, 'deposit', () => getDeposit(policyNumber)),
    loadAwaiting: () =>
      track('accumulation.awaiting', getState().awaiting, (next) => set({ awaiting: next }), () => listAwaitingPayee()),

    instruct: (policyNumber, body, attempt) =>
      act(`instruct.${policyNumber}`, async () => {
        await recordMaturityInstruction(policyNumber, body, attempt);
        await getState().loadDeposit(policyNumber);
      }),

    // Rereads the account too: a payout closes it, and the balance on screen must say so.
    payOutDeposit: (policyNumber, payeeRef, attempt) =>
      act(`depositPayout.${policyNumber}`, async () => {
        await payOutDeposit(policyNumber, payeeRef, attempt);
        await Promise.all([getState().loadDeposit(policyNumber), getState().loadAccount(policyNumber)]);
      }),

    withdraw: (policyNumber, body, attempt) =>
      act(`withdraw.${policyNumber}`, async () => {
        await requestWithdrawal(policyNumber, body, attempt);
        await rereadAccount(policyNumber);
      }),

    approveWithdrawal: (policyNumber, withdrawalId, attempt) =>
      act(withdrawalId, async () => {
        await approveWithdrawal(withdrawalId, attempt);
        await rereadAccount(policyNumber);
      }),

    topUp: (policyNumber, body, attempt) =>
      act(`topup.${policyNumber}`, async () => {
        await requestTopUp(policyNumber, body, attempt);
        await rereadAccount(policyNumber);
      }),

    transferIn: (policyNumber, body, attempt) =>
      act(`transfer.${policyNumber}`, async () => {
        await recordTransferIn(policyNumber, body, attempt);
        await rereadAccount(policyNumber);
      }),

    proposeAdjustment: (policyNumber, body, attempt) =>
      act(`adjust.${policyNumber}`, async () => {
        await proposeAdjustment(policyNumber, body, attempt);
        await rereadAccount(policyNumber);
      }),

    decideAdjustment: (policyNumber, adjustmentId, approve, attempt) =>
      act(adjustmentId, async () => {
        await (approve ? approveAdjustment(adjustmentId, attempt) : rejectAdjustment(adjustmentId, attempt));
        await rereadAccount(policyNumber);
      }),

    fileStatement: (policyNumber, from, to, attempt) =>
      act(`statement.${policyNumber}`, async () => {
        const record = await generateStatement(policyNumber, { from, to }, attempt);
        await getState().loadStatements(policyNumber);
        return record;
      }),

    proposeRate: (productId, body, attempt) =>
      act(`rate.${productId}`, async () => {
        await proposeRate(productId, body, attempt);
        await getState().loadRates(productId);
      }),

    approveRate: (productId, declarationId, attempt) =>
      act(declarationId, async () => {
        await approveRate(declarationId, attempt);
        await getState().loadRates(productId);
      }),

    withdrawRate: (productId, declarationId, attempt) =>
      act(declarationId, async () => {
        await withdrawRate(declarationId, attempt);
        await getState().loadRates(productId);
      }),
  };
});
