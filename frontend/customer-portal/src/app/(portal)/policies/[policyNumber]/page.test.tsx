import { describe, expect, it } from 'vitest';
import { fromWireBeneficiaries, toWireBeneficiaries } from './page';
import type { components } from '@/types/api/policy';

type ApiBeneficiaryInput = components['schemas']['BeneficiaryInput'];

/**
 * Direct coverage of the wire-mapping functions themselves. `BeneficiaryForm`'s own tests exercise
 * the component, not these two functions -- they never touch `revocable` at all, so a bug confined
 * to `toWireBeneficiaries`/`fromWireBeneficiaries` (e.g. hardcoding `revocable: true`, or dropping
 * the field entirely) would pass the component suite unnoticed. These tests close that gap.
 */
describe('fromWireBeneficiaries', () => {
  it('carries an irrevocable (revocable: false) designation through unchanged', () => {
    const [row] = fromWireBeneficiaries([
      { type: 'FREEFORM', freeformDesignee: 'Asha Juma', sharePercent: 100, revocable: false },
    ]);
    expect(row.revocable).toBe(false);
  });

  it('defaults revocable to true when the wire response genuinely omits it', () => {
    const [row] = fromWireBeneficiaries([
      { type: 'FREEFORM', freeformDesignee: 'Asha Juma', sharePercent: 100 } as ApiBeneficiaryInput,
    ]);
    expect(row.revocable).toBe(true);
  });
});

describe('toWireBeneficiaries', () => {
  it('sends revocable: false through unchanged, never hardcoding true', () => {
    const [wire] = toWireBeneficiaries([
      { freeformDesignee: 'Asha Juma', sharePercentage: '100', revocable: false },
    ]);
    expect(wire.revocable).toBe(false);
  });

  it('defaults revocable to true when the form row genuinely omits it', () => {
    const [wire] = toWireBeneficiaries([
      { freeformDesignee: 'Asha Juma', sharePercentage: '100' },
    ]);
    expect(wire.revocable).toBe(true);
  });
});
