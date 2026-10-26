import { useEffect } from 'react';
import { create } from 'zustand';
import { getMyAccess, type MyAccess } from '@/api/access';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The signed-in staff member's access (2026-10-26), loaded once per session before the staff console renders: the
 * badge's role and what the console draws (`can` in auth/access). The server checks every act regardless, and reads
 * a change at once; the console shows it from the next load.
 */
interface AccessState {
  mine: Resource<MyAccess>;
  load: () => Promise<void>;
  /** Load again even after a success -- after a failure, or after the person's own access was changed. */
  reload: () => Promise<void>;
}

export const useAccessStore = create<AccessState>((set, getState) => ({
  mine: idle(),
  load: () => {
    const current = getState().mine;
    if (current.status === 'success' || current.status === 'loading') return Promise.resolve();
    return track('access.mine', current, (next) => set({ mine: next }), () => getMyAccess());
  },
  reload: () => track('access.mine', getState().mine, (next) => set({ mine: next }), () => getMyAccess()),
}));

/** The staff member's access, loading it on first use; null until it arrives, and for any other realm. */
export function useMyAccess(enabled: boolean): MyAccess | null {
  const mine = useAccessStore((s) => s.mine);
  const load = useAccessStore((s) => s.load);
  useEffect(() => {
    if (enabled) void load();
  }, [enabled, load]);
  return enabled ? mine.data : null;
}
