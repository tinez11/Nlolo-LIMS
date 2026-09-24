import { describe, expect, it } from 'vitest';
import { assessorName } from './assessorName';

describe('assessorName', () => {
  it('names the assessor as their token did', () => {
    expect(assessorName({ assessorName: 'Daudi Assessor' })).toBe('Daudi Assessor');
  });

  it('says a name was not recorded rather than showing anything else', () => {
    // Assessments written before the name was captured. The subject is right there on the
    // same object, and is exactly what must not leak into the sentence.
    expect(assessorName({ assessorName: null })).toBe('an assessor whose name was not recorded');
  });

  it('treats a blank name as no name', () => {
    expect(assessorName({ assessorName: '   ' }, 'Name not recorded')).toBe('Name not recorded');
  });
});
