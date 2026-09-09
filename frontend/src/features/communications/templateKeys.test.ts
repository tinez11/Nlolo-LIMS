import { describe, expect, it } from 'vitest';
import { humanizeTemplateKey } from './templateKeys';

describe('humanizeTemplateKey', () => {
  it('turns a screaming snake key into something a person reads', () => {
    expect(humanizeTemplateKey('OFFER_MADE')).toBe('Offer made');
    expect(humanizeTemplateKey('COVER_STARTED')).toBe('Cover started');
    expect(humanizeTemplateKey('OFFER_NOT_TAKEN_UP')).toBe('Offer not taken up');
  });

  it('renders an em dash for a missing key rather than the word undefined', () => {
    // The outbox stores template_key NOT NULL, but every field on a generated view is optional
    // -- the specs declare almost no `required` list, so screens have to defend on each one.
    expect(humanizeTemplateKey(undefined)).toBe('—');
  });
});
