import { describe, expect, it } from 'vitest';
import { actorWords, changeWords, fieldWords, outcomeWords } from './activityWords';

describe('activity words', () => {
  it('names who acted in plain words', () => {
    expect(actorWords({ actorName: 'Asha M.', actorKind: 'STAFF' })).toBe('Asha M. (staff)');
    expect(actorWords({ actorName: 'Juma K', actorKind: 'CUSTOMER' })).toBe('Juma K (customer)');
    expect(actorWords({ actorName: 'Neema', actorKind: 'AGENT' })).toBe('Neema (agent)');
    expect(actorWords({ actorName: null, actorKind: 'SYSTEM' })).toBe('System');
    expect(actorWords({ actorName: null, actorKind: 'STAFF' })).toBe('Someone (staff)');
  });

  it('says the outcome, and why a refusal happened', () => {
    expect(outcomeWords({ outcome: 'DONE', reason: null })).toBe('Done');
    expect(outcomeWords({ outcome: 'REFUSED', reason: 'You cannot approve your own payout' })).toBe(
      'Refused: You cannot approve your own payout',
    );
    expect(outcomeWords({ outcome: 'REFUSED', reason: null })).toBe('Refused');
    expect(outcomeWords({ outcome: 'FAILED', reason: null })).toBe('Failed');
  });

  it('names a field the way a person would', () => {
    expect(fieldWords('phoneNumber')).toBe('Phone number');
    expect(fieldWords('sumAssured')).toBe('Sum assured');
    expect(fieldWords('premium')).toBe('Premium');
  });

  it('shows a change as before and after, or set to', () => {
    expect(changeWords({ field: 'premium', before: '45000', after: '52000' })).toBe('Premium: 45000 → 52000');
    expect(changeWords({ field: 'phoneNumber', before: null, after: '+255713000111' })).toBe(
      'Phone number: set to +255713000111',
    );
    expect(changeWords({ field: 'note', before: 'old', after: null })).toBe('Note: old → (cleared)');
  });
});
