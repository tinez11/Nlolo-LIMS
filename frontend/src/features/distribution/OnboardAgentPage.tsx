import { ArrowLeft } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/AppShell';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { useDistributionStore } from '@/store/distributionStore';
import {
  blankOnboardAgentForm,
  onboardAgentFormSchema,
  toApiRequest,
  type OnboardAgentFormValues,
} from './onboardAgentForm';

/**
 * `POST /agents` -- the only entry point onto this domain that exists
 * server-side, same shape as underwriting's own case-opening page: there is
 * no `GET /agents` list or search endpoint anywhere on this platform. Unlike
 * underwriting, though, an agent IS re-discoverable afterward through a
 * policy's own `agentOfRecordId` (confirmed on the real wire DTO -- unlike
 * underwriting's id, this one is not dropped), so this page's own success
 * response is not the only way back to a given agent, just the first one.
 */
export function OnboardAgentPage() {
  const navigate = useNavigate();
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  const onboardAgent = useDistributionStore((s) => s.onboardAgent);
  const resetOnboardAgent = useDistributionStore((s) => s.resetOnboardAgent);
  const onboarding = useDistributionStore((s) => s.onboarding);

  useEffect(() => {
    resetOnboardAgent();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<OnboardAgentFormValues>({
    resolver: zodResolver(onboardAgentFormSchema),
    defaultValues: blankOnboardAgentForm(),
  });

  async function onSubmit(values: OnboardAgentFormValues) {
    await onboardAgent(toApiRequest(values), attempt);
    const result = useDistributionStore.getState().onboarding;
    if (result.status === 'success' && result.data?.agentId) {
      navigate(`../${result.data.agentId}`, { relative: 'path' });
    }
  }

  return (
    <>
      <div className="px-6 pt-6">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link to=".." relative="path">
            <ArrowLeft />
            Back
          </Link>
        </Button>
      </div>

      <PageHeader
        title="Onboard an agent"
        description="Staff/finance only. The party must already have KYC status VERIFIED."
      />

      <form className="max-w-xl space-y-4 px-6 pb-8" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
        <FormField label="Party id" error={errors.partyId?.message}>
          <Controller
            control={control}
            name="partyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                kycStatus="VERIFIED"
                placeholder="Search for a VERIFIED party by name"
              />
            )}
          />
        </FormField>

        <FormField label="License number" error={errors.licenseNumber?.message}>
          <input
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
            placeholder="LIC-0001"
            {...register('licenseNumber')}
          />
        </FormField>

        <FormField label="License expiry date" error={errors.licenseExpiryDate?.message}>
          <Controller
            control={control}
            name="licenseExpiryDate"
            render={({ field }) => (
              <DatePicker
                value={field.value || null}
                onChange={(iso) => field.onChange(iso ?? '')}
                disabled={{ before: new Date() }}
              />
            )}
          />
        </FormField>

        <FormField label="Hierarchy parent id (optional)" error={errors.hierarchyParentId?.message}>
          <input
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 font-mono text-sm"
            placeholder="uuid, or leave blank for the top of the hierarchy"
            {...register('hierarchyParentId')}
          />
        </FormField>

        {onboarding.status === 'error' && onboarding.error && (
          <div role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
            {onboarding.error.detail ?? onboarding.error.title}
            {onboarding.error.traceId && (
              <span className="ml-2 font-mono text-[10px] opacity-80">({onboarding.error.traceId})</span>
            )}
          </div>
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" disabled={onboarding.status === 'loading'}>
            {onboarding.status === 'loading' ? 'Onboarding…' : 'Onboard agent'}
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
