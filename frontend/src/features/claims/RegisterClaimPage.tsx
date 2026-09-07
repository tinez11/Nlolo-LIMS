import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { CLAIM_TYPES } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { claimGates } from '@/gates/claimGates';
import { ClaimPolicyChooser } from './ClaimPolicyChooser';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { POLICY_NUMBER_PATTERN } from '@/lib/patterns';
import { useClaimStore } from '@/store/claimStore';
import { selectCoverage, selectDetail, usePolicyStore } from '@/store/policyStore';
import {
  blankDetailsFor,
  registerClaimFormSchema,
  toApiRequest,
  type ClaimDetailsFormValues,
  type RegisterClaimFormValues,
} from './claimRegisterForm';
import { Input, Select } from '@/components/ui/input';

/**
 * `POST /claims` is one of only six endpoints on the platform that HARD-REQUIRES
 * `Idempotency-Key` -- a submit without it is simply broken. The key is minted
 * ONCE for the lifetime of this page (useState's lazy initializer, not per
 * submit), so a second click after a network timeout retries the SAME attempt
 * rather than risking a duplicate registration. A fresh page visit -- a genuinely
 * new intent -- gets a fresh key naturally, since that remounts the component.
 */
export function RegisterClaimPage() {
  const navigate = useNavigate();
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  const registerClaim = useClaimStore((s) => s.registerClaim);
  const resetRegisterClaim = useClaimStore((s) => s.resetRegisterClaim);
  const registering = useClaimStore((s) => s.registering);

  // `registering` is a single, non-keyed slot in the store (a registration
  // creates a NEW claim, so there is no existing id to key by) -- which means a
  // stale error from a PREVIOUS visit to this page would otherwise resurface the
  // instant this one mounts. Built in from the start this time, having already
  // found the same bug class the hard way on beneficiaries.
  useEffect(() => {
    resetRegisterClaim();
    // Deliberately run-once: resetRegisterClaim's identity is stable (a Zustand
    // action), and re-running this on every render would clear a genuine
    // in-flight or errored state the user is actively looking at.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    control,
    formState: { errors },
  } = useForm<RegisterClaimFormValues>({
    resolver: zodResolver(registerClaimFormSchema),
    defaultValues: { policyNumber: '', claimantPartyId: '', dateOfEvent: '', details: blankDetailsFor('DEATH') },
  });

  // react-hook-form's watch() returns a live-subscribed value the React Compiler
  // cannot safely memoize -- see BeneficiariesPanel.tsx for the same, inherent
  // interaction. Correct as written; re-renders exactly when the field changes.
  // eslint-disable-next-line react-hooks/incompatible-library
  const claimType = watch('details.claimType');
  const policyNumber = watch('policyNumber');
  const claimantPartyId = watch('claimantPartyId');
  const dateOfEvent = watch('dateOfEvent');

  /**
   * The escape hatch, and why it is not just belt-and-braces: `ClaimsApiImpl.registerClaim`
   * enforces NO relationship between the claimant and the policy -- only that both exist and
   * the policy is in force. An executor, an assignee or a cessionary is a legitimate claimant
   * with no recorded connection, so a chooser that could ONLY offer connected policies would
   * make this console stricter than the platform it is a console for.
   */
  const [manualPolicyEntry, setManualPolicyEntry] = useState(false);
  const [policyFilter, setPolicyFilter] = useState('');

  /**
   * What the platform can honestly say about cover -- see `claimGates`, which
   * documents why "as at the date of event" is NOT among it: coverage-status
   * accepts `asOf`, echoes it, and ignores it.
   *
   * The policy carries the only real date (`issueDate`) and its current status;
   * coverage carries the benefit set. Both are fetched as soon as the policy
   * number is well-formed, so the checks appear as the date is entered.
   */
  const loadDetail = usePolicyStore((s) => s.loadDetail);
  const loadCoverage = usePolicyStore((s) => s.loadCoverage);
  const policy = usePolicyStore(selectDetail(policyNumber));
  const coverage = usePolicyStore(selectCoverage(policyNumber));

  useEffect(() => {
    // Guarded on the real pattern, not on non-emptiness: without it, every
    // keystroke of a policy number fires a request certain to 404.
    if (!POLICY_NUMBER_PATTERN.test(policyNumber)) return;
    void loadDetail(policyNumber);
    void loadCoverage(policyNumber);
  }, [policyNumber, loadDetail, loadCoverage]);

  // react-hook-form's FieldErrors type does not narrow per-branch on a
  // discriminated union field the way the VALUE type does -- `errors.details` is
  // typed as a merge across all 4 variants' possible error shapes, so TypeScript
  // cannot statically confirm `errors.details.causeOfDeath` exists even when
  // claimType is genuinely 'DEATH'. At runtime RHF only ever populates errors for
  // fields the current value shape actually has, so this narrows the read
  // without touching the JSX call sites with an `any` each.
  function detailError(field: string): string | undefined {
    const details = errors.details as Record<string, { message?: string } | undefined> | undefined;
    return details?.[field]?.message;
  }

  async function onSubmit(values: RegisterClaimFormValues) {
    await registerClaim(toApiRequest(values), attempt);
    // Re-read fresh: `registering` above is the value from the render that
    // triggered this submit, not necessarily what `track()` just settled to.
    const result = useClaimStore.getState().registering;
    if (result.status === 'success' && result.data) {
      navigate(`../${result.data.claimId}`, { relative: 'path' });
    }
  }

  return (
    <>
      <div className="px-6 pt-6">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link to=".." relative="path">
            <ArrowLeft />
            All claims
          </Link>
        </Button>
      </div>

      <PageHeader title="Register a claim" description="Replaces nothing -- this always creates a new claim." />

      <form
        className="max-w-xl space-y-4 px-6 pb-8"
        onSubmit={(e) => void handleSubmit(onSubmit)(e)}
      >
        {/* Claimant BEFORE policy, which is the order the conversation actually happens in:
            somebody arrives and says who they are, not which contract number they hold. It is
            also the only order in which the policy field can be a list rather than a guess. */}
        <FormField label="Claimant" error={errors.claimantPartyId?.message}>
          <Controller
            control={control}
            name="claimantPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => {
                  const next = partyId ?? '';
                  /*
                   * Clear the policy only when the claimant genuinely CHANGES from one person
                   * to a different one -- not when it goes from empty to somebody.
                   *
                   * The distinction is the whole rule. A corrected claimant must not keep the
                   * previous claimant's policy: the form would look complete and file the
                   * claim against the wrong contract. But a clerk who typed the policy number
                   * first, off a paper form, and only then identified the claimant has done
                   * nothing wrong, and wiping their input -- and kicking them out of manual
                   * entry -- is destroying work, not preventing a mistake.
                   */
                  if (field.value && next !== field.value) {
                    setValue('policyNumber', '');
                    setPolicyFilter('');
                  }
                  field.onChange(next);
                }}
                placeholder="Search for the claimant by name"
              />
            )}
          />
        </FormField>

        {/* No `hint` prop here: FormField does not have one. An earlier version passed it
            through a JSX spread, which bypasses TypeScript's excess-property check entirely --
            it typechecked, rendered nothing, and would have gone unnoticed but for looking at
            the screen. The scope caption lives inside the chooser instead, which is what it
            describes. */}
        <FormField label="Policy" error={errors.policyNumber?.message}>
          {manualPolicyEntry ? (
            <div className="space-y-1.5">
              <Input className="font-mono" placeholder="POL-XXXXXXXX" {...register('policyNumber')} />
              {claimantPartyId && (
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  className="-ml-2"
                  onClick={() => setManualPolicyEntry(false)}
                >
                  Back to this client&apos;s policies
                </Button>
              )}
            </div>
          ) : (
            <Controller
              control={control}
              name="policyNumber"
              render={({ field }) => (
                <ClaimPolicyChooser
                  claimantPartyId={claimantPartyId}
                  value={field.value}
                  onChange={field.onChange}
                  filter={policyFilter}
                  onFilterChange={setPolicyFilter}
                  onEnterManually={() => {
                    setManualPolicyEntry(true);
                    // Cleared on the way in: carrying a chosen number into the free-text box
                    // invites editing one digit of a real policy number into a 404.
                    field.onChange('');
                  }}
                />
              )}
            />
          )}
        </FormField>

        <FormField label="Date of event" error={errors.dateOfEvent?.message}>
          <Controller
            control={control}
            name="dateOfEvent"
            render={({ field }) => (
              <DatePicker
                value={field.value || null}
                onChange={(iso) => field.onChange(iso ?? '')}
                disabled={{ after: new Date() }}
              />
            )}
          />
        </FormField>

        {/* Sits directly under the date that drives it rather than in a side
            rail: the answer belongs next to the question. Renders nothing until
            there is a date to ask about. The title deliberately avoids the words
            "date of event" -- `getByLabel` matches by substring, so a container
            name containing a field label makes that field ambiguous. */}
        <GatePanel
          title="Coverage checks"
          gates={claimGates({
            policy: policy.data ?? null,
            coverage: coverage.data ?? null,
            claimType: claimType ?? null,
            dateOfEvent: dateOfEvent || null,
          })}
        />

        <FormField label="Claim type">
          <Select
            value={claimType}
            onChange={(e) =>
              setValue('details', blankDetailsFor(e.target.value as ClaimDetailsFormValues['claimType']))
            }
          >
            {CLAIM_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </Select>
        </FormField>

        <div className="rounded-md border border-border p-3">
          {claimType === 'DEATH' && (
            <div className="space-y-3">
              <FormField label="Cause of death" error={detailError('causeOfDeath')}>
                <Input  {...register('details.causeOfDeath')} />
              </FormField>
              <FormField label="Place of death" error={detailError('placeOfDeath')}>
                <Input  {...register('details.placeOfDeath')} />
              </FormField>
              <FormField label="Date of death" error={detailError('dateOfDeath')}>
                <Controller
                  control={control}
                  name="details.dateOfDeath"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
              <FormField label="Attending physician" error={detailError('attendingPhysician')}>
                <Input  {...register('details.attendingPhysician')} />
              </FormField>
            </div>
          )}

          {claimType === 'DISABILITY' && (
            <div className="space-y-3">
              <FormField label="Disability type" error={detailError('disabilityType')}>
                <Input  {...register('details.disabilityType')} />
              </FormField>
              <FormField label="Onset date" error={detailError('onsetDate')}>
                <Controller
                  control={control}
                  name="details.onsetDate"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
              <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <input type="checkbox" {...register('details.permanent')} />
                Permanent
              </label>
              <FormField label="Impairment percent" error={detailError('impairmentPercent')}>
                <div className="flex items-center gap-1">
                  <Input
                    placeholder="62.50"
                    {...register('details.impairmentPercent')}
                  />
                  <span className="text-xs text-muted-foreground">%</span>
                </div>
              </FormField>
            </div>
          )}

          {claimType === 'CRITICAL_ILLNESS' && (
            <div className="space-y-3">
              <FormField label="Diagnosis" error={detailError('diagnosis')}>
                <Input  {...register('details.diagnosis')} />
              </FormField>
              <FormField label="Diagnosis date" error={detailError('diagnosisDate')}>
                <Controller
                  control={control}
                  name="details.diagnosisDate"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
              <FormField label="ICD code" error={detailError('icdCode')}>
                <Input className="font-mono" {...register('details.icdCode')} />
              </FormField>
            </div>
          )}

          {claimType === 'MATURITY' && (
            <FormField label="Maturity date" error={detailError('maturityDate')}>
              <Controller
                control={control}
                name="details.maturityDate"
                render={({ field }) => (
                  <DatePicker
                    value={(field.value as string) || null}
                    onChange={(iso) => field.onChange(iso ?? '')}
                  />
                )}
              />
            </FormField>
          )}
        </div>

        {/* A 422 here (e.g. "Policy POL-X was not in force on <date>") is a real,
            whole-request business rejection, not a per-field error -- same shape
            as beneficiaries' BeneficiaryValidationException. */}
        {registering.status === 'error' && registering.error && (
          <div role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
            {registering.error.detail ?? registering.error.title}
            {registering.error.traceId && (
              <span className="ml-2 font-mono text-[10px] opacity-80">({registering.error.traceId})</span>
            )}
          </div>
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" disabled={registering.status === 'loading'}>
            {registering.status === 'loading' ? 'Registering…' : 'Register claim'}
          </Button>
          <Button asChild variant="ghost">
            <Link to=".." relative="path">
              Cancel
            </Link>
          </Button>
        </div>
      </form>
    </>
  );
}
