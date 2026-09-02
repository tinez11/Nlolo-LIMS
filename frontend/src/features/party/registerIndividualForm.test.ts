import { describe, expect, it } from 'vitest';
import {
  blankRegisterIndividualForm,
  registerIndividualFormSchema,
  toApiRequest,
} from './registerIndividualForm';

// Built on the blank form rather than as a literal, so the fixture always carries
// every key the form actually submits. react-hook-form initialises from
// blankRegisterIndividualForm(), so a partial literal would be testing a shape no
// real submission has -- and would need editing every time a field is added.
const valid = () => ({
  ...blankRegisterIndividualForm(),
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

describe('the person record', () => {
  const withPerson = () => ({
    ...valid(),
    sex: 'FEMALE' as const,
    smokerStatus: 'NON_SMOKER' as const,
    idType: 'NATIONAL_ID' as const,
    idNumber: '19880209-11111-00001-22',
    occupation: 'Teacher',
    occupationClass: 'PROF_1',
    employerName: 'Ilala Secondary School',
    addressLine: 'Plot 44, Uhuru Road',
    ward: 'Upanga',
    district: 'Ilala',
    region: 'Dar es Salaam',
    postalCode: '11101',
  });

  it('sends every recorded field', () => {
    const request = toApiRequest(registerIndividualFormSchema.parse(withPerson()));
    expect(request.sex).toBe('FEMALE');
    expect(request.smokerStatus).toBe('NON_SMOKER');
    expect(request.idType).toBe('NATIONAL_ID');
    expect(request.occupationClass).toBe('PROF_1');
    expect(request.address?.region).toBe('Dar es Salaam');
  });

  // The distinction the whole nullable design exists to protect: an omitted field
  // means "not recorded", and "" would be a recorded blank. On a KYC register those
  // are different claims about a person.
  it('omits unanswered fields rather than sending empty strings', () => {
    const request = toApiRequest(registerIndividualFormSchema.parse(valid()));
    expect('sex' in request).toBe(false);
    expect('smokerStatus' in request).toBe(false);
    expect('occupation' in request).toBe(false);
    expect('idType' in request).toBe(false);
  });

  it('omits the address object entirely when no part of it was given', () => {
    const request = toApiRequest(registerIndividualFormSchema.parse(valid()));
    expect('address' in request).toBe(false);
  });

  it('sends a partial address, because a half-known address still helps', () => {
    const request = toApiRequest(
      registerIndividualFormSchema.parse({ ...valid(), region: 'Mwanza' }),
    );
    expect(request.address).toEqual({ region: 'Mwanza' });
  });

  it('upper-cases the nationality, so TZ and tz are one value', () => {
    const request = toApiRequest(registerIndividualFormSchema.parse({ ...valid(), nationality: 'tz' }));
    expect(request.nationality).toBe('TZ');
  });

  it('defaults nationality to TZ as a visible, changeable value', () => {
    expect(blankRegisterIndividualForm().nationality).toBe('TZ');
  });

  it('rejects an ID number with no document type', () => {
    const result = registerIndividualFormSchema.safeParse({ ...valid(), idNumber: 'A1234567' });
    expect(result.success).toBe(false);
  });

  it('rejects a document type with no ID number', () => {
    const result = registerIndividualFormSchema.safeParse({ ...valid(), idType: 'PASSPORT' });
    expect(result.success).toBe(false);
  });

  it('accepts neither, since an identity document is optional', () => {
    expect(registerIndividualFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('rejects a nationality that is not two letters', () => {
    expect(
      registerIndividualFormSchema.safeParse({ ...valid(), nationality: 'TZA' }).success,
    ).toBe(false);
  });
});
