import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { discardAgentQuotation, getAgentQuotation, type AgentQuotation } from '@/api/agentQuotations';
import { LoadingBlock } from '@/components/states';
import { AcceptedQuotationPanel, QuotationUnavailable } from '@/features/underwriting/AcceptedQuotationPanel';
import { toApiError, type ApiError } from '@/lib/apiError';
import { useProductStore } from '@/store/productStore';
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
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { UnsavedGuard } from '@/components/UnsavedGuard';

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
  // An agent arriving from a confirmed quotation (2026-10-20): the person is registered from it, then proposed.
  const [params] = useSearchParams();
  const quotationId = params.get('quotation');

  return (
    <>
      <PageHeader
        title="Onboard a customer"
        description="Registers a new individual or corporate party, PENDING KYC verification by staff."
      />

      <div className="max-w-xl px-6 pb-8">
        {quotationId ? (
          <FromQuotation key={quotationId} quotationId={quotationId} />
        ) : (
          <>
            <div className="mb-4 inline-flex rounded-md border border-border p-0.5">
              <TypeTab label="Individual" active={type === 'INDIVIDUAL'} onClick={() => setType('INDIVIDUAL')} />
              <TypeTab label="Corporate" active={type === 'CORPORATE'} onClick={() => setType('CORPORATE')} />
            </div>

            {type === 'INDIVIDUAL' ? <RegisterIndividualForm key="individual" /> : <RegisterCorporateForm key="corporate" />}
          </>
        )}
      </div>
    </>
  );
}

/**
 * Registering the customer a confirmed quotation was for: the form starts from the details as quoted, so nothing is
 * typed twice, and the next step is the proposal rather than "Register another". Cancel discards the quotation --
 * the customer is not going ahead -- and returns to the calculator.
 */
function FromQuotation({ quotationId }: { quotationId: string }) {
  const navigate = useNavigate();
  const [quotation, setQuotation] = useState<AgentQuotation | null>(null);
  // Two different failures: the quotation could not be read (nothing to onboard from), or the discard failed (the
  // form and what was typed into it must stay). One shared slot used to replace the whole form on a failed discard.
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [discardError, setDiscardError] = useState<ApiError | null>(null);
  const [discarding, setDiscarding] = useState(false);
  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);

  useEffect(() => {
    let live = true;
    void loadProducts();
    getAgentQuotation(quotationId).then(
      (q) => { if (live) setQuotation(q); },
      (e: unknown) => { if (live) setLoadError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [quotationId, loadProducts]);

  async function discard() {
    setDiscarding(true);
    setDiscardError(null);
    try {
      await discardAgentQuotation(quotationId);
      // Path-relative from customers/new: two segments up is the realm.
      navigate('../../quotation', { relative: 'path' });
    } catch (e) {
      const error = toApiError(e);
      // Already gone -- swept, or discarded in another tab: what the agent wanted is true, so go back regardless.
      if (error.status === 404) {
        navigate('../../quotation', { relative: 'path' });
        return;
      }
      setDiscardError(error);
      setDiscarding(false);
    }
  }

  if (loadError) return <QuotationUnavailable error={loadError} />;
  if (!quotation) return <LoadingBlock label="Loading the quotation…" />;
  // Submitted already (a reload after the proposal went in): there is nothing left to onboard from it.
  if (quotation.caseId) {
    return (
      <div role="status" className="space-y-2 rounded-md border border-border p-3 text-xs">
        <p className="font-medium">This quotation was already submitted as proposal {quotation.proposalNumber}.</p>
        <Button asChild size="sm" variant="outline">
          <Link to={`/agents/proposals/${quotation.caseId}`}>Open the proposal</Link>
        </Button>
      </div>
    );
  }

  const p = quotation.inputs.prospect;
  const productName = (products.data ?? []).find((x) => x.productId === quotation.productId)?.productName;
  return (
    <div className="space-y-4">
      <AcceptedQuotationPanel quotation={quotation} productName={productName} />
      <RegisterIndividualForm
        initial={{
          ...blankRegisterIndividualForm(),
          fullName: p?.fullName ?? '',
          dateOfBirth: p?.dateOfBirth ?? '',
          phoneNumber: p?.phoneNumber ?? '',
          sex: p?.sex ?? '',
          smokerStatus: p?.smokerStatus ?? '',
          occupationClass: p?.occupationClass ?? '',
        }}
        onRegistered={(partyId) =>
          navigate(`../../quotation/${quotationId}?party=${partyId}`, { relative: 'path' })
        }
        guardWhat="This customer and their accepted quotation"
        leaving={discarding}
        extraAction={
          <Button type="button" variant="ghost" pending={discarding} onClick={() => void discard()}>
            Cancel and discard the quotation
          </Button>
        }
      />
      {discardError && <InlineError error={discardError} lead="The quotation could not be discarded:" />}
    </div>
  );
}

/** The two-way switch at the top of a form. Shared with the agents' quotation calculator (2026-10-20). */
export function TypeTab({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
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

function RegisterIndividualForm({
  initial,
  onRegistered,
  guardWhat = 'This customer',
  extraAction,
  leaving = false,
}: {
  /** The page is being left on purpose (the quotation discarded): no "leave this page?" in the way. */
  leaving?: boolean | undefined;
  /** Where the form starts: blank, or the details a confirmed quotation carried. */
  initial?: RegisterIndividualFormValues | undefined;
  /** What follows registering, instead of the registered result -- the proposal, when there is a quotation. */
  onRegistered?: ((partyId: string) => void) | undefined;
  guardWhat?: string | undefined;
  extraAction?: ReactNode;
} = {}) {
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
    formState: { errors, isDirty, isSubmitting },
  } = useForm<RegisterIndividualFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(registerIndividualFormSchema),
    defaultValues: initial ?? blankRegisterIndividualForm(),
  });

  async function onSubmit(values: RegisterIndividualFormValues) {
    await registerIndividual(toIndividualApiRequest(values));
    const result = usePartyStore.getState().registeringIndividual;
    if (onRegistered && result.status === 'success' && result.data?.partyId) {
      onRegistered(result.data.partyId);
    }
  }

  if (!onRegistered && registering.status === 'success' && registering.data) {
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
      <UnsavedGuard
        when={(isDirty || initial !== undefined) && !isSubmitting && !leaving && registering.status !== 'success'}
        what={guardWhat}
      />
      <IndividualFields register={register} control={control} errors={errors} />

      {registering.status === 'error' && registering.error && (
        <InlineError error={registering.error} />
      )}

      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" variant="primary" pending={registering.status === 'loading'}>
          Register individual
        </Button>
        {extraAction}
      </div>
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
    formState: { errors, isDirty, isSubmitting },
  } = useForm<RegisterCorporateFormValues>({
    ...VALIDATE_ON_TOUCH,
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
      <UnsavedGuard when={isDirty && !isSubmitting} what="This customer" />
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

      <Button type="submit" variant="primary" pending={registering.status === 'loading'}>
        Register corporate
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
          note="Registration and verification are separate steps — a new party always starts PENDING."
        />
      </dl>
      {/*
        THE HAND-OFF, added 2026-10-14 because it was missing and the gap read as a missing
        feature. An agent registering a client is holding that client's ID scan at this exact
        moment, and the only control here was "Register another" -- so the place to send the
        documents (the client record's KYC tab) had to be found by going back to My clients,
        searching for the person just registered, opening them, and reading along a tab strip.
        The note above says verification is a separate step; this is that step.

        Route-relative `../parties/{id}`, the idiom ClientsPage's row activation records: every
        screen is a flat child of the realm route, so `..` is the realm regardless of how many
        segments this screen's own path has. Works unchanged in both realms -- the agent lands
        on the filing panel, a staff member on the decision panel, which can also upload.
      */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {party.partyId && (
          <Button size="sm" variant="primary" asChild>
            <Link to={`../parties/${party.partyId}?tab=kyc`}>Send identity documents</Link>
          </Button>
        )}
        <Button size="sm" variant="outline" onClick={onRegisterAnother}>
          Register another
        </Button>
      </div>
    </div>
  );
}
