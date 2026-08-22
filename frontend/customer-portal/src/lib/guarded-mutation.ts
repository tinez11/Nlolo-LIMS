import {
  claimIdempotency, recordOutcome, releaseIdempotency, type IdempotencyStore,
} from '@/lib/idempotency';
import type { ApiProblem } from '@/lib/problem';

/**
 * Layer 2 applied to one mutation, with the release rules from spec §6. Used only by the two
 * endpoints that accept Idempotency-Key and never read it: loan origination and loan repayment.
 *
 * The three-way classification is the whole point:
 *   success/state-changing -> keep the claim, record the outcome (a retry replays it)
 *   correctable rejection  -> RELEASE the claim (nothing happened; the user must be able to retry)
 *   indeterminate          -> KEEP the claim (the backend may have applied it; blocking is correct)
 */
export type PerformResult =
  | { kind: 'success'; body: unknown }
  | { kind: 'rejected'; status: number; problem: ApiProblem | null }
  | { kind: 'indeterminate'; status: number };

export type GuardedResult = { status: number; body?: unknown };

export async function runGuardedMutation(options: {
  store: IdempotencyStore;
  key: string;
  perform: () => Promise<PerformResult>;
}): Promise<GuardedResult> {
  const { store, key, perform } = options;

  const claim = await claimIdempotency(store, key);
  if (!claim.claimed) {
    if (claim.outcome) {
      return { status: 200, body: JSON.parse(claim.outcome) };
    }
    return {
      status: 409,
      body: {
        type: 'about:blank', title: 'Conflict', status: 409,
        errorCode: 'REQUEST_ALREADY_IN_PROGRESS', traceId: 'portal-guard',
        detail: 'This request is already being processed.',
      },
    };
  }

  let outcome: PerformResult;
  try {
    outcome = await perform();
  } catch {
    // An exception here is indeterminate by definition: we do not know whether the backend
    // applied the change, so the claim stays.
    return { status: 504, body: { type: 'about:blank', title: 'Gateway Timeout', status: 504,
      errorCode: 'OUTCOME_UNKNOWN', traceId: 'portal-guard' } };
  }

  if (outcome.kind === 'success') {
    await recordOutcome(store, key, JSON.stringify(outcome.body));
    return { status: 200, body: outcome.body };
  }
  if (outcome.kind === 'rejected') {
    await releaseIdempotency(store, key);
    return { status: outcome.status, body: outcome.problem };
  }
  return { status: outcome.status, body: { type: 'about:blank', title: 'Gateway Timeout',
    status: outcome.status, errorCode: 'OUTCOME_UNKNOWN', traceId: 'portal-guard' } };
}
