import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useForm } from 'react-hook-form';
import { describe, expect, it } from 'vitest';
import { IndividualFields } from './IndividualFields';
import { blankRegisterIndividualForm, type RegisterIndividualFormValues } from './registerIndividualForm';
import { TANZANIA_REGIONS } from './tanzaniaLocations';

function Harness({ initial }: { initial?: Partial<RegisterIndividualFormValues> }) {
  const { register, control, formState } = useForm<RegisterIndividualFormValues>({
    defaultValues: { ...blankRegisterIndividualForm(), ...initial },
  });
  return <IndividualFields register={register} control={control} errors={formState.errors} />;
}

const region = () => screen.getByLabelText<HTMLSelectElement>('Region');
const district = () => screen.getByLabelText<HTMLSelectElement>('District');
const ward = () => screen.getByLabelText<HTMLSelectElement>('Ward');
const offered = (select: HTMLSelectElement) => [...select.options].map((o) => o.value).filter(Boolean);

/** Region, then district, then ward (user, 2026-11-05): each closed until the one above is chosen. */
describe('IndividualFields address', () => {
  it('opens district only after a region, and ward only after a district', async () => {
    render(<Harness />);
    expect(district()).toBeDisabled();
    expect(ward()).toBeDisabled();

    await userEvent.selectOptions(region(), 'Dar es Salaam');
    expect(district()).toBeEnabled();
    expect(ward()).toBeDisabled();
    // Only that region's councils are offered.
    expect(offered(district())).toEqual([
      'Dar es Salaam City', 'Kigamboni Municipal', 'Kinondoni Municipal', 'Temeke Municipal', 'Ubungo Municipal',
    ]);

    await userEvent.selectOptions(district(), 'Kinondoni Municipal');
    expect(ward()).toBeEnabled();
    // Only that council's wards: Kinondoni has 20 in the 2022 census.
    expect(offered(ward())).toHaveLength(20);
    expect(offered(ward())).toContain('Mwananyamala');
    await userEvent.selectOptions(ward(), 'Mwananyamala');
    expect(ward()).toHaveValue('Mwananyamala');
  });

  it('empties the district and ward when the region changes, so no address mixes two regions', async () => {
    render(<Harness />);
    await userEvent.selectOptions(region(), 'Dar es Salaam');
    await userEvent.selectOptions(district(), 'Kinondoni Municipal');
    await userEvent.selectOptions(ward(), 'Mwananyamala');

    await userEvent.selectOptions(region(), 'Arusha');
    expect(district()).toHaveValue('');
    expect(ward()).toHaveValue('');
    expect(ward()).toBeDisabled();
  });

  it('empties the ward when the district changes', async () => {
    render(<Harness />);
    await userEvent.selectOptions(region(), 'Dar es Salaam');
    await userEvent.selectOptions(district(), 'Kinondoni Municipal');
    await userEvent.selectOptions(ward(), 'Mwananyamala');

    await userEvent.selectOptions(district(), 'Ubungo Municipal');
    expect(ward()).toHaveValue('');
    expect(offered(ward())).not.toContain('Mwananyamala');
  });

  it('offers shehia in Zanzibar, where there are no wards', async () => {
    render(<Harness />);
    await userEvent.selectOptions(region(), 'Kaskazini Pemba');
    expect(offered(district())).toEqual(['Micheweni District', 'Wete Town']);
    await userEvent.selectOptions(district(), 'Wete Town');
    expect(offered(ward())).toContain('Kojani');
  });

  it('keeps older typed values that are not on the list, rather than blanking them', () => {
    render(<Harness initial={{ region: 'Dar es salaam', district: 'Kinondoni', ward: 'Sinza' }} />);
    expect(region()).toHaveValue('Dar es salaam');
    expect(screen.getByRole('option', { name: 'Dar es salaam (not on the list)' })).toBeInTheDocument();
    expect(district()).toHaveValue('Kinondoni');
    expect(ward()).toHaveValue('Sinza');
    expect(screen.getByRole('option', { name: 'Sinza (not on the list)' })).toBeInTheDocument();
  });

  it('marks exactly the fields registration refuses to go without', () => {
    render(<Harness />);
    for (const label of ['Full name', 'ID type', 'ID number', 'Sex']) {
      expect(screen.getByLabelText(label)).toHaveAttribute('aria-required', 'true');
    }
    for (const label of ['Phone number', 'Email', 'Smoker status', 'Region', 'District', 'Ward']) {
      expect(screen.getByLabelText(label)).not.toHaveAttribute('aria-required');
    }
  });
});

/** The census's own totals: Mainland 26 regions / 184 councils / 3,956 wards, Zanzibar 5 / 11 / 388 shehia. */
describe('TANZANIA_REGIONS', () => {
  const councils = TANZANIA_REGIONS.flatMap((r) => r.councils);

  it('holds every region, council and ward the 2022 census lists', () => {
    expect(TANZANIA_REGIONS).toHaveLength(31);
    expect(councils).toHaveLength(195);
    expect(councils.reduce((n, c) => n + c.wards.length, 0)).toBe(3956 + 388);
  });

  it('names nothing twice where a person would have to choose between them', () => {
    expect(new Set(TANZANIA_REGIONS.map((r) => r.region)).size).toBe(31);
    for (const r of TANZANIA_REGIONS) expect(new Set(r.councils.map((c) => c.name)).size).toBe(r.councils.length);
    for (const c of councils) expect(new Set(c.wards).size).toBe(c.wards.length);
  });

  it('carries no extraction debris in a name', () => {
    for (const c of councils) {
      expect(c.name).toMatch(/ (District|Municipal|Town|City)$/);
      // Digits are real in a few names ("Oljoro No.5"); quote marks, underscores and stray spaces never are.
      for (const w of c.wards) expect(w).not.toMatch(/[`"_]|\s{2}|^\s|\s$/);
    }
  });
});
