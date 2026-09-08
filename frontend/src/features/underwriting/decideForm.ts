import { z } from 'zod';
import type { DecideRequest, UnderwritingDecisionOutcome } from '@/api/types';

/**
 * The underwriting decision form, mirroring the backend's `DecideRequest` and the checks
 * `UnderwritingApiImpl.decide` applies.
 *
 * `loadingPercent` is kept as a STRING and coerced on submit, for the reason this console
 * keeps every numeric input as a string: `z.coerce.number()` turns `''` into `0`, and a
 * loading of zero is a different statement from "no loading" — the database's
 * `chk_loading_only_when_loaded` refuses a null loading on a LOADED decision and refuses a
 * non-null one on any other outcome, so the empty case has to stay distinguishable.
 *
 * The pairing is conditional on `outcome`, which is why it lives in a `superRefine` rather
 * than on the field: no per-field rule can say "required here, forbidden there".
 */

/** Every outcome an underwriter may record, in the order the form offers them. */
export const DECISION_OUTCOMES = [
  { value: 'ACCEPT', label: 'Accept' },
  { value: 'LOADED', label: 'Accept with a loading' },
  { value: 'DECLINED', label: 'Decline' },
  { value: 'POSTPONED', label: 'Postpone — more evidence needed' },
] as const satisfies readonly { value: UnderwritingDecisionOutcome; label: string }[];

export const decideFormSchema = z
  .object({
    outcome: z.enum(['ACCEPT', 'LOADED', 'DECLINED', 'POSTPONED']),
    loadingPercent: z.string().trim(),
    reason: z
      .string()
      .trim()
      .min(1, 'Say why. A decision nobody explained is one nobody can review')
      .max(500, 'Keep the reason under 500 characters'),
  })
  .superRefine((values, ctx) => {
    if (values.outcome === 'LOADED') {
      if (values.loadingPercent === '') {
        ctx.addIssue({
          code: 'custom',
          path: ['loadingPercent'],
          message: 'A loaded acceptance needs the loading percentage',
        });
        return;
      }
      const loading = Number(values.loadingPercent);
      if (Number.isNaN(loading) || loading <= 0) {
        ctx.addIssue({
          code: 'custom',
          path: ['loadingPercent'],
          message: 'A loading must be greater than zero',
        });
      }
      // NUMERIC(5,2) on decision_loading_percent: 999.99 is the largest it holds, and a
      // rejection from the database here would arrive as a 500 rather than a field error.
      if (loading > 999.99) {
        ctx.addIssue({
          code: 'custom',
          path: ['loadingPercent'],
          message: 'A loading above 999.99% cannot be recorded',
        });
      }
      return;
    }

    // Not merely ignored on submit: silently dropping a figure somebody typed is how a
    // decision ends up meaning something other than what its author intended.
    if (values.loadingPercent !== '') {
      ctx.addIssue({
        code: 'custom',
        path: ['loadingPercent'],
        message: 'A loading only applies to an accepted-with-loading decision',
      });
    }
  });

export type DecideFormValues = z.output<typeof decideFormSchema>;
export type DecideFormInput = z.input<typeof decideFormSchema>;

export function blankDecideForm(): DecideFormInput {
  return {
    // Blank would be the honest default, but `outcome` is an enum with no empty member and
    // the form must open on something. ACCEPT is the commonest outcome and, crucially, is
    // never destructive by accident: the reason field is required, so nothing can be
    // submitted without the author having typed a sentence about it.
    outcome: 'ACCEPT',
    loadingPercent: '',
    reason: '',
  };
}

/**
 * Whether this decision departs from what the engine recommended.
 *
 * Only a `SENIOR_UNDERWRITER` may record one. Exported because the form has to make the
 * same judgement live, as the outcome is being chosen, rather than discovering it from a
 * 403 after the fact.
 *
 * A case with NO recommendation is not a disagreement — a case may be decided before the
 * engine has run over any evidence — so it never counts as an override, matching the
 * server.
 */
export function isOverride(
  outcome: UnderwritingDecisionOutcome,
  recommendation: UnderwritingDecisionOutcome | null | undefined,
): boolean {
  return recommendation != null && recommendation !== outcome;
}

/** Convert validated values into exactly what `POST .../decision` expects. */
export function toApiRequest(values: DecideFormValues): DecideRequest {
  return {
    outcome: values.outcome,
    reason: values.reason,
    // Absent, not null and not zero, on every outcome but LOADED. The server refuses a
    // non-null loading on any other outcome, mirroring the database CHECK.
    ...(values.outcome === 'LOADED' ? { loadingPercent: Number(values.loadingPercent) } : {}),
  };
}
