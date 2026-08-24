import { describe, expect, it } from 'vitest';
import { beneficiariesFormSchema, toApiBeneficiaries } from './beneficiaryForm';

const party = (overrides: Partial<Record<string, unknown>> = {}) => ({
  type: 'PARTY' as const,
  partyId: '11111111-1111-4111-8111-111111111111',
  freeformDesignee: '',
  sharePercent: 100,
  revocable: true,
  ...overrides,
});

const freeform = (overrides: Partial<Record<string, unknown>> = {}) => ({
  type: 'FREEFORM' as const,
  partyId: '',
  freeformDesignee: 'My Estate',
  sharePercent: 100,
  revocable: true,
  ...overrides,
});

describe('beneficiariesFormSchema', () => {
  it('accepts a single PARTY beneficiary at 100%', () => {
    const result = beneficiariesFormSchema.safeParse({ beneficiaries: [party()] });
    expect(result.success).toBe(true);
  });

  it('accepts a single FREEFORM beneficiary at 100%', () => {
    const result = beneficiariesFormSchema.safeParse({ beneficiaries: [freeform()] });
    expect(result.success).toBe(true);
  });

  // PolicyApiImpl.replaceBeneficiaries: inputs == null || inputs.isEmpty() -> List.of(),
  // no validation applied at all. Clearing every beneficiary is a legitimate action.
  it('accepts an empty list -- clearing all beneficiaries is legitimate', () => {
    const result = beneficiariesFormSchema.safeParse({ beneficiaries: [] });
    expect(result.success).toBe(true);
  });

  it('accepts multiple beneficiaries summing to exactly 100', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [party({ sharePercent: 60 }), freeform({ sharePercent: 40 })],
    });
    expect(result.success).toBe(true);
  });

  // Mirrors PolicyApiImpl.validateAndBuildBeneficiaries's exact BigDecimal check.
  it.each([99, 101, 0, 50.5])('rejects shares summing to %s, not 100', (total) => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [party({ sharePercent: total })],
    });
    expect(result.success).toBe(false);
  });

  it('tolerates floating-point noise around exactly 100', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [party({ sharePercent: 33.34 }), freeform({ sharePercent: 66.66 })],
    });
    expect(result.success).toBe(true);
  });

  // Mirrors: hasParty == hasFreeform (both true or both false) -> error, independent of `type`.
  it('rejects a row with BOTH partyId and freeformDesignee set', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [party({ freeformDesignee: 'also this' })],
    });
    expect(result.success).toBe(false);
  });

  it('rejects a row with NEITHER partyId nor freeformDesignee set', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [party({ partyId: '' })],
    });
    expect(result.success).toBe(false);
  });

  // Mirrors: type === PARTY but !hasParty -> BeneficiaryValidationException, even if
  // freeformDesignee happens to be set (which the hasParty==hasFreeform check above would
  // otherwise accept as "exactly one" -- the type-specific check is a second, independent gate).
  it('rejects type PARTY paired with a freeform designee instead of a party id', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [{ type: 'PARTY', partyId: '', freeformDesignee: 'x', sharePercent: 100, revocable: true }],
    });
    expect(result.success).toBe(false);
  });

  it('rejects type FREEFORM paired with a party id instead of a designee', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [{ type: 'FREEFORM', partyId: '11111111-1111-4111-8111-111111111111', freeformDesignee: '', sharePercent: 100, revocable: true }],
    });
    expect(result.success).toBe(false);
  });

  it('rejects a freeform designee that is only whitespace', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [freeform({ freeformDesignee: '   ' })],
    });
    expect(result.success).toBe(false);
  });

  it('rejects a malformed party id rather than letting the server 400 on it', () => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [party({ partyId: 'not-a-uuid' })],
    });
    expect(result.success).toBe(false);
  });

  it.each([-1, 100.01, 150])('rejects a sharePercent outside 0-100 (%s)', (sharePercent) => {
    const result = beneficiariesFormSchema.safeParse({
      beneficiaries: [party({ sharePercent })],
    });
    expect(result.success).toBe(false);
  });

  it('accepts the boundary values 0 and 100 for a single row', () => {
    // 0 alone fails the sum-to-100 rule, so pair it with a 100 row to isolate the
    // per-field boundary from the whole-array sum rule.
    expect(
      beneficiariesFormSchema.safeParse({
        beneficiaries: [party({ sharePercent: 0 }), freeform({ sharePercent: 100 })],
      }).success,
    ).toBe(true);
  });
});

describe('toApiBeneficiaries', () => {
  it('sends partyId, never freeformDesignee, for a PARTY row', () => {
    const parsed = beneficiariesFormSchema.parse({ beneficiaries: [party()] });
    const api = toApiBeneficiaries(parsed);
    expect(api).toEqual([
      { type: 'PARTY', partyId: '11111111-1111-4111-8111-111111111111', sharePercent: 100, revocable: true },
    ]);
    expect(api[0]).not.toHaveProperty('freeformDesignee');
  });

  it('sends freeformDesignee, never partyId, for a FREEFORM row', () => {
    const parsed = beneficiariesFormSchema.parse({ beneficiaries: [freeform()] });
    const api = toApiBeneficiaries(parsed);
    expect(api).toEqual([{ type: 'FREEFORM', freeformDesignee: 'My Estate', sharePercent: 100, revocable: true }]);
    expect(api[0]).not.toHaveProperty('partyId');
  });

  it('produces an empty array from an empty form', () => {
    const parsed = beneficiariesFormSchema.parse({ beneficiaries: [] });
    expect(toApiBeneficiaries(parsed)).toEqual([]);
  });
});
