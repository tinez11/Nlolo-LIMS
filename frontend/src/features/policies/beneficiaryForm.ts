import { z } from 'zod';
import type { BeneficiaryInput } from '@/api/types';
import { UUID_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for the beneficiaries edit form, mirroring
 * `PolicyApiImpl.validateAndBuildBeneficiaries` exactly so the client rejects what
 * the server would reject, before a round trip.
 *
 * Rules transcribed from the backend, not invented:
 *  - hasParty XOR hasFreeform must hold: exactly one of partyId / freeformDesignee,
 *    never both, never neither (independent of `type`).
 *  - type PARTY additionally requires hasParty; type FREEFORM additionally requires
 *    hasFreeform -- a second, independent gate the backend applies even when the
 *    XOR check above already passed with the "wrong" field for the declared type.
 *  - sharePercent is 0-100 inclusive (Jakarta `@DecimalMin("0") @DecimalMax("100")`).
 *  - Across the whole array, shares must sum to exactly 100 -- UNLESS the array is
 *    empty, which the backend explicitly short-circuits before any validation
 *    (`inputs == null || inputs.isEmpty() -> List.of()`), so clearing every
 *    beneficiary is a legitimate save, not a validation failure.
 */

const beneficiaryRowSchema = z
  .object({
    type: z.enum(['PARTY', 'FREEFORM']),
    // Both kept as always-present strings in the FORM model (react-hook-form needs a
    // stable shape per row); which one is actually sent is decided in toApiBeneficiaries.
    partyId: z.string(),
    freeformDesignee: z.string(),
    sharePercent: z.coerce.number().min(0).max(100),
    revocable: z.boolean(),
  })
  .superRefine((row, ctx) => {
    const hasParty = row.partyId.trim().length > 0;
    const hasFreeform = row.freeformDesignee.trim().length > 0;

    if (hasParty === hasFreeform) {
      ctx.addIssue({
        code: 'custom',
        message: 'Provide exactly one of a party or a freeform designee',
        path: ['partyId'],
      });
      return; // matches the backend: this check short-circuits before the type-specific one
    }

    if (hasParty && !UUID_PATTERN.test(row.partyId.trim())) {
      ctx.addIssue({ code: 'custom', message: 'Not a valid party id', path: ['partyId'] });
    }

    if (row.type === 'PARTY' && !hasParty) {
      ctx.addIssue({ code: 'custom', message: 'Party type requires a party id', path: ['partyId'] });
    }
    if (row.type === 'FREEFORM' && !hasFreeform) {
      ctx.addIssue({
        code: 'custom',
        message: 'Freeform type requires a designee',
        path: ['freeformDesignee'],
      });
    }
  });

export const beneficiariesFormSchema = z
  .object({ beneficiaries: z.array(beneficiaryRowSchema) })
  .superRefine((data, ctx) => {
    if (data.beneficiaries.length === 0) return; // clearing all beneficiaries is legitimate
    const total = data.beneficiaries.reduce((sum, b) => sum + b.sharePercent, 0);
    // The backend compares an exact BigDecimal to "100"; floating point in the browser
    // needs a small tolerance for the same real inputs (e.g. 33.34 + 33.33 + 33.33).
    if (Math.abs(total - 100) > 0.005) {
      ctx.addIssue({
        code: 'custom',
        message: `Shares must sum to 100, got ${total}`,
        path: ['beneficiaries'],
      });
    }
  });

/**
 * Two distinct types either side of `z.coerce.number()`:
 *  - Input: what react-hook-form actually holds while the user is typing --
 *    `sharePercent` is `unknown` pre-coercion, since `z.coerce` accepts anything.
 *  - Output: what `handleSubmit` hands to the submit callback once zodResolver has
 *    validated and coerced -- `sharePercent` is a real `number` here.
 * useForm is instantiated with both (see BeneficiariesPanel) so RHF's own
 * "transformed values" support routes the coerced Output type to onSubmit while
 * the raw Input type governs what register()/watch() see.
 */
export type BeneficiaryFormValues = z.output<typeof beneficiariesFormSchema>;
export type BeneficiaryFormInput = z.input<typeof beneficiariesFormSchema>;
export type BeneficiaryRowValues = BeneficiaryFormInput['beneficiaries'][number];

/** A blank row for "add beneficiary", defaulting revocable to true per the spec's own default. */
export function blankBeneficiaryRow(): BeneficiaryRowValues {
  return { type: 'PARTY', partyId: '', freeformDesignee: '', sharePercent: 0, revocable: true };
}

/** Convert validated (post-coercion) form values into exactly what `PUT .../beneficiaries` expects. */
export function toApiBeneficiaries(values: BeneficiaryFormValues): BeneficiaryInput[] {
  return values.beneficiaries.map((row) =>
    row.type === 'PARTY'
      ? { type: 'PARTY', partyId: row.partyId.trim(), sharePercent: row.sharePercent, revocable: row.revocable }
      : {
          type: 'FREEFORM',
          freeformDesignee: row.freeformDesignee.trim(),
          sharePercent: row.sharePercent,
          revocable: row.revocable,
        },
  );
}
