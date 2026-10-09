import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import { amendCorporate, amendIndividual, getParty } from '@/api/party';
import type { PartyDetailView } from '@/api/types';
import type { ApiError } from '@/lib/apiError';
import { IndividualFields } from './IndividualFields';
import {
  editIndividualForm,
  registerIndividualFormSchema,
  toApiRequest,
  type RegisterIndividualFormValues,
} from './registerIndividualForm';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/**
 * Correct what the platform has recorded about a client.
 *
 * <p><b>KYC IS NOT TOUCHED, and the page says so rather than leaving it to be discovered.</b>
 * KYC here is what a passport is: a document verifying that this person is who they say they
 * are. Correcting an address does not un-verify that document, so a verified client is not sent
 * back to PENDING and made to prove themselves again over a misspelled street. Staff about to
 * edit a VERIFIED client would otherwise reasonably hesitate, and hesitating means the record
 * stays wrong.
 *
 * <p>A page rather than an inline editor on the detail screen. The detail page is a pinned
 * record rail read ALONGSIDE evidence — a claim, an underwriting case — and turning the thing
 * being judged into a set of inputs while it is being judged against is how a reader stops
 * being able to tell what the record said when the decision was made.
 *
 * <p>Every field is sent on save, so clearing one clears it. That is what "correct the record"
 * means, and the alternative — treating an emptied field as "leave alone" — would make removing
 * a wrongly-recorded employer impossible through the only screen that edits a client.
 */
export function EditClientPage() {
  const { partyId = '' } = useParams();
  const navigate = useNavigate();

  const [party, setParty] = useState<PartyDetailView | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [saveError, setSaveError] = useState<ApiError | null>(null);
  const [saving, setSaving] = useState(false);

  const {
    register,
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RegisterIndividualFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(registerIndividualFormSchema),
  });

  const [corporateName, setCorporateName] = useState('');
  const [corporatePhone, setCorporatePhone] = useState('');
  const [corporateEmail, setCorporateEmail] = useState('');

  useEffect(() => {
    let cancelled = false;
    getParty(partyId)
      .then((loaded) => {
        if (cancelled) return;
        setParty(loaded);
        if (loaded.partyType === 'INDIVIDUAL') {
          reset(editIndividualForm(loaded));
        } else {
          setCorporateName(loaded.displayName ?? '');
          setCorporatePhone(loaded.phoneNumber ?? '');
          setCorporateEmail(loaded.email ?? '');
        }
      })
      .catch((e: ApiError) => {
        if (!cancelled) setLoadError(e);
      });
    return () => {
      cancelled = true;
    };
  }, [partyId, reset]);

  const backToClient = `/staff/parties/${partyId}`;

  async function saveIndividual(values: RegisterIndividualFormValues) {
    setSaving(true);
    setSaveError(null);
    try {
      await amendIndividual(partyId, toApiRequest(values));
      void navigate(backToClient);
    } catch (e) {
      setSaveError(e as ApiError);
    } finally {
      setSaving(false);
    }
  }

  async function saveCorporate(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaveError(null);
    try {
      await amendCorporate(partyId, {
        registeredName: corporateName.trim(),
        contactInfo: {
          ...(corporatePhone.trim() && { phoneNumber: corporatePhone.trim() }),
          ...(corporateEmail.trim() && { email: corporateEmail.trim() }),
        },
      });
      void navigate(backToClient);
    } catch (err) {
      setSaveError(err as ApiError);
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <div className="px-6 pt-4">
        <Link className="text-xs text-muted-foreground hover:underline" to={backToClient}>
          Back to client
        </Link>
      </div>

      <PageHeader
        title={party ? `Correct ${party.displayName}` : 'Correct a client'}
        description="Amending what is on record. This does not change their KYC — that is a decision about a document, and correcting the record does not un-verify it."
      />

      {loadError && (
        <InlineError error={loadError} />
      )}

      {party?.partyType === 'INDIVIDUAL' && (
        <form
          className="max-w-2xl space-y-4 px-6 pb-8"
          onSubmit={(e) => void handleSubmit(saveIndividual)(e)}
        >
          <IndividualFields register={register} control={control} errors={errors} />

          {saveError && (
            <InlineError error={saveError} />
          )}

          <Button type="submit" variant="primary" disabled={saving}>
            {saving ? 'Saving…' : 'Save corrections'}
          </Button>
        </form>
      )}

      {party && party.partyType !== 'INDIVIDUAL' && (
        <form className="max-w-2xl space-y-4 px-6 pb-8" onSubmit={(e) => void saveCorporate(e)}>
          <FormField label="Registered name">
            <Input value={corporateName} onChange={(e) => setCorporateName(e.target.value)} />
          </FormField>

          <FormField label="Phone number (optional)">
            <Input
              placeholder="+255712345678"
              value={corporatePhone}
              onChange={(e) => setCorporatePhone(e.target.value)}
            />
          </FormField>

          <FormField label="Email (optional)">
            <Input value={corporateEmail} onChange={(e) => setCorporateEmail(e.target.value)} />
          </FormField>

          {/*
            NO REGISTRATION NUMBER, and its absence is deliberate rather than an oversight. A
            company's registration number is its identity in the national register — what the
            duplicate check runs on, and what a policy was underwritten against. Correcting a
            phone number must not be a route to quietly becoming a different company.
          */}
          <p className="text-xs text-muted-foreground">
            Registration number is not editable here — it is this company&apos;s identity in the
            national register, not a detail about it.
          </p>

          {saveError && (
            <InlineError error={saveError} />
          )}

          <Button type="submit" variant="primary" disabled={saving}>
            {saving ? 'Saving…' : 'Save corrections'}
          </Button>
        </form>
      )}
    </>
  );
}
