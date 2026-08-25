import { describe, expect, it } from 'vitest';
import { renameAccountFormSchema } from './renameAccountForm';

describe('renameAccountFormSchema', () => {
  it('accepts a well-formed name', () => {
    expect(renameAccountFormSchema.safeParse({ name: 'Petty Cash' }).success).toBe(true);
  });

  it('rejects a blank name', () => {
    expect(renameAccountFormSchema.safeParse({ name: '' }).success).toBe(false);
    expect(renameAccountFormSchema.safeParse({ name: '   ' }).success).toBe(false);
  });

  it('rejects a name over 200 characters', () => {
    expect(renameAccountFormSchema.safeParse({ name: 'a'.repeat(201) }).success).toBe(false);
  });

  it('accepts a name at exactly 200 characters', () => {
    expect(renameAccountFormSchema.safeParse({ name: 'a'.repeat(200) }).success).toBe(true);
  });
});
