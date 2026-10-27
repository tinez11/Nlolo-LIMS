import { describe, expect, it } from 'vitest';
import { blankNewUserForm, newUserFormSchema, suggestedUsername, toApiRequest } from './newUserForm';

const valid = { ...blankNewUserForm(), firstName: 'Asha', lastName: 'Juma', username: 'asha.juma', roleCodes: ['UNDERWRITER'] };

describe('newUserFormSchema', () => {
  it('accepts a name and a login, with everything else optional', () => {
    expect(newUserFormSchema.safeParse(valid).success).toBe(true);
  });

  it('refuses a login with capitals or spaces, as Keycloak would store it differently', () => {
    expect(newUserFormSchema.safeParse({ ...valid, username: 'Asha Juma' }).success).toBe(false);
  });

  it('takes a phone written the everyday way and refuses one that is not Tanzanian', () => {
    expect(newUserFormSchema.safeParse({ ...valid, phone: '0712 345 678' }).success).toBe(true);
    expect(newUserFormSchema.safeParse({ ...valid, phone: '+44 20 7946 0000' }).success).toBe(false);
  });
});

describe('suggestedUsername', () => {
  it('is first.last in lower case, as deploy/server/add-user.sh names logins', () => {
    expect(suggestedUsername('Asha', "Juma-O'Neil")).toBe('asha.jumaoneil');
  });
});

describe('toApiRequest', () => {
  it('sends absent optionals as null and a phone as +255', () => {
    expect(toApiRequest({ ...valid, phone: '0712345678' })).toEqual({
      username: 'asha.juma', firstName: 'Asha', lastName: 'Juma', email: null, phone: '+255712345678',
      homeBranch: null, roleCodes: ['UNDERWRITER'], permissions: [],
    });
  });
});
