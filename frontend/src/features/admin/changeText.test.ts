import { describe, expect, it } from 'vitest';
import type { AccessChangeView } from '@/api/access';
import { describeChange } from './changeText';

const change = (changeType: AccessChangeView['changeType'], detail: Record<string, unknown>): AccessChangeView => ({
  changeId: 'c-1', userId: 'u-1', userName: 'Rehema Finance', roleId: null, roleName: null, changeType, detail,
  status: 'APPLIED', requestedByName: 'Asha Admin', requestedAt: '2026-11-14T08:00:00Z', decidedByName: null,
  decidedAt: null,
});

describe('describeChange', () => {
  it('says where a sign-in code now goes, by the last three digits only', () => {
    expect(describeChange(change('CONTACT_CHANGED', { phoneEnds: '369', textLanguage: 'sw' })))
      .toBe("Changed Rehema Finance's phone (ends 369), texts in Kiswahili");
    expect(describeChange(change('CONTACT_CHANGED', { phoneRemoved: true, textLanguage: 'en' })))
      .toBe("Removed Rehema Finance's phone, texts in English");
  });

  it('still words the older changes', () => {
    expect(describeChange(change('PASSWORD_RESET', {}))).toBe('Gave Rehema Finance a new temporary password');
  });
});
