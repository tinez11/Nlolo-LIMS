import { describe, expect, it } from 'vitest';
import {
  money,
  splitSchema,
  statementSchema,
  switchSchema,
  toSwitchRequest,
  toTopUpRequest,
  toWithdrawalRequest,
  topUpSchema,
  withdrawalSchema,
} from './u2Forms';

const messages = (r: { success: boolean; error?: { issues: { message: string }[] } }) =>
  r.success ? [] : (r.error?.issues ?? []).map((i) => i.message);

describe('the server money format', () => {
  it('groups thousands and keeps two places', () => {
    expect(money(50000)).toBe('50,000.00');
  });
});

describe('splitSchema', () => {
  it('refuses a split that does not total 100', () => {
    const r = splitSchema.safeParse({ shares: [{ fundCode: 'EQ1', percent: '60' }, { fundCode: 'BD1', percent: '30' }] });
    expect(messages(r)).toEqual(['The fund split totals 90%; it must total 100%']);
  });

  it('refuses a share that is not a whole percent', () => {
    const r = splitSchema.safeParse({ shares: [{ fundCode: 'EQ1', percent: '50.5' }, { fundCode: 'BD1', percent: '49.5' }] });
    expect(messages(r)).toEqual(["Each fund's share is a whole percent from 1 to 100"]);
  });
});

describe('switchSchema', () => {
  it('needs a fund to move out of', () => {
    const r = switchSchema.safeParse({ out: [{ fundCode: 'EQ1', percent: '' }], into: [{ fundCode: 'BD1', percent: '100' }] });
    expect(messages(r)).toEqual(['A switch names at least one fund to move out of']);
  });

  it('sends only the funds with a percent', () => {
    const v = { out: [{ fundCode: 'EQ1', percent: '50' }, { fundCode: 'BD1', percent: '' }], into: [{ fundCode: 'BD1', percent: '100' }] };
    expect(switchSchema.safeParse(v).success).toBe(true);
    expect(toSwitchRequest(v)).toEqual({ out: [{ fundCode: 'EQ1', percent: 50 }], into: [{ fundCode: 'BD1', percent: 100 }] });
  });
});

describe('withdrawalSchema', () => {
  const schema = withdrawalSchema(100000, 'TZS');

  it('refuses less than the minimum, in the server words', () => {
    const r = schema.safeParse({ grossAmount: '50000', funds: [], payeeRef: '+255700000600' });
    expect(messages(r)).toEqual(['A withdrawal is at least 100,000.00 TZS']);
  });

  it('needs named amounts to total the gross', () => {
    const r = schema.safeParse({
      grossAmount: '150000',
      funds: [{ fundCode: 'EQ1', amount: '100000' }, { fundCode: 'BD1', amount: '' }],
      payeeRef: '+255700000600',
    });
    expect(messages(r)).toEqual(["The named funds' amounts total 100,000.00; they must total the withdrawal of 150,000.00"]);
  });

  it('needs a payee', () => {
    expect(messages(schema.safeParse({ grossAmount: '150000', funds: [], payeeRef: '' }))).toEqual([
      'A withdrawal needs the payee to pay',
    ]);
  });

  it('is pro rata when no fund is named', () => {
    const v = { grossAmount: '150000.00', funds: [{ fundCode: 'EQ1', amount: '' }], payeeRef: '+255700000600' };
    expect(toWithdrawalRequest(v)).toEqual({ grossAmount: '150000.00', funds: [], payeeRef: '+255700000600' });
  });
});

describe('topUpSchema', () => {
  const schema = topUpSchema(50000, 'TZS');

  it('refuses less than the minimum, in the server words', () => {
    expect(messages(schema.safeParse({ amount: '10000', payerRef: '+255700000700', split: [] }))).toEqual([
      'A top-up is at least 50,000.00 TZS',
    ]);
  });

  it('takes the split in force when none is typed in', () => {
    const v = { amount: '200000', payerRef: '+255700000700', split: [{ fundCode: 'EQ1', percent: '' }] };
    expect(schema.safeParse(v).success).toBe(true);
    expect(toTopUpRequest(v).split).toEqual([]);
  });

  it('checks its own split when one is typed in', () => {
    const v = { amount: '200000', payerRef: '+255700000700', split: [{ fundCode: 'EQ1', percent: '70' }] };
    expect(messages(schema.safeParse(v))).toEqual(['The fund split totals 70%; it must total 100%']);
  });
});

describe('statementSchema', () => {
  const schema = statementSchema('2026-10-05');

  it('ends today at the latest', () => {
    expect(messages(schema.safeParse({ from: '2026-10-01', to: '2026-10-06' }))).toEqual([
      'A statement period ends today at the latest',
    ]);
  });

  it('starts on or before its last day', () => {
    expect(messages(schema.safeParse({ from: '2026-10-05', to: '2026-10-01' }))).toEqual([
      'A statement period starts on or before its last day',
    ]);
  });
});
