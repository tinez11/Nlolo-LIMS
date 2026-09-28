import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { CLAIM_TYPES, type PolicyMemberView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { DatePicker } from '@/components/DatePicker';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { claimGates } from '@/gates/claimGates';
import { ClaimPolicyChooser } from './ClaimPolicyChooser';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { humanizeStatus } from '@/lib/status';
import { POLICY_NUMBER_PATTERN } from '@/lib/patterns';
import { useClaimStore } from '@/store/claimStore';
import { selectCoverage, selectDetail, selectMembers, usePolicyStore } from '@/store/policyStore';
import {
  blankDetailsFor,
  registerClaimFormSchema,
  toApiRequest,
  type ClaimDetailsFormValues,
  type RegisterClaimFormValues,
} from './claimRegisterForm';
import { PartyName } from '@/components/PartyName';
import { Input, Select } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import { CheckboxField } from '@/components/ui/checkbox';

/**
 * `POST /claims` is one of only six endpoints on the platform that HARD-REQUIRES
 * `Idempotency-Key` -- a submit without it is simply broken. The key is minted
 * ONCE for the lifetime of this page (useState's lazy initializer, not per
 * submit), so a second click after a network timeout retries the SAME attempt
 * rather than risking a duplicate registration. A fresh page visit -- a genuinely
 * new intent -- gets a fresh key naturally, since that remounts the component.
 */
/**
 * How one insured life reads in the "Who died" list.
 *
 * <p>A freeform borrower carries their own name; an employer-scheme member is a registered party
 * and is resolved through it. Neither is ever a bare uuid, which is what this showed before.
 */
function memberLabel(m: PolicyMemberView) {
  if (m.memberName) {
    return m.memberReference ? `${m.memberName} — ${m.memberReference}` : m.memberName;
  }
  return m.memberPartyId ? <PartyName partyId={m.memberPartyId} /> : m.policyMemberId;
}

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
    // The schema is a factory now, because whether a member is required depends on the policy
    // -- and the policy is not known until its number has been typed and fetched, which is
    // after this hook runs. The ref is written in an effect below and read at validation time,
    // by which point the category has arrived.
    resolver: (values, context, options) =>
      zodResolver(
        registerClaimFormSchema({ productCategory: productCategoryRef.current }),
      )(values, context, options),
    defaultValues: {
      policyNumber: '',
      policyMemberId: '',
      claimantPartyId: '',
      dateOfEvent: '',
      details: blankDetailsFor('DEATH'),
    },
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

  /**
   * A scheme insures many lives, so a claim on one must say which. Loaded only for a scheme,
   * and only once the category is known.
   *
   * <p><b>Searched server-side rather than paged into the browser.</b> The endpoint's own `q`
   * filters the roll by name — the same filter the scheme page's member search uses — so a
   * 500-life schedule is answered by typing a name rather than by offering the first N and
   * hoping. Filtering a fetched page instead would search only the rows in hand and report
   * "not found" for somebody who is on the schedule.
   */
  /**
   * Does this policy insure MANY lives, so that a claim on it has to say which one died?
   *
   * <p><b>This tested GROUP_LIFE alone, and a credit-life claim could not be registered at all
   * because of it.</b> A CREDIT_LIFE scheme insures a lender's whole book, so the answer is
   * obviously yes — but the check was written when GROUP_LIFE was the only scheme category, and
   * the new one was added beside it rather than inside it. The consequence was not a wrong label:
   * the "Who died" picker never rendered, the schedule was never fetched, the client-side guard
   * never fired, and the form posted policyMemberId: null. The backend then refused with "Scheme
   * ... insures many lives, so a claim on it names a member" — correctly, and about a field that
   * was not on the screen, so there was no way through.
   *
   * <p>Phrased as the QUESTION rather than as a category test, because that is what every caller
   * here actually wants to know and it is what the next scheme category will also answer yes to.
   */
  const insuresManyLives =
    policy.data?.productCategory === 'GROUP_LIFE' ||
    policy.data?.productCategory === 'CREDIT_LIFE';
  const members = usePolicyStore(selectMembers(policyNumber));
  const loadMembers = usePolicyStore((s) => s.loadMembers);
  const [memberQuery, setMemberQuery] = useState('');

  useEffect(() => {
    if (!insuresManyLives) return;
    // Debounced, so typing a name is one request rather than one per keystroke. 300ms is
    // PartyPicker's own interval; the two searches should not feel different.
    const timer = setTimeout(() => {
      void loadMembers(policyNumber, {
        ...(memberQuery.trim() ? { q: memberQuery.trim() } : {}),
        pageSize: 50,
      });
    }, 300);
    return () => clearTimeout(timer);
  }, [insuresManyLives, policyNumber, memberQuery, loadMembers]);

  /**
   * Read by the resolver above at validation time. A ref rather than state because it must not
   * trigger a render of its own -- and written in an effect rather than during render, which
   * this console's lint rules forbid.
   */
  const productCategoryRef = useRef<string | null | undefined>(undefined);
  useEffect(() => {
    productCategoryRef.current = policy.data?.productCategory;
  }, [policy.data?.productCategory]);

  // Clearing it when the policy stops being a scheme matters: a member id left behind from a
  // previously-typed scheme number would be sent against an individual policy and refused.
  useEffect(() => {
    if (!insuresManyLives) setValue('policyMemberId', '');
  }, [insuresManyLives, setValue]);

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
      <PageHeader
        breadcrumb={[{ label: 'Claims', to: '/staff/claims' }]}
        title="Register a claim"
        description="Replaces nothing — this always creates a new claim."
      />

      {/*
        Three groups, not fourteen fields in a row. This was the longest form on the platform
        and it ran claimant, policy, member, date, type and up to four type-specific questions
        as one flat column, with nothing on screen saying a new subject had started -- so the
        only way to know how much was left was to scroll to the bottom.

        `Panel`, not `<fieldset>`: this console has one grouping primitive and its heading is
        an `<h2>` under the page's single `<h1>`. A second grouping idiom for one screen is how
        two idioms become five.

        No `emphasis` on any of them. The Panel doc is explicit that emphasis is one per page
        and only where the page exists to perform an act; here all three ARE the act, and
        promoting every panel promotes none of them.
      */}
      <form
        className="max-w-xl space-y-5 px-6 pb-8 pt-5"
        onSubmit={(e) => void handleSubmit(onSubmit)(e)}
      >
        <Panel title="Who and which policy">
        <div className="space-y-4 p-4">
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

        {insuresManyLives && (
          <>
          {/* The search sits OUTSIDE the FormField on purpose. FormField binds its label to the
              first control inside it, so putting two controls in one leaves the second
              unlabelled -- the select stopped answering to "Who died" and only an e2e run
              caught it, since every unit test addresses it by name through register(). */}
          <Input
            inputSize="sm"
            className="mb-1.5"
            placeholder="Search the schedule by name…"
            aria-label="Search members by name"
            value={memberQuery}
            onChange={(e) => setMemberQuery(e.target.value)}
          />
          <FormField label="Who died" error={errors.policyMemberId?.message}>
            {/* A scheme insures many lives, so "a claim on GL-000123" names none of them, and
                nothing else on the claim can say which employee it was. The claimant field
                above is who is FILING -- the widow -- not who died. */}
            <Select inputSize="sm" {...register('policyMemberId')}>
              <option value="">Choose the member…</option>
              {(members.data?.items ?? []).map((m) => (
                <option key={m.policyMemberId} value={m.policyMemberId}>
                  {/* THE BORROWER'S OWN NAME FIRST.

                      This resolved a name through the PARTY module or fell back to printing the
                      raw uuid, on the reasoning that a member with no party could not exist. One
                      can: a credit-life borrower is FREEFORM and has no party record, because
                      minting one per borrower would put a KYC obligation on a life whose cover
                      the lender owns. So this dropdown asked "who died?" and offered a column of
                      uuids -- the same defect the member roll had, and the same fix: the name is
                      on the member row, it was simply never read.

                      The reference is shown beside it because on a real book several borrowers
                      share a name, and the reference is the handle the lender quotes. */}
                  {memberLabel(m)}
                </option>
              ))}
            </Select>
            <p className="mt-1 text-xs text-subtle-foreground">
              Members who have left are listed too — a claim can arrive after somebody leaves,
              and what decides it is whether they were covered on the date of event.
              {(members.data?.page?.totalElements ?? 0) > (members.data?.items?.length ?? 0) && (
                <>
                  {' '}
                  Showing {members.data?.items?.length} of {members.data?.page?.totalElements} —
                  search by name to narrow it.
                </>
              )}
            </p>
          </FormField>
          </>
        )}
        </div>
        </Panel>

        <Panel title="The event">
        <div className="space-y-4 p-4">
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
                {humanizeStatus(type)}
              </option>
            ))}
          </Select>
        </FormField>
        </div>
        </Panel>

        {claimType && (
        <Panel
          title="Details"
          subtitle={`Specific to a ${humanizeStatus(claimType).toLowerCase()} claim`}
        >
        <div className="p-4">
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
              <CheckboxField label="Permanent" {...register('details.permanent')} />
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
        </Panel>
        )}

        {/* A 422 here (e.g. "Policy POL-X was not in force on <date>") is a real,
            whole-request business rejection, not a per-field error -- same shape
            as beneficiaries' BeneficiaryValidationException. */}
        {registering.status === 'error' && registering.error && (
          <InlineError error={registering.error} />
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" pending={registering.status === 'loading'}>
            Register claim
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
