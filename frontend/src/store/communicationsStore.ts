import { create } from 'zustand';
import {
  listDispatches,
  listTemplates,
  rewordTemplate,
  type DispatchSearchParams,
} from '@/api/communications';
import type { NotificationDispatchView, NotificationTemplateView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `communication` domain store.
 *
 * Templates and the outbox are separate slots rather than one: they are read on different
 * screens, by different people, answering different questions — what do we say, and did we say
 * it. `byPolicy` is a third because the policy detail page's panel loads alongside a page that
 * already has its own list in flight, and sharing a slot would have one clobber the other.
 */
interface CommunicationsState {
  templates: Resource<NotificationTemplateView[]>;
  dispatches: Resource<NotificationDispatchView[]>;
  byPolicy: Resource<NotificationDispatchView[]>;
  rewording: Resource<NotificationTemplateView>;

  loadTemplates: () => Promise<void>;
  loadDispatches: (params: DispatchSearchParams) => Promise<void>;
  loadForPolicy: (policyNumber: string) => Promise<void>;
  reword: (templateId: string, bodyTemplate: string) => Promise<void>;
}

export const useCommunicationsStore = create<CommunicationsState>((set, getState) => ({
  templates: idle(),
  dispatches: idle(),
  byPolicy: idle(),
  rewording: idle(),

  loadTemplates: () =>
    track('communications.templates', getState().templates, (next) => set({ templates: next }), listTemplates),

  loadDispatches: (params) =>
    track('communications.dispatches', getState().dispatches, (next) => set({ dispatches: next }), () =>
      listDispatches(params),
    ),

  loadForPolicy: (policyNumber) =>
    track('communications.byPolicy', getState().byPolicy, (next) => set({ byPolicy: next }), () =>
      listDispatches({ policyNumber }),
    ),

  reword: async (templateId, bodyTemplate) => {
    await track('communications.reword', getState().rewording, (next) => set({ rewording: next }), () =>
      rewordTemplate(templateId, bodyTemplate),
    );
    // Re-read rather than patching the row in place. The response carries the derived
    // `placeholders`, and a locally-patched list would show stale ones the moment an edit
    // dropped a token — which is exactly the thing an editor needs to see change.
    if (getState().rewording.status === 'success') {
      await getState().loadTemplates();
    }
  },
}));
