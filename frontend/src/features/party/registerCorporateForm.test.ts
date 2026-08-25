import { describe, expect, it } from 'vitest';
import { registerCorporateFormSchema, toApiRequest } from './registerCorporateForm';

const valid = () => ({
  registeredName: 'Kilimanjaro SACCO',
  registrationNumber: 'REG-0001',
  phoneNumber: '+255712345999',
  email: 'finance@kilimanjaro-sacco.tz',
});

describe('registerCorporateFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(registerCorporateFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts blank phone and email -- both are optional', () => {
    expect(
      registerCorporateFormSchema.safeParse({ ...valid(), phoneNumber: '', email: '' }).success,
    ).toBe(true);
  });

  it('rejects a blank registered name', () => {
    expect(registerCorporateFormSchema.safeParse({ ...valid(), registeredName: '' }).success).toBe(
      false,
    );
  });

  it('rejects a blank registration number', () => {
    expect(
      registerCorporateFormSchema.safeParse({ ...valid(), registrationNumber: '' }).success,
    ).toBe(false);
  });

  it('rejects a phone number not matching the Tanzanian E.164 pattern', () => {
    expect(
      registerCorporateFormSchema.safeParse({ ...valid(), phoneNumber: '0712345999' }).success,
    ).toBe(false);
  });

  it('rejects a malformed email', () => {
    expect(registerCorporateFormSchema.safeParse({ ...valid(), email: 'not-an-email' }).success).toBe(
      false,
    );
  });
});

describe('toApiRequest', () => {
  it('trims the registered name and registration number', () => {
    const request = toApiRequest(
      registerCorporateFormSchema.parse({
        ...valid(),
        registeredName: '  Kilimanjaro SACCO  ',
        registrationNumber: '  REG-0001  ',
      }),
    );
    expect(request.registeredName).toBe('Kilimanjaro SACCO');
    expect(request.registrationNumber).toBe('REG-0001');
  });

  it('omits phoneNumber and email entirely when both are blank', () => {
    const request = toApiRequest(
      registerCorporateFormSchema.parse({ ...valid(), phoneNumber: '', email: '' }),
    );
    expect('phoneNumber' in request.contactInfo).toBe(false);
    expect('email' in request.contactInfo).toBe(false);
  });
});
