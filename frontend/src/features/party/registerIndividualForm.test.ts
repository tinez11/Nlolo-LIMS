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
  // Required as of 2026-09-30. Sex because the pricing pipeline refuses to price a life
  // without it, and an identity document because a client nobody can identify cannot be
  // KYC-verified. A fixture omitting them would model a client the form no longer accepts.
  sex: 'FEMALE' as const,
  idType: 'NATIONAL_ID' as const,
  idNumber: '19900512-12345-00001-12',
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
    // Asserted on the fields that are still OPTIONAL. Sex and the identity document became
    // required on 2026-09-30 and so are always answered now; smoker status is the one to keep
    // an eye on, because UNKNOWN means the question was put and not answered while absent
    // means nobody asked, and a product may price those differently.
    const request = toApiRequest(registerIndividualFormSchema.parse(valid()));
    expect('smokerStatus' in request).toBe(false);
    expect('occupation' in request).toBe(false);
    expect('employerName' in request).toBe(false);
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
    const result = registerIndividualFormSchema.safeParse({ ...valid(), idType: '', idNumber: 'A1234567' });
    expect(result.success).toBe(false);
  });

  it('rejects a document type with no ID number', () => {
    const result = registerIndividualFormSchema.safeParse({ ...valid(), idType: 'PASSPORT', idNumber: '' });
    expect(result.success).toBe(false);
  });

  /**
   * This asserted the opposite until 2026-09-30, when the document became required: a client
   * nobody can identify cannot be KYC-verified, and an unverifiable client cannot hold a
   * policy. Asked for at registration rather than discovered at verification, by which point
   * the agent who took the details has moved on.
   */
  it('rejects a client with no identity document at all', () => {
    const result = registerIndividualFormSchema.safeParse({
      ...valid(),
      idType: '',
      idNumber: '',
    });
    expect(result.success).toBe(false);
  });

  it('rejects a client whose sex is unrecorded, because nothing can price them', () => {
    const result = registerIndividualFormSchema.safeParse({ ...valid(), sex: '' });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.map((i) => i.path.join('.'))).toContain('sex');
    }
  });

  it('carries the client reference through, and leaves it out when blank', () => {
    const withRef = toApiRequest(
      registerIndividualFormSchema.parse({ ...valid(), clientReference: 'CLT-000412' }),
    );
    expect(withRef.clientReference).toBe('CLT-000412');
    // Absent rather than empty: "" would occupy the unique index and refuse the blank to
    // everybody else, which is a confusing way to discover a typo.
    expect(toApiRequest(registerIndividualFormSchema.parse(valid()))).not.toHaveProperty(
      'clientReference',
    );
  });

  it('rejects a nationality that is not two letters', () => {
    expect(
      registerIndividualFormSchema.safeParse({ ...valid(), nationality: 'TZA' }).success,
    ).toBe(false);
  });
});
