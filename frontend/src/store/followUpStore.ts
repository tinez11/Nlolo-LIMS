import { create } from 'zustand';
import {
  collectionFigures,
  listFollowUp,
  listMyFollowUp,
  recordCall,
  sendReminder,
  type CollectionActionView,
  type CollectionFigures,
  type FollowUpPage,
  type FollowUpQuery,
  type RecordCallBody,
} from '@/api/followUp';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Premiums to follow up (2026-11-11): the list as last asked for, the collection figures, and the call or reminder in
 * flight per premium -- one row in flight never disables another. After a call or reminder is recorded the list is
 * read again, so the row shows what was just done.
 */
interface FollowUpState {
  list: Resource<FollowUpPage>;
  figures: Resource<CollectionFigures>;
  acting: Record<string, Resource<CollectionActionView>>;
  /** The query the list was last loaded with, and whose list (the agent's own, or the company's). */
  query: FollowUpQuery | null;
  mine: boolean;
  load: (query: FollowUpQuery, mine: boolean) => Promise<void>;
  loadFigures: (from: string, to: string) => Promise<void>;
  recordCall: (body: RecordCallBody) => Promise<boolean>;
  sendReminder: (invoiceId: string) => Promise<boolean>;
  clearActing: (invoiceId: string) => void;
}

export const useFollowUpStore = create<FollowUpState>((set, get) => ({
  list: idle(),
  figures: idle(),
  acting: {},
  query: null,
  mine: false,

  load: (query, mine) => {
    set({ query, mine });
    return track(mine ? 'followUp.mine' : 'followUp.list', get().list, (next) => set({ list: next }), () =>
      mine ? listMyFollowUp(query) : listFollowUp(query),
    );
  },

  loadFigures: (from, to) =>
    track('followUp.figures', get().figures, (next) => set({ figures: next }), () => collectionFigures(from, to)),

  recordCall: (body) => act(body.invoiceId, () => recordCall(body)),

  sendReminder: (invoiceId) => act(invoiceId, () => sendReminder(invoiceId)),

  clearActing: (invoiceId) =>
    set((state) => {
      const rest = { ...state.acting };
      delete rest[invoiceId];
      return { acting: rest };
    }),
}));

async function act(invoiceId: string, run: () => Promise<CollectionActionView>): Promise<boolean> {
  const store = useFollowUpStore;
  await track(`followUp.act.${invoiceId}`, store.getState().acting[invoiceId] ?? idle(), (next) =>
    store.setState((state) => ({ acting: { ...state.acting, [invoiceId]: next } })), run);
  const ok = store.getState().acting[invoiceId]?.status === 'success';
  const { query, mine, load } = store.getState();
  if (ok && query) await load(query, mine);
  return ok;
}
