import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { CLAIM_TYPES } from '@/api/types';
import { PageHeader } from '@/components/AppShell';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { useClaimStore } from '@/store/claimStore';
import {
  blankDetailsFor,
  registerClaimFormSchema,
  toApiRequest,
  type ClaimDetailsFormValues,
  type RegisterClaimFormValues,
} from './claimRegisterForm';

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
        <FormField label="Policy number" error={errors.policyNumber?.message}>
          <input
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 font-mono text-sm"
            placeholder="POL-XXXXXXXX"
            {...register('policyNumber')}
          />
        </FormField>

        <FormField label="Claimant party id" error={errors.claimantPartyId?.message}>
          <Controller
            control={control}
            name="claimantPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the claimant by name"
              />
            )}
          />
        </FormField>

        <FormField label="Date of event" error={errors.dateOfEvent?.message}>
          <Controller
            control={control}
            name="dateOfEvent"
            render={({ field }) => (
              <DatePicker
                value={field.value || null}
                onChange={(iso) => field.onChange(iso ?? '')}
                placeholder="Select the date of event"
                disabled={{ after: new Date() }}
              />
            )}
          />
        </FormField>

        <FormField label="Claim type">
          <select
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
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
          </select>
        </FormField>

        <div className="rounded-md border border-border p-3">
          {claimType === 'DEATH' && (
            <div className="space-y-3">
              <FormField label="Cause of death" error={detailError('causeOfDeath')}>
                <input className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm" {...register('details.causeOfDeath')} />
              </FormField>
              <FormField label="Place of death" error={detailError('placeOfDeath')}>
                <input className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm" {...register('details.placeOfDeath')} />
              </FormField>
              <FormField label="Date of death" error={detailError('dateOfDeath')}>
                <Controller
                  control={control}
                  name="details.dateOfDeath"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      placeholder="Select the date of death"
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
              <FormField label="Attending physician" error={detailError('attendingPhysician')}>
                <input className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm" {...register('details.attendingPhysician')} />
              </FormField>
            </div>
          )}

          {claimType === 'DISABILITY' && (
            <div className="space-y-3">
              <FormField label="Disability type" error={detailError('disabilityType')}>
                <input className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm" {...register('details.disabilityType')} />
              </FormField>
              <FormField label="Onset date" error={detailError('onsetDate')}>
                <Controller
                  control={control}
                  name="details.onsetDate"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      placeholder="Select the onset date"
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
                  <input
                    className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
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
                <input className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm" {...register('details.diagnosis')} />
              </FormField>
              <FormField label="Diagnosis date" error={detailError('diagnosisDate')}>
                <Controller
                  control={control}
                  name="details.diagnosisDate"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      placeholder="Select the diagnosis date"
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
              <FormField label="ICD code" error={detailError('icdCode')}>
                <input className="h-9 w-full rounded-md border border-input bg-surface px-2.5 font-mono text-sm" {...register('details.icdCode')} />
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
                    placeholder="Select the maturity date"
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

function FormField({
  label,
  error,
  children,
}: {
  label: string;
  // The explicit `| undefined` matters under exactOptionalPropertyTypes: every
  // call site passes `errors.x?.message`, which IS `string | undefined` -- a bare
  // `error?: string` would reject that assignment outright.
  error?: string | undefined;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-muted-foreground">{label}</span>
      {children}
      {error && <p className="mt-1 text-[11px] text-status-danger-fg">{error}</p>}
    </label>
  );
}
