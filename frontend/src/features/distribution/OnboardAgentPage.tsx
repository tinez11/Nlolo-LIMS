import { ArrowLeft } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { AgentPicker } from '@/components/AgentPicker';
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
import { Input } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';

/**
 * `POST /agents`.
 *
 * CORRECTION: this javadoc used to say "there is no `GET /agents` list or search endpoint
 * anywhere on this platform". That stopped being true in M13, which added the paged list the
 * Agents table and this page's own supervisor picker both read.
 *
 * <p><b>What this endpoint does NOT do is create an identity.</b> It writes an
 * `agent_profile` -- a payee, a licence and a place in the hierarchy -- against a party that
 * already exists. The agents-realm login is a separate, manual step, and the form says so
 * rather than leaving the operator to discover it when the agent cannot sign in.
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
          <Input
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

        <FormField label="Reports to (optional)" error={errors.hierarchyParentId?.message}>
          {/*
            An agent picker, not a uuid box, for the same reason the agent of record got one:
            nobody knows a supervisor's uuid, and the hierarchy decides who earns OVERRIDE and
            SUPERVISOR_OVERRIDE commission on this agent's sales. A wrong parent here misroutes
            somebody else's money.
          */}
          <Controller
            control={control}
            name="hierarchyParentId"
            render={({ field }) => (
              <AgentPicker
                value={field.value || null}
                onChange={(agentId) => field.onChange(agentId ?? '')}
                placeholder="Search for their supervisor by name…"
              />
            )}
          />
          <p className="mt-1 text-[11px] text-subtle-foreground">
            Their supervisor, who earns override commission on this agent's business. Leave it
            empty for an agent at the top of the hierarchy.
          </p>
        </FormField>

        {/*
          THE SECOND STEP, SAID OUT LOUD.

          Onboarding creates the COMMISSION record and nothing else. It does not create a
          Keycloak login: this backend makes no Admin API calls anywhere, and the `party_id`
          claim that ties a signed-in agent to this profile is written by an administrator, not
          by this form. Until that happens the agent cannot sign in, cannot register clients,
          and therefore can never be bound as an introducing agent on their own business.

          Said here because the form otherwise looks complete. Of the agent profiles in this
          platform's own dev data, only two have a login at all -- and nothing on screen has
          ever indicated that the rest are, from the agent's point of view, not yet onboarded.
        */}
        <div className="rounded-md border border-border bg-surface-muted px-3 py-2 text-xs">
          <p className="font-medium">This is step one of two</p>
          <p className="mt-1 text-subtle-foreground">
            Onboarding creates the agent's commission record. It does not create their login —
            an administrator must separately create their user in the <code>agents</code> realm
            and set its <code>party_id</code> to the party above. Until that is done they cannot
            sign in or register clients, and business they introduce cannot be attributed to
            them automatically.
          </p>
        </div>

        {onboarding.status === 'error' && onboarding.error && (
          <InlineError error={onboarding.error} />
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
