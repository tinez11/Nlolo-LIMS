import { get, post } from '@/lib/http';
import type { ApiError } from '@/lib/apiError';
import type { MutationAttempt } from '@/lib/idempotency';
import type { BonusDeclarationView, BonusValuationView, PolicyBonusView } from './types';

/**
 * With-profits bonuses (product step 4): a product's declarations, and one policy's bonuses.
 *
 * Every mutation sends the store-minted `Idempotency-Key`, as `api/accumulation.ts` explains.
 */

const declarations = (productId: string) => `/products/${encodeURIComponent(productId)}/bonus-declarations`;

export function listDeclarations(productId: string): Promise<BonusDeclarationView[]> {
  return get<BonusDeclarationView[]>(declarations(productId));
}

export interface DeclarationBody {
  valuationDate: string;
  reversionaryRatePercent: number;
  terminalRatePercent: number;
}

export function proposeDeclaration(productId: string, body: DeclarationBody, attempt: MutationAttempt): Promise<BonusDeclarationView> {
  return post<BonusDeclarationView>(declarations(productId), body, { headers: attempt.headers() });
}

export function approveDeclaration(declarationId: string, attempt: MutationAttempt): Promise<BonusDeclarationView> {
  return post<BonusDeclarationView>(`/bonus-declarations/${encodeURIComponent(declarationId)}/approve`, undefined, {
    headers: attempt.headers(),
  });
}

export function withdrawDeclaration(declarationId: string, attempt: MutationAttempt): Promise<BonusDeclarationView> {
  return post<BonusDeclarationView>(`/bonus-declarations/${encodeURIComponent(declarationId)}/withdraw`, undefined, {
    headers: attempt.headers(),
  });
}

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as ApiError).kind === 'notFound';
}

/**
 * The policy's bonuses, or null for a policy that is not with-profits. The 404 is an answer, not an
 * error, exactly as getAccount's is: the policy page uses it to decide whether to show the tab.
 */
export async function getPolicyBonuses(policyNumber: string): Promise<PolicyBonusView | null> {
  try {
    return await get<PolicyBonusView>(`/policies/${encodeURIComponent(policyNumber)}/bonuses`);
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

export function getBonusValue(policyNumber: string, asOf: string): Promise<BonusValuationView> {
  return get<BonusValuationView>(`/policies/${encodeURIComponent(policyNumber)}/bonuses/value`, { params: { asOf } });
}
