import { describe, expect, it } from 'vitest';
import { actorWords, byDay, changeWords, dayWords, fieldWords, reasonWords } from './activityWords';

describe('activity words', () => {
  it('names who acted in plain words', () => {
    expect(actorWords({ actorName: 'Asha M.', actorKind: 'STAFF' })).toBe('Asha M. (staff)');
    expect(actorWords({ actorName: 'Juma K', actorKind: 'CUSTOMER' })).toBe('Juma K (customer)');
    expect(actorWords({ actorName: 'Neema', actorKind: 'AGENT' })).toBe('Neema (agent)');
    expect(actorWords({ actorName: null, actorKind: 'SYSTEM' })).toBe('System');
    expect(actorWords({ actorName: null, actorKind: 'STAFF' })).toBe('Someone (staff)');
    expect(actorWords({ actorName: null, actorKind: 'UNRECORDED' })).toBe('Not recorded');
  });

  it('says why a line did not go through, and nothing for one that did', () => {
    expect(reasonWords({ outcome: 'REFUSED', reason: ' You cannot approve your own payout ' })).toBe(
      'You cannot approve your own payout',
    );
    expect(reasonWords({ outcome: 'FAILED', reason: 'The bank did not answer' })).toBe('The bank did not answer');
    expect(reasonWords({ outcome: 'REFUSED', reason: null })).toBeNull();
    expect(reasonWords({ outcome: 'REFUSED', reason: '  ' })).toBeNull();
    expect(reasonWords({ outcome: 'DONE', reason: 'stale words' })).toBeNull();
  });

  it('cuts lines into days, newest first, keeping their order', () => {
    const at = (iso: string, id: string) => ({ id, occurredAt: iso });
    // Local noon, so the day is the same in any timezone the suite runs in.
    const days = byDay([
      at(new Date(2026, 10, 15, 12, 30).toISOString(), 'a'),
      at(new Date(2026, 10, 15, 9, 0).toISOString(), 'b'),
      at(new Date(2026, 10, 13, 18, 0).toISOString(), 'c'),
    ]);
    expect(days.map((d) => d.day)).toEqual(['2026-11-15', '2026-11-13']);
    expect(days.map((d) => d.lines.map((l) => l.id))).toEqual([['a', 'b'], ['c']]);
    expect(byDay([])).toEqual([]);
  });

  it('heads a day as Today, Yesterday, or its weekday and date', () => {
    const now = new Date(2026, 10, 15, 16, 0);
    expect(dayWords(new Date(2026, 10, 15, 8, 0).toISOString(), now)).toBe('Today');
    expect(dayWords(new Date(2026, 10, 14, 23, 59).toISOString(), now)).toBe('Yesterday');
    const older = dayWords(new Date(2026, 10, 12, 10, 0).toISOString(), now);
    expect(older).toMatch(/2026/);
    expect(older).not.toMatch(/^(Today|Yesterday)$/);
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
