import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import type { UnderwritingCaseView } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select, Textarea } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import type { Resource } from '@/store/createResourceSlice';
import {
  blankDecideForm,
  decideFormSchema,
  DECISION_OUTCOMES,
  isOverride,
  separationOfDutiesConflict,
  toApiRequest,
  type DecideFormInput,
  type DecideFormValues,
} from './decideForm';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/** Ties the separation-of-duties refusal to the button it explains. */
const BLOCKED_BY_RANK_ID = 'decision-blocked-by-rank';

/** The outcome in real words, with the loading if there is one. */
function consequenceOf(values: DecideFormValues) {
  const label = DECISION_OUTCOMES.find((o) => o.value === values.outcome)?.label ?? values.outcome;
  if (values.outcome === 'LOADED') {
    return (
      <>
        Accept this risk with a loading of <strong>{values.loadingPercent}%</strong>, and issue the
        policy.
      </>
    );
  }
  if (values.outcome === 'ACCEPT') {
    return <>Accept this risk and issue the policy.</>;
  }
  if (values.outcome === 'DECLINED') {
    return <>Refuse this risk. No policy is issued.</>;
  }
  return (
    <>
      Record <strong>{label}</strong>. No policy is issued.
    </>
  );
}

/**
 * Where an underwriter decides the case.
 *
 * The engine's recommendation sits at the top as ADVICE, which is the whole point of the
 * split: it used to write straight into the decision fields, so a placeholder algorithm --
 * `SimpleRulesEngine`, whose own source calls its thresholds "illustrative, not actuarially
 * validated" -- settled every case on this platform and issued the policy, with no person
 * anywhere in the chain and no way to disagree.
 *
 * ## Why the override control is disabled rather than hidden
 *
 * A junior underwriter choosing an outcome that departs from the recommendation gets a
 * disabled button and a sentence naming what is missing. Hiding the option would leave them
 * unable to tell a decision they may not make from one the form does not support, and the
 * next step -- fetch a senior -- is only obvious if the restriction is visible.
 *
 * The gate is still enforced server-side, and this panel surfaces that 403 if it comes.
 * The two can legitimately disagree: whether a decision counts as an override depends on the
 * recommendation at the moment the server reads it, and a colleague submitting an assessment
 * meanwhile can move it.
 */
export function DecisionPanel({
  view,
  deciding,
  canDecide,
  isSenior,
  callerSubject,
  onDecide,
  annuity = false,
}: {
  view: UnderwritingCaseView;
  deciding: Resource<UnderwritingCaseView>;
  /** Holds UNDERWRITER. Without it the endpoint is closed regardless of anything here. */
  canDecide: boolean;
  isSenior: boolean;
  /** The signed-in user's subject, compared with who opened and assessed the case. */
  callerSubject: string | null;
  onDecide: (request: ReturnType<typeof toApiRequest>) => void;
  /**
   * An annuity case (product step 5): the light path. Accepted or declined only, on proof of age,
   * with no assessment -- the risk is the annuitant living long, which no medical evidence prices.
   */
  annuity?: boolean;
}) {
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<DecideFormInput, unknown, DecideFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(decideFormSchema),
    defaultValues: blankDecideForm(annuity),
  });

  /*
    The decision is held here between the submit and the second, deliberate click.

    It was the one comparably consequential act on the platform with no confirmation:
    claim settlement, EFT execution, reinstatement and KYC all go through `ConfirmAct`,
    while an underwriter decided a life in a single click from a select that may still
    be carrying its previous value. A decided case is closed to further evidence unless
    it was POSTPONED (`UnderwritingCaseAlreadyDecidedException`), so a mis-click is not
    trivially undoable, and on ACCEPT/LOADED it issues the policy.
  */
  const [pending, setPending] = useState<DecideFormValues | null>(null);

  // eslint-disable-next-line react-hooks/incompatible-library -- see RegisterClaimPage
  const outcome = watch('outcome');
  const recommendation = view.recommendationOutcome ?? null;
  const override = isOverride(outcome, recommendation);
  const blockedByRank = override && !isSenior;
  const conflict = separationOfDutiesConflict(view, callerSubject);
  /*
    The evidence itself, not the recommendation derived from it.

    This gate first asked whether a recommendation existed, on the reasoning that an assessed
    case always has one. That is false, and false precisely where it matters: `recommendFromEvidence`
    returns early for a GROUP SCHEME -- "no engine opinion on a group scheme, and this is a
    decision rather than a gap", because the age band it would resolve belongs to a company. A
    scheme case therefore carries assessments and no recommendation for ever, and the gate made
    every one of them permanently undecidable.

    `assessedBy` is the fact, and it is already on the wire for the separation-of-duties check
    beside it: "identity-provider subjects of everyone who recorded an assessment on the case".
    Empty means nothing has been assessed, which is the one thing `decide` actually refuses.
  */
  // An annuity needs no assessment: decide accepts it on proof of age alone.
  const awaitingAssessment = !annuity && (view.assessedBy ?? []).length === 0;

  if (!canDecide) return null;

  return (
    <Panel
      emphasis
      title="Decide this case"
      subtitle="The decision is what settles the case and, on an acceptance, issues the policy."
    >
      <div className="space-y-4 p-4">
        {/*
          Advice, and labelled as advice. It is deliberately not pre-selected into the
          outcome field: a recommendation the form has already acted on is one nobody reads.
        */}
        <div className="rounded-md border border-border bg-surface-muted px-3 py-2">
          {recommendation ? (
            <>
              <p className="text-xs">
                <span className="text-muted-foreground">The rules engine recommends </span>
                <strong>{recommendation}</strong>
                {view.recommendationLoadingPercent != null && (
                  <span> at {view.recommendationLoadingPercent}%</span>
                )}
              </p>
              {view.recommendationReason && (
                <p className="mt-0.5 text-xs text-muted-foreground">{view.recommendationReason}</p>
              )}
              <p className="mt-1 text-xs text-subtle-foreground">
                A recommendation, not a decision. It is recomputed each time evidence arrives
                and binds nothing.
              </p>
            </>
          ) : (
            <p className="text-xs text-muted-foreground">
              {annuity
                ? 'No engine opinion on an annuity — it is accepted on proof of age, and the income is priced from the rate table when the premium arrives.'
                : awaitingAssessment
                ? 'No recommendation yet — the engine runs when an assessment is submitted.'
                : /*
                     Assessed, and still no advice: this is a group scheme, where the engine
                     deliberately has no opinion. Saying "not yet" here would be waiting for
                     something that is never coming.
                   */
                  'No recommendation on a scheme — the engine rates one life from one age band, and the age it would read is the employer’s. Decide it on the evidence; it will not count as an override.'}
            </p>
          )}
        </div>

        {/*
          The form is withheld rather than disabled: nothing on it is theirs to fill in, whatever
          outcome they pick, and seniority does not lift it -- the senior rule is about
          overriding the engine, this one is about marking one's own work.
        */}
        {conflict ? (
          <p
            role="status"
            className="rounded-md bg-status-warning-bg px-3 py-2 text-xs text-status-warning-fg"
          >
            You {conflict === 'opened' ? 'opened this case' : 'recorded an assessment on this case'},
            so another underwriter must decide it. Separation of duties: whoever takes or assesses
            a proposal does not also accept it.
          </p>
        ) : awaitingAssessment ? (
          /*
            Withheld, like the separation-of-duties branch above, rather than offered and
            refused. `UnderwritingApiImpl.decide` throws "there is nothing to decide on" when a
            case carries no assessment -- "accepted, nothing assessed" is not a decision anyone
            can defend later -- and this panel used to say the opposite in as many words: "You
            can still decide, and it will not count as an override." The second half was true
            and the first was not, so an underwriter filled the form, submitted, and got a raw
            validation error for doing exactly what the screen invited.
          */
          <p
            role="status"
            className="rounded-md bg-status-warning-bg px-3 py-2 text-xs text-status-warning-fg"
          >
            Record an assessment first. A case with no evidence on it cannot be decided — an
            acceptance nobody assessed is not a decision that can be defended later.
          </p>
        ) : (
          <form className="space-y-4" onSubmit={(e) => void handleSubmit((v) => setPending(v))(e)}>
            <FormField label="Decision" error={errors.outcome?.message}>
              <Select {...register('outcome')}>
                {/* No loading on a member's evidence case -- one member of a scheme has no
                    premium of their own, and the server refuses it. */}
                {DECISION_OUTCOMES.filter(
                  (o) =>
                    !(view.evidenceForPolicyNumber && o.value === 'LOADED') &&
                    // An annuity is accepted or declined; its premium is the purchase price.
                    !(annuity && (o.value === 'LOADED' || o.value === 'POSTPONED')),
                ).map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            </FormField>

            {outcome === 'LOADED' && (
              <FormField label="Loading (%)" error={errors.loadingPercent?.message}>
                <Input className="w-32" placeholder="25" {...register('loadingPercent')} />
              </FormField>
            )}

            {annuity && outcome === 'ACCEPT' && (
              <FormField label="Proof of age" error={errors.ageEvidenceConfirmed?.message}>
                <CheckboxField
                  label="Age evidence confirmed — I have seen the annuitant's proof of age"
                  {...register('ageEvidenceConfirmed')}
                />
              </FormField>
            )}

            <FormField label="Reason" error={errors.reason?.message}>
              <Textarea
                className="min-h-20"
                placeholder="Standard risk, in line with the recommendation"
                {...register('reason')}
              />
            </FormField>

            {blockedByRank && (
              <p
                id={BLOCKED_BY_RANK_ID}
                role="status"
                className="rounded-md bg-status-warning-bg px-3 py-2 text-xs text-status-warning-fg"
              >
                This departs from the recommendation of <strong>{recommendation}</strong>, so a
                senior underwriter has to record it. Refer the case, or ask one to decide it.
              </p>
            )}

            {override && isSenior && (
              <p className="text-xs text-muted-foreground">
                This departs from the recommendation of {recommendation}. It will be recorded as
                an override, against your name.
              </p>
            )}

            {deciding.status === 'error' && deciding.error && (
              <InlineError error={deciding.error} />
            )}

            {pending ? (
              <ConfirmAct
                heading={
                  pending.outcome === 'DECLINED'
                    ? 'Decline this risk?'
                    : pending.outcome === 'POSTPONED'
                      ? 'Postpone this case?'
                      : 'Record this acceptance?'
                }
                tone={pending.outcome === 'DECLINED' ? 'danger' : 'primary'}
                consequence={consequenceOf(pending)}
                /*
                  Facts about the backend, not caution. `UnderwritingApiImpl` refuses a second
                  decision on any decided case that is not POSTPONED, and
                  `UnderwritingDecisionEventListener` issues the policy on ACCEPT and LOADED
                  only -- DECLINED and POSTPONED never issue.
                */
                reversal={
                  pending.outcome === 'POSTPONED' ? (
                    <>
                      A postponed case can be decided again once new evidence arrives, so this
                      one is recoverable.
                    </>
                  ) : (
                    <>
                      Nothing here can undo it: the case closes to further evidence, and only a
                      postponement can be decided a second time.
                    </>
                  )
                }
                /*
                  Deliberately not "Record decision" again. Two buttons with the same
                  accessible name, one replacing the other, is a confirmation a person can
                  click through on muscle memory -- and the naming pattern here is the one
                  `ClaimSettlementPanel` already sets, where "Approve claim" is confirmed by
                  "Approve and pay".
                */
                confirmLabel={
                  pending.outcome === 'DECLINED'
                    ? 'Decline the risk'
                    : pending.outcome === 'POSTPONED'
                      ? 'Postpone the case'
                      : 'Record the acceptance'
                }
                busy={deciding.status === 'loading'}
                onConfirm={() => onDecide(toApiRequest(pending))}
                onCancel={() => setPending(null)}
              />
            ) : (
              <Button
                type="submit"
                variant="primary"
                // Kept apart on purpose: `blockedByRank` is a separation-of-duties refusal, which
                // no amount of waiting resolves, while `pending` is the request in flight. One
                // `disabled` carrying both told a blocked underwriter the platform was working.
                pending={deciding.status === 'loading'}
                disabled={blockedByRank}
                // The refusal above explains this button, so it is named as the button's
                // description rather than left to sit near it. `title` was the pattern here and
                // reaches nobody: a disabled button is not focusable, so neither a keyboard user
                // nor a screen reader ever got the reason.
                aria-describedby={blockedByRank ? BLOCKED_BY_RANK_ID : undefined}
              >
                Record decision
              </Button>
            )}
          </form>
        )}
      </div>
    </Panel>
  );
}
