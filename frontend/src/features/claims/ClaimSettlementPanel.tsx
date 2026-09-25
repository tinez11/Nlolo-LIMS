import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { type FieldErrors, useForm } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { PartyName } from '@/components/PartyName';
import { Receipt } from '@/components/Receipt';
import { selectDetail, usePolicyStore } from '@/store/policyStore';
import { cn } from '@/lib/cn';
import { compareAmounts, formatMoney, subtractAmounts } from '@/lib/money';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import {
  selectAssessments,
  selectClaimableCover,
  selectDecidingSettlement,
  useClaimStore,
} from '@/store/claimStore';
import { assessorName } from './assessorName';
import {
  blankApproveDecision,
  switchDecision,
  recommendationExceedsCover,
  settlementDecisionSchema,
  toApiRequest,
  type SettlementDecisionFormValues,
} from './settlementDecisionForm';
import { Input, Textarea } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';

/**
 * `POST /claims/{claimId}/settlement-decision` -- `CLAIMS_MANAGER` role only,
 * rendered by the parent only for a claim currently UNDER_ASSESSMENT (or
 * REGISTERED for a MATURITY claim, which auto-approves with no assessment at
 * all). `ClaimsApiImpl` additionally rejects the same PERSON who assessed this
 * claim from also deciding it -- a 422 this panel surfaces plainly rather than
 * trying to pre-empt, since the frontend has no way to know who assessed a
 * claim (no assessment-history endpoint exists to check against).
 *
 * Approve and reject are modelled as a genuine `z.discriminatedUnion`, not a
 * single schema with optional fields -- `reset()`, not `setValue()`, switches
 * branches, matching RegisterClaimPage's own claim-type switcher: the two
 * branches share no fields, so there is nothing to preserve across a toggle.
 */
export function ClaimSettlementPanel({
  claimId,
  policyNumber,
  onScheme,
}: {
  claimId: string;
  policyNumber: string;
  /** The claim names a member: a group or credit-life scheme, where one life leaves and the
   *  contract stays -- so "the policy closes" would be false. */
  onScheme: boolean;
}) {
  const loadPolicy = usePolicyStore((s) => s.loadDetail);
  const policy = usePolicyStore(selectDetail(policyNumber));
  // Unknown until the policy loads. Approving waits for it: guessing "not credit life" would ask
  // for a payee the backend then refuses, and guessing "credit life" would send none where one
  // is required.
  const productCategory = policy.data?.productCategory ?? null;
  const creditLife = productCategory === 'CREDIT_LIFE';
  const lenderPartyId = creditLife ? (policy.data?.policyholderPartyId ?? null) : null;
  const decideSettlement = useClaimStore((s) => s.decideSettlement);
  const resetDecideSettlement = useClaimStore((s) => s.resetDecideSettlement);
  const deciding = useClaimStore(selectDecidingSettlement(claimId));
  const loadClaimableCover = useClaimStore((s) => s.loadClaimableCover);
  const loadAssessments = useClaimStore((s) => s.loadAssessments);
  const coverResource = useClaimStore(selectClaimableCover(claimId));
  const assessmentsResource = useClaimStore(selectAssessments(claimId));
  // Minted once for the lifetime of this panel, reused across retries -- same
  // idiom as RegisterClaimPage, even though this key is only load-bearing on
  // approval (see api/claims.ts's decideSettlement doc).
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  useEffect(() => {
    resetDecideSettlement(claimId);
    void loadClaimableCover(claimId);
    void loadAssessments(claimId);
    void loadPolicy(policyNumber);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [claimId, policyNumber]);

  const cover = coverResource.data?.claimableCover ?? null;
  // Newest first, so [0] is the most recent assessment -- the one a second
  // assessor wrote after the first, if there were two.
  const latestAssessment = assessmentsResource.data?.[0] ?? null;
  const recommended = latestAssessment?.recommendedAmount ?? null;

  const {
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isDirty },
  } = useForm<SettlementDecisionFormValues>({
    resolver: zodResolver(settlementDecisionSchema(cover, !creditLife)),
    defaultValues: blankApproveDecision(),
  });

  // eslint-disable-next-line react-hooks/incompatible-library -- see RegisterClaimPage
  const approved = watch('approved');

  /*
    Both reads land AFTER this form mounts, so the starting amount cannot be a
    `defaultValue` -- it has to be written in when it arrives.

    `reset`, not `setValue`: reset also clears the dirty flag, so the prefilled
    figure is the form's BASELINE rather than looking like an edit somebody
    already made and might be expected to justify.

    Two guards, and both are load-bearing. `isDirty` stops a slow read
    overwriting something already typed -- the one behaviour that would be worse
    than no prefill at all. `approved` stops it resurrecting the approve branch
    under somebody who has moved to Reject while the reads were in flight.
  */
  useEffect(() => {
    if (isDirty || !approved) return;
    if (!recommended && !cover) return;
    reset(blankApproveDecision(recommended, cover));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recommended?.amount, recommended?.currencyCode, cover?.amount, cover?.currencyCode]);

  /*
    Derived from what is rendered right now, never stored in state -- the lint
    rule against synchronous setState in an effect exists for exactly this, and
    a stored copy would go stale on the keystroke after it was computed.

    `compareAmounts` returns NaN for a half-typed amount, and `< 0` is false for
    NaN, so nothing is claimed while somebody is still typing.
  */
  const typedAmount = watch('approvedAmount');
  const shortfall = (() => {
    if (!approved || !cover || typeof typedAmount !== 'string') return null;
    if (!(compareAmounts(typedAmount, cover.amount) < 0)) return null;
    const difference = subtractAmounts(cover.amount, typedAmount);
    return difference ? formatMoney({ amount: difference, currencyCode: cover.currencyCode }) : null;
  })();

  /**
   * Validation runs FIRST, then the confirmation.
   *
   * `handleSubmit` parks the validated values here rather than sending them, so
   * the second click has something true to describe -- confirming an amount and
   * then being told it is malformed would make the confirmation a formality to
   * click past, which is the failure mode this whole step exists to avoid.
   */
  const [pending, setPending] = useState<SettlementDecisionFormValues | null>(null);

  async function commit(values: SettlementDecisionFormValues) {
    await decideSettlement(claimId, toApiRequest(values, creditLife), attempt);
    if (useClaimStore.getState().decidingSettlement[claimId]?.status === 'success') {
      setPending(null);
    }
  }

  const decided = deciding.status === 'success' ? deciding.data : null;
  if (decided) {
    return (
      <div className="p-4">
        <Receipt
          heading={decided.status === 'REJECTED' ? 'Claim rejected' : 'Settlement approved'}
          /*
            Only the amount, and only when there is one.

            The outcome is already the heading, and the claim's status badge and
            id are both rendered elsewhere on this page -- repeating them here
            would put the same string on screen twice, which is how a spec's
            `getByText('Rejected')` becomes ambiguous and how a reader stops
            being able to tell which one is authoritative. A receipt reports what
            CHANGED, not a second copy of the record.
          */
          lines={
            decided.approvedAmount
              ? [{ label: 'Approved', value: formatMoney(decided.approvedAmount) }]
              : []
          }
          // Every field above comes off the ClaimView the server returned. The
          // note is the one thing the console knows and the response does not
          // say -- and it is the consequence an operator most needs on the
          // record, because nothing on this screen would otherwise reveal it.
          note={
            decided.status === 'REJECTED'
              ? 'A claims manager can reopen a rejected claim if new evidence arrives.'
              : 'Disbursement settles asynchronously through the payment rail. The policy is now closed and reopening this claim will not reverse that.'
          }
        />
      </div>
    );
  }

  return (
    <form className="space-y-3 p-4" onSubmit={(e) => void handleSubmit(setPending)(e)}>
      {/*
        A CHOICE of which decision to record, not an action -- nothing is sent from here.
        These were two buttons styled like the submit button, one labelled "Approve" beside an
        "Approve claim", so which one approved was a guess. A labelled radiogroup, the same
        pattern as the claim form's policy chooser, says what it is to sight and to a screen
        reader alike.
      */}
      <div className="flex items-center gap-2">
        <span id={`decision-${claimId}`} className="text-xs font-medium text-muted-foreground">
          Decision
        </span>
        <div
          role="radiogroup"
          aria-labelledby={`decision-${claimId}`}
          className="inline-flex rounded-md border border-border p-0.5"
        >
          {(['approve', 'reject'] as const).map((choice) => {
            const selected = (choice === 'approve') === approved;
            return (
              <button
                key={choice}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => {
                  const next = switchDecision(choice, approved, recommended, cover);
                  if (next) reset(next);
                }}
                className={cn(
                  'rounded px-3 py-1 text-xs transition-colors',
                  selected ? 'bg-selected font-medium ring-1 ring-border-strong ring-inset' : 'hover:bg-hover',
                )}
              >
                {choice === 'approve' ? 'Approve' : 'Reject'}
              </button>
            );
          })}
        </div>
      </div>

      {approved ? (
        <>
          <div className="grid grid-cols-[1fr_auto] gap-2">
            <FormField
              label="Approved amount"
              error={fieldError(errors, 'approvedAmount')}
              /*
                The ceiling, BEFORE anything is typed.

                This field used to be blank with a `placeholder="1500000.00"` --
                an invented number that was the only figure on screen, so it is
                what people entered and then had rejected by a 422 quoting a
                different one. The limit was always knowable; nothing published
                it.
              */
              hint={
                cover
                  ? `Covered for ${formatMoney(cover)} — the most this claim can pay`
                  : coverResource.status === 'error'
                    ? 'Could not read what this claim is covered for; the amount will be checked on submission'
                    : undefined
              }
            >
              <Input {...register('approvedAmount')} />
            </FormField>
            <FormField label="Currency" error={fieldError(errors, 'approvedCurrency')}>
              <Input
                className="w-20 uppercase"
                {...register('approvedCurrency')}
              />
            </FormField>
          </div>

          {/*
            Under, not over. Over is refused outright by the schema and by
            Claim.approve behind it.

            Paying LESS than the cover is a legitimate decision -- a partial
            disability award, a balance already partly repaid outside the
            schedule -- so it is not blocked. It is also exactly what a typo
            looks like, which is why the shortfall is stated in money rather
            than left for somebody to notice by subtracting two numbers.
          */}
          {shortfall && (
            <p className="text-xs text-status-warning-fg">
              {shortfall} less than this claim is covered for.
              {recommended && ' Change it only on a finding that justifies paying less.'}
            </p>
          )}

          {/*
            Where the starting number came from. A prefilled field that does not
            say why is a number somebody is being asked to trust blind, and this
            one carries a colleague's judgement.
          */}
          {latestAssessment && cover && recommendationExceedsCover(recommended, cover) ? (
            // An assessment recorded before the backend bounded recommendations. Starting from
            // it would open the form already refusing its own value, so it starts at the cover
            // -- and says so, because silently replacing a colleague's figure is its own harm.
            <p className="text-xs text-status-warning-fg">
              The latest assessment, by {assessorName(latestAssessment)}, recommended{' '}
              {formatMoney(latestAssessment.recommendedAmount)}, more than the{' '}
              {formatMoney(cover)} this claim is covered for. Starting at the cover, the most it
              can pay.
            </p>
          ) : (
            latestAssessment && (
              <p className="text-xs text-muted-foreground">
                Starts at {formatMoney(latestAssessment.recommendedAmount)}, recommended by{' '}
                <span className="font-medium">{assessorName(latestAssessment)}</span>. Change it if you
                have a finding they did not.
              </p>
            )
          )}
          {creditLife ? (
            /*
              NOT a field. The insurer deals only with the lender (client answer 3.6), who is
              the claimant and the policyholder (spec 2.9) -- there is no payee to choose. This
              was a "Mobile-money destination" box, so finance was sent EFTs to "mobile" and
              "i approve", and whoever approved could have typed any account at all.
            */
            <div className="rounded-md border border-border bg-surface px-3 py-2 text-xs">
              <p>
                Pays{' '}
                {lenderPartyId ? (
                  <PartyName partyId={lenderPartyId} className="font-medium" />
                ) : (
                  'the lender'
                )}
                , the lender who holds this scheme, by bank transfer.
              </p>
              <p className="mt-0.5 text-muted-foreground">
                Finance makes the transfer to the lender&rsquo;s account and records it under Finance
                &rarr; Bank transfers.
              </p>
            </div>
          ) : (
            <FormField label="Payee reference" error={fieldError(errors, 'payeeRef')}>
              <Input placeholder="Mobile-money destination" {...register('payeeRef')} />
            </FormField>
          )}
        </>
      ) : (
        <FormField label="Rejection reason (optional)">
          <Textarea
            className="min-h-16"
            placeholder="Insufficient evidence"
            {...register('rejectionReason')}
          />
        </FormField>
      )}

      {deciding.status === 'error' && deciding.error && (
        <InlineError error={deciding.error} />
      )}

      {pending ? (
        <ConfirmAct
          heading={pending.approved ? 'Approve this settlement?' : 'Reject this claim?'}
          tone="danger"
          consequence={
            pending.approved ? (
              <>
                Pay{' '}
                <strong>
                  {pending.approvedCurrency.toUpperCase()} {pending.approvedAmount}
                </strong>{' '}
                to{' '}
                {creditLife ? (
                  <>
                    <strong>
                      {lenderPartyId ? <PartyName partyId={lenderPartyId} /> : 'the lender'}
                    </strong>{' '}
                    by bank transfer
                  </>
                ) : (
                  <strong>{pending.payeeRef}</strong>
                )}
                .
              </>
            ) : (
              <>
                Refuse this claim
                {pending.rejectionReason?.trim() ? (
                  <>
                    {' '}
                    on the grounds: <strong>{pending.rejectionReason.trim()}</strong>
                  </>
                ) : (
                  <> with no reason recorded</>
                )}
                .
              </>
            )
          }
          /*
            Both lines are facts about the backend, checked in Claim.java rather
            than assumed. Approving is the one an operator cannot discover from
            this screen: `claims.application.PaymentEventListener` closes the
            policy on settlement, and `Policy` has no transition out of MATURED
            or SURRENDERED -- so a claim reopened from SETTLED, even if it is
            then re-rejected, leaves the policy permanently closed. That is
            documented in Claim.reopen()'s own Javadoc as a known asymmetry.
            Rejection really is recoverable, and says so, which is what keeps
            the warning above meaningful.
          */
          reversal={
            pending.approved
              ? onScheme
                ? // A scheme is not discharged by one death: this life leaves when the claim
                  // settles, and everybody else stays insured (PolicyApiImpl.dischargeForSettledClaim).
                  'Money moves, and this life leaves the scheme when the claim settles — the scheme and everyone else on it stay in force.'
                : 'Money moves, and the policy closes permanently — reopening this claim later will not reverse the closure or restart billing.'
              : 'A claims manager can reopen a rejected claim, but this decision stays on the record.'
          }
          // Never the arming button's own words: "Reject claim" twice, a click
          // apart, is how the second click becomes as automatic as the first.
          confirmLabel={pending.approved ? 'Approve and pay' : 'Record the rejection'}
          busy={deciding.status === 'loading'}
          onConfirm={() => void commit(pending)}
          onCancel={() => setPending(null)}
        />
      ) : (
        <Button
          type="submit"
          size="sm"
          variant="primary"
          // Approving waits for the policy: whether a payee is asked for depends on it.
          disabled={deciding.status === 'loading' || (approved && productCategory === null)}
        >
          {approved ? 'Approve claim' : 'Reject claim'}
        </Button>
      )}
    </form>
  );
}

// react-hook-form's FieldErrors does not narrow per-branch on a discriminated
// union used directly as TFieldValues, the same limitation claimRegisterForm's
// nested `details` union hits -- see RegisterClaimPage's detailError for why
// this reads correctly at runtime regardless.
function fieldError(errors: FieldErrors<SettlementDecisionFormValues>, field: string): string | undefined {
  const rec = errors as Record<string, { message?: string } | undefined>;
  return rec[field]?.message;
}
