import { create } from 'zustand';
import { listAuditEvents, type AuditSearchParams } from '@/api/audit';
import type { AuditEntryView, Page } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/** The `audit` domain store. One slot: the journal is the module's whole read surface. */
interface AuditState {
  list: Resource<Page<AuditEntryView>>;
  loadList: (params: AuditSearchParams) => Promise<void>;
}

export const useAuditStore = create<AuditState>((set, getState) => ({
  list: idle(),

  loadList: (params) =>
    track('audit.list', getState().list, (next) => set({ list: next }), () => listAuditEvents(params)),
}));
