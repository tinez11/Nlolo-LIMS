import { create } from 'zustand';
import {
  actOnPeriod,
  approvePolicyElection,
  listPeriods,
  listPolicyElections,
  proposePolicyElection,
  rejectPolicyElection,
  requestReopen,
  type PeriodAction,
} from '@/api/finaccounting';
import type { AccountingPeriodView, PolicyElectionInput, PolicyElectionView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The ledger's controls (IFRS 17 I1): accounting periods and the accounting policy register. Each mutation
 * runs under its own `acting` slot -- a refusal on one period or election must not touch another's controls
 * -- then rereads the list it changed.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface LedgerControlsState {
  periods: Resource<AccountingPeriodView[]>;
  elections: Resource<PolicyElectionView[]>;
  acting: Keyed<unknown>;

  loadPeriods: () => Promise<void>;
  actOnPeriod: (period: string, action: PeriodAction) => Promise<boolean>;
  requestReopen: (period: string, reason: string) => Promise<boolean>;
  loadElections: () => Promise<void>;
  propose: (input: PolicyElectionInput) => Promise<boolean>;
  approve: (electionId: string, signOffRef: string) => Promise<boolean>;
  reject: (electionId: string, reason: string) => Promise<boolean>;
}

export const useLedgerControlsStore = create<LedgerControlsState>((set, getState) => {
  /** Runs one mutation under acting[key]; resolves true when it succeeded (track() never rethrows). */
  const act = (key: string, call: () => Promise<unknown>): Promise<boolean> =>
    track(
      `ledgerControls.act.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        await call();
        return true;
      },
    ).then(() => getState().acting[key]?.status === 'success');

  return {
    periods: idle(),
    elections: idle(),
    acting: {},

    loadPeriods: () =>
      track('ledgerControls.periods', getState().periods, (next) => set({ periods: next }), () => listPeriods()),

    actOnPeriod: (period, action) =>
      act(`period.${period}`, async () => {
        await actOnPeriod(period, action);
        await getState().loadPeriods();
      }),

    requestReopen: (period, reason) =>
      act(`period.${period}`, async () => {
        await requestReopen(period, reason);
        await getState().loadPeriods();
      }),

    loadElections: () =>
      track(
        'ledgerControls.elections',
        getState().elections,
        (next) => set({ elections: next }),
        () => listPolicyElections(),
      ),

    propose: (input) =>
      act('election.propose', async () => {
        await proposePolicyElection(input);
        await getState().loadElections();
      }),

    approve: (electionId, signOffRef) =>
      act(`election.${electionId}`, async () => {
        await approvePolicyElection(electionId, signOffRef);
        await getState().loadElections();
      }),

    reject: (electionId, reason) =>
      act(`election.${electionId}`, async () => {
        await rejectPolicyElection(electionId, reason);
        await getState().loadElections();
      }),
  };
});
