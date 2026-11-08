import { describe, expect, it } from 'vitest';
import { messageName, placeholdersIn, previewText, smsParts, whenSent } from './messageCatalog';

/** 2026-11-08, the user: the templates screen showed OFFER_MADE and {{policyNumber}} and nothing a person reads. */
describe('the message catalogue', () => {
  it('names every message in words and says when it goes out', () => {
    expect(messageName('ACCOUNT_STATEMENT')).toBe('Yearly savings statement');
    expect(whenSent('COVER_STARTED')).toMatch(/first premium clears/);
    expect(messageName('SOMETHING_NEW')).toBe('Something new');
  });

  it('reads a template as the customer will, with realistic values', () => {
    const body = 'Policy {{policyNumber}}: your savings balance on 31/12/{{year}} is {{balance}}.';
    expect(placeholdersIn(body)).toEqual(['policyNumber', 'year', 'balance']);
    expect(previewText(body)).toBe('Policy POL-3E440FE9: your savings balance on 31/12/2026 is TZS 2,005,000.00.');
  });

  it('counts what an SMS costs: 160 to a part, 153 once it is split, 70 with a special character', () => {
    expect(smsParts('a'.repeat(160))).toMatchObject({ parts: 1, unicode: false });
    expect(smsParts('a'.repeat(161))).toMatchObject({ parts: 2 });
    expect(smsParts(`${'a'.repeat(70)}’`)).toMatchObject({ parts: 2, unicode: true });
  });
});
