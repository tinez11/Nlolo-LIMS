import { create } from 'zustand';
import {
  approveDeclaration,
  getPolicyBonuses,
  listDeclarations,
  proposeDeclaration,
  withdrawDeclaration,
  type DeclarationBody,
} from '@/api/bonus';
import type { BonusDeclarationView, PolicyBonusView } from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * With-profits bonuses (product step 4): one product's declarations, and one policy's bonuses.
 *
 * Keyed, as accumulationStore is and for its reason. The policy resource holds `null` for a policy
 * that is not with-profits -- an answer, not an error -- which is how the policy page knows not to
 * show the Bonuses tab. After every mutation the declarations are reread rather than patched.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface BonusState {
  declarations: Keyed<BonusDeclarationView[]>;
  policy: Keyed<PolicyBonusView | null>;
  acting: Keyed<unknown>;

  loadDeclarations: (productId: string) => Promise<void>;
  loadPolicy: (policyNumber: string) => Promise<void>;
  proposeDeclaration: (productId: string, body: DeclarationBody, attempt: MutationAttempt) => Promise<void>;
  approveDeclaration: (productId: string, declarationId: string, attempt: MutationAttempt) => Promise<void>;
  withdrawDeclaration: (productId: string, declarationId: string, attempt: MutationAttempt) => Promise<void>;
}

export const useBonusStore = create<BonusState>((set, getState) => {
  const keyed = <K extends keyof BonusState>(slot: K, key: string, scope: string, load: () => Promise<unknown>) =>
    track(
      `bonus.${scope}.${key}`,
      ((getState()[slot] as Keyed<unknown>)[key] ?? idle()) as Resource<unknown>,
      (next) => set((s) => ({ [slot]: { ...(s[slot] as Keyed<unknown>), [key]: next } }) as Partial<BonusState>),
      load,
    );

  /** Run one mutation under its own `acting` slot, then reread what it changed. */
  /** track() never rethrows: a failure lands on acting[key], and this resolves undefined. */
  const act = <T>(key: string, call: () => Promise<T>): Promise<T | undefined> => {
    let result: T | undefined;
    return track(
      `bonus.act.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
      },
    ).then(() => result);
  };

  return {
    declarations: {},
    policy: {},
    acting: {},

    loadDeclarations: (productId) => keyed('declarations', productId, 'declarations', () => listDeclarations(productId)),
    loadPolicy: (policyNumber) => keyed('policy', policyNumber, 'policy', () => getPolicyBonuses(policyNumber)),

    proposeDeclaration: (productId, body, attempt) =>
      act(`declaration.${productId}`, async () => {
        await proposeDeclaration(productId, body, attempt);
        await getState().loadDeclarations(productId);
      }),

    approveDeclaration: (productId, declarationId, attempt) =>
      act(declarationId, async () => {
        await approveDeclaration(declarationId, attempt);
        await getState().loadDeclarations(productId);
      }),

    withdrawDeclaration: (productId, declarationId, attempt) =>
      act(declarationId, async () => {
        await withdrawDeclaration(declarationId, attempt);
        await getState().loadDeclarations(productId);
      }),
  };
});
