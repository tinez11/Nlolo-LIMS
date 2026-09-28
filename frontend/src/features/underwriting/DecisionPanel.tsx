import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import type { UnderwritingCaseView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
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
}: {
  view: UnderwritingCaseView;
  deciding: Resource<UnderwritingCaseView>;
  /** Holds UNDERWRITER. Without it the endpoint is closed regardless of anything here. */
  canDecide: boolean;
  isSenior: boolean;
  /** The signed-in user's subject, compared with who opened and assessed the case. */
  callerSubject: string | null;
  onDecide: (request: ReturnType<typeof toApiRequest>) => void;
}) {
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<DecideFormInput, unknown, DecideFormValues>({
    resolver: zodResolver(decideFormSchema),
    defaultValues: blankDecideForm(),
  });

  // eslint-disable-next-line react-hooks/incompatible-library -- see RegisterClaimPage
  const outcome = watch('outcome');
  const recommendation = view.recommendationOutcome ?? null;
  const override = isOverride(outcome, recommendation);
  const blockedByRank = override && !isSenior;
  const conflict = separationOfDutiesConflict(view, callerSubject);

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
              No recommendation yet — the engine runs when an assessment is submitted. You can
              still decide, and it will not count as an override.
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
        ) : (
          <form className="space-y-4" onSubmit={(e) => void handleSubmit((v) => onDecide(toApiRequest(v)))(e)}>
            <FormField label="Decision" error={errors.outcome?.message}>
              <Select {...register('outcome')}>
                {/* No loading on a member's evidence case -- one member of a scheme has no
                    premium of their own, and the server refuses it. */}
                {DECISION_OUTCOMES.filter((o) => !(view.evidenceForPolicyNumber && o.value === 'LOADED')).map((o) => (
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

            <FormField label="Reason" error={errors.reason?.message}>
              <Textarea
                className="min-h-20"
                placeholder="Standard risk, in line with the recommendation"
                {...register('reason')}
              />
            </FormField>

            {blockedByRank && (
              <p
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

            <Button
              type="submit"
              variant="primary"
              // Kept apart on purpose: `blockedByRank` is a separation-of-duties refusal, which
              // no amount of waiting resolves, while `pending` is the request in flight. One
              // `disabled` carrying both told a blocked underwriter the platform was working.
              pending={deciding.status === 'loading'}
              disabled={blockedByRank}
            >
              Record decision
            </Button>
          </form>
        )}
      </div>
    </Panel>
  );
}
