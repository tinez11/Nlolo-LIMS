import { describe, expect, it } from 'vitest';
import { generateReturnFormSchema, toApiRequest } from './generateReturnForm';

const valid = () => ({ returnType: 'QUARTERLY_PRUDENTIAL', period: '2026-Q1' });

describe('generateReturnFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(generateReturnFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('rejects a blank return type', () => {
    expect(generateReturnFormSchema.safeParse({ ...valid(), returnType: '' }).success).toBe(false);
  });

  it('rejects a blank period', () => {
    expect(generateReturnFormSchema.safeParse({ ...valid(), period: '' }).success).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('trims both fields', () => {
    const request = toApiRequest(
      generateReturnFormSchema.parse({ returnType: '  QUARTERLY_PRUDENTIAL  ', period: '  2026-Q1  ' }),
    );
    expect(request).toEqual({ returnType: 'QUARTERLY_PRUDENTIAL', period: '2026-Q1' });
  });
});
