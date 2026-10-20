import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import type { ApiError } from '@/lib/apiError';
import { Panel } from '@/components/Panel';
import type { AgentQuotation } from '@/api/agentQuotations';
import { PREMIUM_FREQUENCY_LABELS, type PremiumFrequency } from '@/api/types';
import { formatDate, formatMonths } from '@/lib/dates';
import { formatMoney } from '@/lib/money';

/**
 * A saved quotation that could not be read. A 404 is the ordinary case -- it was cancelled, or swept at the end of
 * the day it was confirmed without being submitted (or it is another agent's) -- and is said in those words with the
 * way back, rather than as a bare "not found" with nowhere to go.
 */
export function QuotationUnavailable({ error }: { error: ApiError }) {
  if (error.status !== 404) return <InlineError sticky error={error} lead="The quotation could not be read:" />;
  return (
    <div role="alert" className="space-y-2 rounded-md border border-border bg-surface-muted p-3 text-xs">
      <p className="font-medium">This quotation is no longer available.</p>
      <p className="text-muted-foreground">
        A confirmed quotation is discarded when it is cancelled, or at the end of the day if it was not submitted as a
        proposal. Quote again to continue.
      </p>
      <Button asChild size="sm" variant="outline">
        <Link to="/agents/quotation">Back to the calculator</Link>
      </Button>
    </div>
  );
}

/** A figure the server saved as a JSON number. Formatting only: nothing here adds, scales or rounds it. */
function money(value: unknown, currency: string): string {
  return typeof value === 'number' ? formatMoney({ amount: String(value), currencyCode: currency }) : '—';
}

/**
 * The quotation the customer agreed to, as it was SAVED (2026-10-20): the figures are the server's, priced when the
 * agent confirmed, so the agent onboarding the customer and submitting the proposal is reading what the customer was
 * shown -- not a fresh price that may have moved since.
 */
export function AcceptedQuotationPanel({
  quotation,
  productName,
}: {
  quotation: AgentQuotation;
  productName?: ReactNode;
}) {
  const inputs = quotation.inputs;
  const quoted = quotation.quoted;
  const q = (quoted.quote ?? {}) as Record<string, unknown>;
  const currency = quoted.currency;
  const frequency = inputs.frequency as PremiumFrequency | null | undefined;

  return (
    <Panel title="The accepted quotation" subtitle={`Quoted on ${formatDate(quotation.quotedOn)}. Valid until ${formatDate(quotation.validUntil)}.`}>
      <dl className="px-4 pb-3">
        {inputs.prospect && <Field label="Customer" value={inputs.prospect.fullName} />}
        <Field label="Product" value={productName ?? '—'} />
        {'instalmentAmount' in q && (
          <>
            <Field label="Sum assured" value={money(quoted.sumAssured, currency)} />
            <Field
              label="Premium"
              value={`${money(q.instalmentAmount, currency)}${frequency ? ` ${PREMIUM_FREQUENCY_LABELS[frequency].toLowerCase()}` : ''}`}
            />
            <Field label="Annual equivalent" value={money(q.annualAfterFrequencyLoading, currency)} />
            {typeof q.totalOverTerm === 'number' && inputs.policyTermMonths != null && (
              <Field
                label={`Total over ${formatMonths(inputs.policyTermMonths)}`}
                value={`${money(q.totalOverTerm, currency)} (${String(q.instalmentsOverTerm)} payments)`}
              />
            )}
          </>
        )}
        {'totalYearlyPremium' in q && (
          <>
            <Field label="Plan" value={inputs.funeralPlanCode ?? '—'} />
            <Field label="Lives covered" value={String(inputs.lives?.length ?? 0)} />
            <Field label="Family total a year" value={money(q.totalYearlyPremium, currency)} />
            <Field
              label="Premium"
              value={`${money(q.instalment, currency)}${frequency ? ` ${PREMIUM_FREQUENCY_LABELS[frequency].toLowerCase()}` : ''}`}
            />
          </>
        )}
        {'kind' in q && (
          <>
            <Field label={q.kind === 'DEPOSIT' ? 'Deposit' : 'Opening balance'} value={money(q.amount, currency)} />
            {typeof q.ratePercent === 'number' && (
              <Field label="Interest rate" value={`${q.ratePercent}% ${q.kind === 'DEPOSIT' ? 'for the term' : 'a year'}`} />
            )}
            {q.kind === 'DEPOSIT' && <Field label="Value at maturity" value={money(q.maturityValue, currency)} />}
          </>
        )}
        {inputs.policyTermMonths != null && <Field label="Term" value={formatMonths(inputs.policyTermMonths)} />}
      </dl>
    </Panel>
  );
}
