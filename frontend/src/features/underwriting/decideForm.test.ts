import { describe, expect, it } from 'vitest';
import {
  blankDecideForm,
  decideFormSchema,
  isOverride,
  separationOfDutiesConflict,
  toApiRequest,
  type DecideFormInput,
} from './decideForm';

describe('separationOfDutiesConflict', () => {
  const view = { openedBy: 'opener', assessedBy: ['assessor-a', 'assessor-b'] };

  it('names the opener and every assessor, and nobody else', () => {
    expect(separationOfDutiesConflict(view, 'opener')).toBe('opened');
    expect(separationOfDutiesConflict(view, 'assessor-b')).toBe('assessed');
    expect(separationOfDutiesConflict(view, 'someone-else')).toBeNull();
  });

  it('conflicts with nothing when the subject is unknown -- the server stays the authority', () => {
    expect(separationOfDutiesConflict(view, null)).toBeNull();
    expect(separationOfDutiesConflict({}, 'opener')).toBeNull();
  });
});

const valid = (over: Partial<DecideFormInput> = {}): DecideFormInput => ({
  ...blankDecideForm(),
  reason: 'Standard risk, in line with the recommendation',
  ...over,
});

function issuesFor(input: DecideFormInput, field: string): string[] {
  const result = decideFormSchema.safeParse(input);
  return result.success ? [] : result.error.issues.filter((i) => i.path[0] === field).map((i) => i.message);
}

describe('decideFormSchema', () => {
  it('accepts a plain acceptance with a reason', () => {
    expect(decideFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('requires a reason, because a decision nobody explained cannot be reviewed', () => {
    expect(issuesFor(valid({ reason: '   ' }), 'reason')).toHaveLength(1);
  });

  it('refuses a reason longer than the column holds', () => {
    expect(issuesFor(valid({ reason: 'x'.repeat(501) }), 'reason')).toHaveLength(1);
  });
});

describe('the loading, which is paired with the outcome', () => {
  it('requires one on a loaded acceptance', () => {
    expect(issuesFor(valid({ outcome: 'LOADED' }), 'loadingPercent')).toContain(
      'A loaded acceptance needs the loading percentage',
    );
  });

  it('refuses a loading of zero, which is not the same as no loading', () => {
    expect(issuesFor(valid({ outcome: 'LOADED', loadingPercent: '0' }), 'loadingPercent')).toContain(
      'A loading must be greater than zero',
    );
  });

  it('refuses a loading the NUMERIC(5,2) column cannot hold', () => {
    expect(issuesFor(valid({ outcome: 'LOADED', loadingPercent: '1000' }), 'loadingPercent')).toContain(
      'A loading above 999.99% cannot be recorded',
    );
  });

  it.each(['ACCEPT', 'DECLINED', 'POSTPONED'] as const)(
    'refuses a loading on a %s decision rather than silently dropping it',
    (outcome) => {
      expect(issuesFor(valid({ outcome, loadingPercent: '25' }), 'loadingPercent')).toContain(
        'A loading only applies to an accepted-with-loading decision',
      );
    },
  );

  it('accepts a real loading on a loaded acceptance', () => {
    expect(decideFormSchema.safeParse(valid({ outcome: 'LOADED', loadingPercent: '37.5' })).success).toBe(true);
  });
});

describe('isOverride', () => {
  it('is true when the decision departs from the recommendation', () => {
    expect(isOverride('DECLINED', 'ACCEPT')).toBe(true);
  });

  it('is false when it agrees', () => {
    expect(isOverride('ACCEPT', 'ACCEPT')).toBe(false);
  });

  /**
   * Matches the server exactly. A case can be decided before the engine has run over any
   * evidence, and a pre-V5 case carries no recommendation at all -- neither is a
   * disagreement, and neither should demand a senior underwriter.
   */
  it('is false when there is no recommendation to depart from', () => {
    expect(isOverride('DECLINED', null)).toBe(false);
    expect(isOverride('DECLINED', undefined)).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('omits loadingPercent entirely on a non-loaded decision', () => {
    const request = toApiRequest(decideFormSchema.parse(valid()));
    expect('loadingPercent' in request).toBe(false);
  });

  it('sends the loading as a number on a loaded acceptance', () => {
    const request = toApiRequest(
      decideFormSchema.parse(valid({ outcome: 'LOADED', loadingPercent: '37.50' })),
    );
    expect(request.loadingPercent).toBe(37.5);
    expect(request.outcome).toBe('LOADED');
  });

  it('sends the trimmed reason', () => {
    const request = toApiRequest(decideFormSchema.parse(valid({ reason: '  Adverse history  ' })));
    expect(request.reason).toBe('Adverse history');
  });
});
