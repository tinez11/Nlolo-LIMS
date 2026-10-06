import { describe, expect, it } from 'vitest';
import { dismissSchema, modelsLabel, whenLabel } from './postingQueueForms';

describe('dismissSchema', () => {
  it('needs a reason, as the server does', () => {
    const result = dismissSchema.safeParse({ reason: '   ' });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.message).toBe('Say why this event is dismissed rather than posted');
  });

  it('trims the reason it keeps', () => {
    expect(dismissSchema.parse({ reason: '  Billed in error  ' })).toEqual({ reason: 'Billed in error' });
  });

  it('refuses more than the server stores', () => {
    expect(dismissSchema.safeParse({ reason: 'x'.repeat(501) }).success).toBe(false);
  });
});

describe('rule labels', () => {
  it('spells out ANY and NONE', () => {
    expect(modelsLabel(['GMM', 'VFA'])).toBe('GMM, VFA');
    expect(modelsLabel(['ANY'])).toBe('any classified');
    expect(modelsLabel(['NONE'])).toBe('no policy');
  });

  it('reads a when as attribute = value', () => {
    expect(whenLabel({})).toBe('');
    expect(whenLabel({ part: 'OWED', sign: 'NEGATIVE' })).toBe('part = OWED, sign = NEGATIVE');
  });
});
