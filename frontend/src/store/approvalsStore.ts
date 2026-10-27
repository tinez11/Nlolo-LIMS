import { create } from 'zustand';
import { getApprovals, type ApprovalsView } from '@/api/approvals';
import { idle, track, type Resource } from './createResourceSlice';

/** The approvals inbox: one read, one slot. Acting happens on each row's own page, which owns its store. */
interface ApprovalsState {
  approvals: Resource<ApprovalsView>;
  loadApprovals: () => Promise<void>;
}

export const useApprovalsStore = create<ApprovalsState>((set, getState) => ({
  approvals: idle(),

  loadApprovals: () =>
    track(
      'approvals.list',
      getState().approvals,
      (next) => set({ approvals: next }),
      () => getApprovals(),
    ),
}));
