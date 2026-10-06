import { useEffect } from 'react';
import { create } from 'zustand';
import { getReferenceCodes, type ReferenceCodeView } from '@/api/refdata';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Global reference lists, loaded once per code set and kept: branches and sales channels change by a refdata row, not
 * within a session. Keyed so the branch and channel pickers share one request each however many forms show them.
 */
interface RefdataState {
  sets: Record<string, Resource<ReferenceCodeView[]>>;
  load: (codeSetKey: string) => Promise<void>;
}

export const useRefdataStore = create<RefdataState>((set, getState) => ({
  sets: {},
  load: (codeSetKey) => {
    const current = getState().sets[codeSetKey] ?? idle<ReferenceCodeView[]>();
    if (current.status === 'success' || current.status === 'loading') return Promise.resolve();
    return track(
      `refdata.${codeSetKey}`,
      current,
      (next) => set((s) => ({ sets: { ...s.sets, [codeSetKey]: next } })),
      () => getReferenceCodes(codeSetKey),
    );
  },
}));

const NONE: ReferenceCodeView[] = [];

/** The codes of one list, loading it on first use; an empty array until it arrives. */
export function useReferenceCodes(codeSetKey: string): ReferenceCodeView[] {
  const resource = useRefdataStore((s) => s.sets[codeSetKey]);
  const load = useRefdataStore((s) => s.load);
  useEffect(() => {
    void load(codeSetKey);
  }, [codeSetKey, load]);
  return resource?.data ?? NONE;
}
