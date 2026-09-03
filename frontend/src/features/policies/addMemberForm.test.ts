import { describe, expect, it } from 'vitest';
import {
  blankMemberForm,
  memberFormSchema,
  toApiRequest,
  type MemberFormContext,
  type MemberFormValues,
} from './addMemberForm';

const PERSON = '11111111-2222-3333-4444-555555555555';
const TODAY = '2026-09-03';

function values(overrides: Partial<MemberFormValues> = {}): MemberFormValues {
  return { ...blankMemberForm(), memberPartyId: PERSON, ...overrides };
}

function errorsFor(context: Partial<MemberFormContext>, input: MemberFormValues) {
  const result = memberFormSchema({
    benefitBasis: 'FLAT',
    gradeCodes: [],
    today: TODAY,
    ...context,
  }).safeParse(input);
  if (result.success) return {} as Record<string, string>;
  // FIRST issue per field, not the last. An empty person id trips both the
  // required check and the format check, and react-hook-form renders the first
  // one -- so asserting the last would test a message no user ever sees.
  const errors: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const field = String(issue.path[0]);
    if (!(field in errors)) errors[field] = issue.message;
  }
  return errors;
}

describe('memberFormSchema', () => {
  it('accepts a bare person on a flat scheme', () => {
    expect(errorsFor({ benefitBasis: 'FLAT' }, values())).toEqual({});
  });

  it('requires a person', () => {
    expect(errorsFor({}, values({ memberPartyId: '' })).memberPartyId).toMatch(/Choose the person/);
  });

  /**
   * The rejections matter as much as the requirements. Uploading a salaried
   * schedule to a flat scheme must fail rather than quietly produce plausible,
   * wrong numbers — the same reasoning `PolicyApiImpl.valueMember` follows.
   */
  it('rejects a salary on a flat scheme', () => {
    expect(errorsFor({ benefitBasis: 'FLAT' }, values({ salaryAmount: '4000000.00' })).salaryAmount)
      .toMatch(/flat benefit/);
  });

  it('rejects a grade on a flat scheme', () => {
    expect(errorsFor({ benefitBasis: 'FLAT' }, values({ gradeCode: 'STAFF' })).gradeCode)
      .toMatch(/flat benefit/);
  });

  it('requires a salary on a salary-multiple scheme', () => {
    expect(errorsFor({ benefitBasis: 'SALARY_MULTIPLE' }, values()).salaryAmount)
      .toMatch(/every member needs one/);
  });

  it('rejects a malformed salary', () => {
    const errors = errorsFor({ benefitBasis: 'SALARY_MULTIPLE' }, values({ salaryAmount: '4,000,000' }));
    expect(errors.salaryAmount).toMatch(/amount like/);
  });

  it('accepts a well-formed salary', () => {
    expect(errorsFor({ benefitBasis: 'SALARY_MULTIPLE' }, values({ salaryAmount: '4000000.00' })))
      .toEqual({});
  });

  it('requires a grade the scheme actually has', () => {
    const context = { benefitBasis: 'GRADED' as const, gradeCodes: ['MANAGEMENT', 'STAFF'] };
    expect(errorsFor(context, values()).gradeCode).toMatch(/every member needs one/);
    // The message lists what the scheme DOES have, so the fix is visible.
    expect(errorsFor(context, values({ gradeCode: 'DIRECTORS' })).gradeCode)
      .toBe('Grade DIRECTORS is not on this scheme (MANAGEMENT, STAFF)');
    expect(errorsFor(context, values({ gradeCode: 'STAFF' }))).toEqual({});
  });

  /** Mirrors a real 409, answered while the date field still has focus. */
  it('refuses a join date in the future', () => {
    expect(errorsFor({}, values({ joinedOn: '2026-10-01' })).joinedOn)
      .toMatch(/cannot start in the future/);
  });

  it('allows backdating — a schedule reaches the insurer late', () => {
    expect(errorsFor({}, values({ joinedOn: '2026-08-01' }))).toEqual({});
  });

  it('refuses a join date before the scheme commenced', () => {
    expect(
      errorsFor({ commencementDate: '2026-08-15' }, values({ joinedOn: '2026-08-01' })).joinedOn,
    ).toMatch(/commenced on 2026-08-15/);
  });

  it('allows joining on the commencement date itself', () => {
    expect(errorsFor({ commencementDate: '2026-08-15' }, values({ joinedOn: '2026-08-15' })))
      .toEqual({});
  });

  it('allows joining today', () => {
    expect(errorsFor({}, values({ joinedOn: TODAY }))).toEqual({});
  });
});

describe('toApiRequest', () => {
  /**
   * Blank optional fields are omitted, not sent as empty strings: `joinedOn: ''`
   * fails date parsing server-side and `salaryAmount: ''` fails the amount
   * pattern, where absence means "the scheme's commencement date" and "this
   * basis collects no salary".
   */
  it('omits every blank optional field rather than sending an empty string', () => {
    expect(toApiRequest(values())).toEqual({ memberPartyId: PERSON });
  });

  it('sends the fields that were filled', () => {
    expect(toApiRequest(values({ salaryAmount: '4000000.00', joinedOn: '2026-08-01' }))).toEqual({
      memberPartyId: PERSON,
      salaryAmount: '4000000.00',
      joinedOn: '2026-08-01',
    });
  });

  it('trims what it sends', () => {
    expect(toApiRequest(values({ gradeCode: '  STAFF  ' }))).toEqual({
      memberPartyId: PERSON,
      gradeCode: 'STAFF',
    });
  });
});
