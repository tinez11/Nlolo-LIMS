import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useForm } from 'react-hook-form';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { quoteFuneral } from '@/api/funeral';
import type { FuneralTermsView } from '@/api/types';
import { FuneralLivesFields } from './FuneralLivesFields';
import type { OpenCaseFormInput } from './openCaseForm';

vi.mock('@/api/funeral');

/** One role rule, with the ages the form never reads here. */
const rule = (role: FuneralTermsView['roles'][number]['role']) => ({
  role,
  maxLives: 4,
  minEntryAge: 0,
  maxEntryAge: 70,
  coverStopAge: null,
  studentStopAge: null,
});

const terms = (
  roles: FuneralTermsView['roles'][number]['role'][],
  benefitRoles: FuneralTermsView['benefits'][number]['role'][],
): FuneralTermsView => ({
  plans: [{ planCode: 'A', name: 'Plan A', groupMonthlyRate: null, groupRatePeriod: 'MONTHLY' }],
  benefits: benefitRoles.map((role) => ({ planCode: 'A', role, benefit: 1000000 })),
  premiums: [],
  roles: roles.map(rule),
  maxPricedAge: 70,
  waitingPeriodMonths: null,
  accidentWaivesWaiting: false,
  dependantClaimPayee: 'MAIN_MEMBER',
  freeCoverToPaidDate: false,
  soldAs: 'INDIVIDUAL',
  onMainMemberDeath: 'POLICY_ENDS',
});

/** The host supplies what the page supplies: one react-hook-form, nothing else. */
function Host({ terms: t, planCode = '' }: { terms: FuneralTermsView; planCode?: string }) {
  const form = useForm<OpenCaseFormInput>({
    defaultValues: {
      funeralPlanCode: planCode,
      funeralDependants: [],
      premiumFrequency: '',
      proposedCommencementDate: '',
    } as Partial<OpenCaseFormInput> as OpenCaseFormInput,
  });
  return (
    <FuneralLivesFields
      terms={t}
      productId="prod-1"
      productVersionId="ver-1"
      mainMember={{ name: 'Main Member', dateOfBirth: '1990-01-01' }}
      register={form.register}
      control={form.control as unknown as Parameters<typeof FuneralLivesFields>[0]['control']}
      errors={form.formState.errors}
      setValue={form.setValue}
    />
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(quoteFuneral).mockRejectedValue({ status: 422, detail: 'not asked in these tests' });
});

/**
 * The form offers the roles the cover actually takes (2026-10-16).
 *
 * <p>It used to offer every role the VERSION ruled -- a different set from what the chosen PLAN pays for -- and a new
 * dependant row arrived hard-coded as a CHILD whether or not the product covered one. On a plan for the main member
 * alone that produced "This product does not cover a child" about a row nobody had chosen.
 */
describe('FuneralLivesFields', () => {
  it('offers no family at all on a cover that takes only the main member', () => {
    render(<Host terms={terms(['MAIN_MEMBER'], ['MAIN_MEMBER'])} planCode="A" />);

    expect(screen.getByText(/covers the main member only/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add dependant/ })).not.toBeInTheDocument();
    expect(screen.queryByText('The family')).not.toBeInTheDocument();
  });

  /** A role the version rules but the chosen plan prices nothing for is not covered on that plan. */
  it('narrows the roles to what the chosen plan pays for', async () => {
    render(<Host terms={terms(['MAIN_MEMBER', 'SPOUSE', 'PARENT'], ['MAIN_MEMBER', 'SPOUSE'])} planCode="A" />);

    await userEvent.click(screen.getByRole('button', { name: /Add dependant/ }));

    const role = screen.getByLabelText('Role');
    expect(role).toHaveValue('SPOUSE');
    expect(screen.getByRole('option', { name: 'Spouse' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Parent' })).not.toBeInTheDocument();
  });

  /** The first covered role, not CHILD: the refusal this replaces was about a choice nobody made. */
  it('adds a dependant in a role the product covers', async () => {
    render(<Host terms={terms(['MAIN_MEMBER', 'PARENT'], ['MAIN_MEMBER', 'PARENT'])} planCode="A" />);

    await userEvent.click(screen.getByRole('button', { name: /Add dependant/ }));

    expect(screen.getByLabelText('Role')).toHaveValue('PARENT');
  });
});
