import { Field } from '@/components/Field';
import { Panel } from '@/components/Panel';
import type { AgentQuotation } from '@/api/agentQuotations';
import { PREMIUM_FREQUENCY_LABELS, type PremiumFrequency } from '@/api/types';
import { formatDate, formatMonths } from '@/lib/dates';
import { formatMoney } from '@/lib/money';

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
  productName?: string | undefined;
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
