import { describe, expect, it } from 'vitest';
import { registerIndividualFormSchema, toApiRequest } from './registerIndividualForm';

const valid = () => ({
  fullName: 'Amina Hassan',
  dateOfBirth: '1990-05-12',
  phoneNumber: '+255712345678',
  email: 'amina@example.tz',
});

describe('registerIndividualFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(registerIndividualFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts blank phone and email -- both are optional', () => {
    expect(
      registerIndividualFormSchema.safeParse({ ...valid(), phoneNumber: '', email: '' }).success,
    ).toBe(true);
  });

  it('rejects a blank full name', () => {
    expect(registerIndividualFormSchema.safeParse({ ...valid(), fullName: '' }).success).toBe(false);
  });

  it('rejects a date of birth in the future', () => {
    const future = `${new Date().getFullYear() + 1}-01-01`;
    expect(
      registerIndividualFormSchema.safeParse({ ...valid(), dateOfBirth: future }).success,
    ).toBe(false);
  });

  it('rejects a phone number not matching the Tanzanian E.164 pattern', () => {
    expect(
      registerIndividualFormSchema.safeParse({ ...valid(), phoneNumber: '0712345678' }).success,
    ).toBe(false);
  });

  it('rejects a malformed email', () => {
    expect(
      registerIndividualFormSchema.safeParse({ ...valid(), email: 'not-an-email' }).success,
    ).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('trims the full name', () => {
    const request = toApiRequest(registerIndividualFormSchema.parse({ ...valid(), fullName: '  Amina Hassan  ' }));
    expect(request.fullName).toBe('Amina Hassan');
  });

  it('omits phoneNumber and email entirely when both are blank', () => {
    const request = toApiRequest(
      registerIndividualFormSchema.parse({ ...valid(), phoneNumber: '', email: '' }),
    );
    expect('phoneNumber' in request.contactInfo).toBe(false);
    expect('email' in request.contactInfo).toBe(false);
  });

  it('includes phoneNumber and email when present', () => {
    const request = toApiRequest(registerIndividualFormSchema.parse(valid()));
    expect(request.contactInfo.phoneNumber).toBe('+255712345678');
    expect(request.contactInfo.email).toBe('amina@example.tz');
  });
});
