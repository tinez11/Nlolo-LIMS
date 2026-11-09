import { useEffect } from 'react';
import { create } from 'zustand';
import { listBranches, type Branch } from '@/api/organisation';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The company's branches (2026-11-09, organisation settings), loaded once and shared by every branch picker; reloaded
 * when an administrator saves one.
 */
interface OrganisationState {
  branches: Resource<Branch[]>;
  loadBranches: (force?: boolean) => Promise<void>;
}

export const useOrganisationStore = create<OrganisationState>((set, getState) => ({
  branches: idle(),
  loadBranches: (force = false) => {
    const current = getState().branches;
    if (!force && (current.status === 'success' || current.status === 'loading')) return Promise.resolve();
    return track('organisation.branches', current, (next) => set({ branches: next }), listBranches);
  },
}));

const NONE: { code: string; label: string }[] = [];

/** The company's active branches as code and name, the head office first; empty until they arrive. */
export function useBranchCodes(): { code: string; label: string }[] {
  const resource = useOrganisationStore((s) => s.branches);
  const load = useOrganisationStore((s) => s.loadBranches);
  useEffect(() => {
    void load();
  }, [load]);
  return resource.data ? resource.data.filter((b) => b.active).map((b) => ({ code: b.code, label: b.name })) : NONE;
}
