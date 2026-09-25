import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { PageHeader } from '@/components/PageHeader';

import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { IndividualFields } from './IndividualFields';
import { cn } from '@/lib/cn';
import {
  selectRegisteringCorporate,
  selectRegisteringIndividual,
  usePartyStore,
} from '@/store/partyStore';
import {
  blankRegisterCorporateForm,
  registerCorporateFormSchema,
  toApiRequest as toCorporateApiRequest,
  type RegisterCorporateFormValues,
} from './registerCorporateForm';
import {
  blankRegisterIndividualForm,
  registerIndividualFormSchema,
  toApiRequest as toIndividualApiRequest,
  type RegisterIndividualFormValues,
} from './registerIndividualForm';
import { Input } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';

type CustomerType = 'INDIVIDUAL' | 'CORPORATE';

/**
 * `POST /parties/individuals` / `POST /parties/corporates` -- both were
 * already agent-scoped server-side before any frontend called either. There
 * is nowhere to navigate on success: no `GET /parties` list exists, and this
 * realm has no party-detail route (that stays a staff-only drill-in screen),
 * so a registered customer's id is shown right here rather than implying a
 * page it could be revisited from.
 */
export function OnboardCustomerPage() {
  const [type, setType] = useState<CustomerType>('INDIVIDUAL');

  return (
    <>
      <PageHeader
        title="Onboard a customer"
        description="Registers a new individual or corporate party, PENDING KYC verification by staff."
      />

      <div className="max-w-xl px-6 pb-8">
        <div className="mb-4 inline-flex rounded-md border border-border p-0.5">
          <TypeTab label="Individual" active={type === 'INDIVIDUAL'} onClick={() => setType('INDIVIDUAL')} />
          <TypeTab label="Corporate" active={type === 'CORPORATE'} onClick={() => setType('CORPORATE')} />
        </div>

        {type === 'INDIVIDUAL' ? <RegisterIndividualForm key="individual" /> : <RegisterCorporateForm key="corporate" />}
      </div>
    </>
  );
}

function TypeTab({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      className={cn(
        'rounded px-3 py-1.5 text-sm font-medium transition-colors',
        active ? 'bg-selected text-foreground' : 'text-muted-foreground hover:text-foreground',
      )}
      onClick={onClick}
    >
      {label}
    </button>
  );
}

function RegisterIndividualForm() {
  const registerIndividual = usePartyStore((s) => s.registerIndividual);
  const resetRegisterIndividual = usePartyStore((s) => s.resetRegisterIndividual);
  const registering = usePartyStore(selectRegisteringIndividual);

  useEffect(() => {
    resetRegisterIndividual();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RegisterIndividualFormValues>({
    resolver: zodResolver(registerIndividualFormSchema),
    defaultValues: blankRegisterIndividualForm(),
  });

  async function onSubmit(values: RegisterIndividualFormValues) {
    await registerIndividual(toIndividualApiRequest(values));
  }

  if (registering.status === 'success' && registering.data) {
    return (
      <RegisteredResult
        party={registering.data}
        onRegisterAnother={() => {
          resetRegisterIndividual();
          reset(blankRegisterIndividualForm());
        }}
      />
    );
  }

  return (
    <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      <IndividualFields register={register} control={control} errors={errors} />

      {registering.status === 'error' && registering.error && (
        <InlineError error={registering.error} />
      )}

      <Button type="submit" variant="primary" disabled={registering.status === 'loading'}>
        {registering.status === 'loading' ? 'Registering…' : 'Register individual'}
      </Button>
    </form>
  );
}

function RegisterCorporateForm() {
  const registerCorporate = usePartyStore((s) => s.registerCorporate);
  const resetRegisterCorporate = usePartyStore((s) => s.resetRegisterCorporate);
  const registering = usePartyStore(selectRegisteringCorporate);

  useEffect(() => {
    resetRegisterCorporate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RegisterCorporateFormValues>({
    resolver: zodResolver(registerCorporateFormSchema),
    defaultValues: blankRegisterCorporateForm(),
  });

  async function onSubmit(values: RegisterCorporateFormValues) {
    await registerCorporate(toCorporateApiRequest(values));
  }

  if (registering.status === 'success' && registering.data) {
    return (
      <RegisteredResult
        party={registering.data}
        onRegisterAnother={() => {
          resetRegisterCorporate();
          reset(blankRegisterCorporateForm());
        }}
      />
    );
  }

  return (
    <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      <FormField label="Registered name" error={errors.registeredName?.message}>
        <Input
          placeholder="Kilimanjaro SACCO"
          {...register('registeredName')}
        />
      </FormField>

      <FormField label="Registration number" error={errors.registrationNumber?.message}>
        <Input
          placeholder="REG-0001"
          {...register('registrationNumber')}
        />
      </FormField>

      <FormField label="Phone number (optional)" error={errors.phoneNumber?.message}>
        <Input
          placeholder="+255712345678"
          {...register('phoneNumber')}
        />
      </FormField>

      <FormField label="Email (optional)" error={errors.email?.message}>
        <Input
          placeholder="finance@kilimanjaro-sacco.tz"
          {...register('email')}
        />
      </FormField>

      {registering.status === 'error' && registering.error && (
        <InlineError error={registering.error} />
      )}

      <Button type="submit" variant="primary" disabled={registering.status === 'loading'}>
        {registering.status === 'loading' ? 'Registering…' : 'Register corporate'}
      </Button>
    </form>
  );
}

function RegisteredResult({
  party,
  onRegisterAnother,
}: {
  party: { partyId?: string; kycStatus?: string };
  onRegisterAnother: () => void;
}) {
  return (
    <div className="rounded-lg border border-border bg-surface p-4">
      <p className="text-sm font-medium">Registered</p>
      <dl className="mt-2">
        <Field label="Party id" value={<span className="font-mono text-xs">{party.partyId}</span>} />
        <Field
          label="KYC status"
          value={party.kycStatus ? <StatusBadge kind="kyc" value={party.kycStatus} /> : '—'}
          note="Registration and verification are separate steps -- a new party always starts PENDING."
        />
      </dl>
      <Button size="sm" variant="outline" className="mt-3" onClick={onRegisterAnother}>
        Register another
      </Button>
    </div>
  );
}
