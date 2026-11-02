import { zodResolver } from '@hookform/resolvers/zod';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import type { CreditLifeAgreement } from '@/api/types';
import { can } from '@/auth/access';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { CheckboxField } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { formatDate, todayIso } from '@/lib/dates';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAddingAgreement, selectAgreements, useCreditLifeStore } from '@/store/creditLifeStore';
import {
  agreementFormSchema,
  COMMISSION_CAP_PERCENT,
  defaultAgreementValues,
  isPositive,
  pricingRuleInWords,
  toAgreementInput,
  trimZeros,
  type AgreementFormValues,
} from './creditLifeAgreementForm';
import { CREDIT_LIFE_PREMIUM_BASES, CREDIT_LIFE_PREMIUM_BASIS_LABELS } from './creditLifeSchemeIssueForm';

/**
 * The lender's yearly agreements on a credit-life scheme (policy V43, 2026-10-30).
 *
 * One a year: the rate and how it is applied, the least a loan pays, the youngest and oldest borrower, the shortest and
 * longest loan, the lender's commission and whether cover includes interest. Each borrower is priced on the agreement
 * in force the day their loan started, so a new year never re-prices anybody already covered -- which is why an
 * agreement is never edited once made, and why the next year is ADDED here rather than changed.
 *
 * Agreement first (2026-11-01): a scheme takes no file, and gives out no template, until it has one.
 *
 * In the work column, not the record rail: adding a year is an act, and the rail holds facts (the Record-Rail Rule).
 * The rail shows the year in force through {@link AgreementInForce}.
 */
export function CreditLifeAgreementsPanel({ policyNumber, currency }: { policyNumber: string; currency: string }) {
  const agreements = useCreditLifeStore(selectAgreements(policyNumber));
  const loadAgreements = useCreditLifeStore((s) => s.loadAgreements);
  const canManage = can('schemes.manage');
  const [adding, setAdding] = useState(false);

  if (isInitialLoad(agreements)) return <LoadingBlock label="Loading agreements" />;
  if (agreements.error && !agreements.data) {
    return <ErrorPanel error={agreements.error} onRetry={() => void loadAgreements(policyNumber)} />;
  }
  const rows = agreements.data ?? [];
  const today = todayIso();
  const latest = rows.length > 0 ? rows[rows.length - 1] ?? null : null;

  return (
    <div className="space-y-4 p-4">
      {rows.length === 0 ? (
        <EmptyState
          title="No agreement yet"
          description="Every loan is priced on it, so the lender's template and first file wait for it."
        />
      ) : (
        <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Agreements">
          {/* Newest first: the year a person is asking about is the current or the next one. */}
          {[...rows].reverse().map((a) => (
            <AgreementRow key={a.agreementId} agreement={a} currency={currency} today={today} />
          ))}
        </div>
      )}

      {canManage && !adding && (
        <Button size="sm" variant={rows.length === 0 ? 'primary' : 'outline'} onClick={() => setAdding(true)}>
          <Plus className="size-4" aria-hidden />
          {rows.length === 0 ? 'Add the agreement' : 'Add the next year'}
        </Button>
      )}
      {canManage && adding && (
        <AddAgreementForm
          policyNumber={policyNumber}
          currency={currency}
          previous={latest}
          onDone={() => setAdding(false)}
        />
      )}
    </div>
  );
}

function periodOf(a: CreditLifeAgreement, today: string): 'IN_FORCE' | 'STARTS_LATER' | 'ENDED' {
  if (a.validFrom > today) return 'STARTS_LATER';
  if (a.validTo < today) return 'ENDED';
  return 'IN_FORCE';
}

function AgreementRow({ agreement: a, currency, today }: {
  agreement: CreditLifeAgreement;
  currency: string;
  today: string;
}) {
  return (
    <div role="listitem" className="space-y-1 px-4 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium">
          {formatDate(a.validFrom)} to {formatDate(a.validTo)}
        </span>
        <StatusBadge kind="agreementPeriod" value={periodOf(a, today)} />
      </div>
      <p className="text-sm">{pricingRuleInWords(a)}.</p>
      <p className="text-xs text-muted-foreground">{termsInWords(a, currency)}</p>
      <p className="text-xs text-muted-foreground">
        Added by <StaffName subject={a.createdBy} /> on {formatDate(a.createdAt)}
      </p>
    </div>
  );
}

/** Everything but the price, in one line: what decides who is covered and what the lender earns. */
function termsInWords(a: CreditLifeAgreement, currency: string): string {
  const minimum = isPositive(a.minimumPremium)
    ? `At least ${formatMoney({ amount: a.minimumPremium, currencyCode: currency })} a loan`
    : 'No minimum premium';
  const commission = isPositive(a.commissionPercent)
    ? `${trimZeros(a.commissionPercent)}% commission to the lender`
    : 'No commission';
  return [
    minimum,
    `loans of ${a.minTermMonths} to ${a.maxTermMonths} months`,
    `borrowers aged ${a.minAge} to ${a.maxAge} when the loan starts`,
    commission,
    a.coverIncludesInterest ? 'a claim pays the balance with its interest' : 'a claim pays the principal still owed',
  ].join(' · ');
}

function AddAgreementForm({ policyNumber, currency, previous, onDone }: {
  policyNumber: string;
  currency: string;
  previous: CreditLifeAgreement | null;
  onDone: () => void;
}) {
  const add = useCreditLifeStore((s) => s.addAgreement);
  const acting = useCreditLifeStore(selectAddingAgreement(policyNumber));
  const form = useForm<AgreementFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(agreementFormSchema()),
    defaultValues: defaultAgreementValues(previous),
  });
  const errors = form.formState.errors;

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      onSubmit={form.handleSubmit((v) =>
        void add(policyNumber, toAgreementInput(v)).then(() => {
          if (useCreditLifeStore.getState().addingAgreement[policyNumber]?.status === 'success') onDone();
        }),
      )}
    >
      <p className="text-xs font-medium">{previous ? 'The next year' : "The lender's agreement"}</p>
      <p className="text-xs text-muted-foreground">
        Cannot be changed once added: every borrower whose loan starts within it is priced on it. A renegotiated year
        is the next agreement.
      </p>
      {acting.status === 'error' && acting.error && <InlineError error={acting.error} />}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <FormField label="From" error={errors.validFrom?.message}>
          <Controller
            control={form.control}
            name="validFrom"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
        <FormField label="To" error={errors.validTo?.message}>
          <Controller
            control={form.control}
            name="validTo"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>

        <FormField label="Premium rate (% a year)" error={errors.premiumRatePercent?.message}>
          <Input inputMode="decimal" placeholder="0.6" {...form.register('premiumRatePercent')} />
        </FormField>
        <FormField label="How the rate is charged" error={errors.premiumBasis?.message}>
          <Select {...form.register('premiumBasis')}>
            {CREDIT_LIFE_PREMIUM_BASES.map((b) => (
              <option key={b} value={b}>
                {CREDIT_LIFE_PREMIUM_BASIS_LABELS[b]}
              </option>
            ))}
          </Select>
        </FormField>

        <FormField label="Youngest borrower (age when the loan starts)" error={errors.minAge?.message}>
          <Input inputMode="numeric" {...form.register('minAge')} />
        </FormField>
        <FormField label="Oldest borrower (age when the loan starts)" error={errors.maxAge?.message}>
          <Input inputMode="numeric" placeholder="75" {...form.register('maxAge')} />
        </FormField>

        <FormField label="Shortest loan (months)" error={errors.minTermMonths?.message}>
          <Input inputMode="numeric" {...form.register('minTermMonths')} />
        </FormField>
        <FormField label="Longest loan (months)" error={errors.maxTermMonths?.message}>
          <Input inputMode="numeric" placeholder="120" {...form.register('maxTermMonths')} />
        </FormField>

        <FormField label={`Least a loan pays (${currency}, optional)`} error={errors.minimumPremium?.message}>
          <Input inputMode="decimal" placeholder="2000" {...form.register('minimumPremium')} />
        </FormField>
        <FormField
          label={`Lender's commission (% of premium, at most ${COMMISSION_CAP_PERCENT})`}
          error={errors.commissionPercent?.message}
        >
          <Input inputMode="decimal" placeholder="10" {...form.register('commissionPercent')} />
        </FormField>
      </div>

      <CheckboxField
        label="Cover includes the loan's interest: a claim pays the balance with its interest, not the principal alone"
        {...form.register('coverIncludesInterest')}
      />

      <div className="flex gap-2">
        <Button type="submit" size="sm" variant="primary" disabled={acting.status === 'loading'}>
          Add the agreement
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

