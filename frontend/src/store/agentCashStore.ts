import { create } from 'zustand';
import { listAgentCash, markAgentCashBanked, recordAgentCash } from '@/api/billing';
import type { FieldReceiptView } from '@/api/types';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The signed-in agent's cash (2026-11-11): what they still hold, recording new cash, and saying where it was banked.
 * Online only, by the user's choice: a recording is sent at once, and the same attempt's key makes a retried tap
 * record the cash once.
 */
interface AgentCashState {
  held: Resource<FieldReceiptView[]>;
  recording: Resource<{ receiptId?: string; status?: string }>;
  banking: Resource<FieldReceiptView[]>;
  /** The attempt in progress, so a retry after a failure reuses its key. */
  attempt: MutationAttempt | null;
  loadHeld: (agentId: string) => Promise<void>;
  record: (agentId: string, policyNumber: string, amount: string, currencyCode: string) => Promise<boolean>;
  resetRecording: () => void;
  markBanked: (agentId: string, receiptIds: string[], reference: string) => Promise<boolean>;
}

export const useAgentCashStore = create<AgentCashState>((set, get) => ({
  held: idle(),
  recording: idle(),
  banking: idle(),
  attempt: null,

  loadHeld: (agentId) =>
    track(`agentCash.held.${agentId}`, get().held, (next) => set({ held: next }), () => listAgentCash(agentId)),

  record: async (agentId, policyNumber, amount, currencyCode) => {
    const attempt = get().attempt ?? startMutation();
    set({ attempt });
    await track('agentCash.record', get().recording, (next) => set({ recording: next }), () =>
      recordAgentCash(agentId, { policyNumber, amount, currencyCode, clientIdempotencyKey: attempt.key }),
    );
    const ok = get().recording.status === 'success';
    if (ok) {
      set({ attempt: null });
      await get().loadHeld(agentId);
    }
    return ok;
  },

  resetRecording: () => set({ recording: idle(), attempt: null }),

  markBanked: async (agentId, receiptIds, reference) => {
    await track('agentCash.banked', get().banking, (next) => set({ banking: next }), () =>
      markAgentCashBanked(agentId, receiptIds, reference),
    );
    const ok = get().banking.status === 'success';
    if (ok) await get().loadHeld(agentId);
    return ok;
  },
}));
