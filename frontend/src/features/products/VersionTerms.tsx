import type { AnnuityTermsView, FuneralTermsView, UnitLinkedTermsView } from '@/api/types';
import { FUNERAL_ROLE_LABELS, FUNERAL_ROLES, type FuneralRoleName } from './funeralSchema';
import { FactorSection, SectionHeading } from './RatingBasis';

/**
 * A version's own terms, read-only, on the product page (2026-10-08): what a funeral plan pays and charges, what an
 * annuity offers, what a unit-linked version charges. The page showed none of it, so a funeral version priced at its
 * own benefit -- 4,000,000 a year for 4,000,000 of cover -- could not be seen anywhere but in a quote.
 */

const AMOUNT = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
const amount = (n: number | null | undefined) => (n == null ? '—' : AMOUNT.format(n));
const roleLabel = (role: string) => FUNERAL_ROLE_LABELS[role as FuneralRoleName] ?? role;
const SOLD_AS: Record<string, string> = { INDIVIDUAL: 'Individual policies', GROUP: 'Group schemes', BOTH: 'Individual policies and group schemes' };

export function FuneralTermsDetail({ terms }: { terms: FuneralTermsView }) {
  const roles = FUNERAL_ROLES.filter((role) => terms.benefits.some((b) => b.role === role));
  const groupRated = terms.plans.some((p) => p.groupMonthlyRate != null);
  // The premium table's columns: each priced role's age bands, youngest first.
  const bandColumns = roles.flatMap((role) => {
    const bands = [...new Map(terms.premiums.filter((p) => p.role === role)
      .map((p) => [`${p.ageFrom}-${p.ageTo}`, { from: p.ageFrom, to: p.ageTo }])).values()].sort((a, b) => a.from - b.from);
    return bands.map((band) => ({ role, ...band }));
  });
  const priceOf = (planCode: string, role: string, from: number) =>
    terms.premiums.find((p) => p.planCode === planCode && p.role === role && p.ageFrom === from)?.yearlyPremium;

  return (
    <div className="space-y-1 pb-2">
      <SectionHeading>Plans and benefits</SectionHeading>
      <p className="px-4 text-xs text-muted-foreground">Sold to: {SOLD_AS[terms.soldAs] ?? terms.soldAs}</p>
      <div className="overflow-x-auto px-4">
        <table className="text-xs">
          <caption className="sr-only">Benefits per plan and role</caption>
          <thead>
            <tr className="text-left text-muted-foreground">
              <th className="py-1 pr-4">Plan</th>
              {roles.map((role) => <th key={role} className="py-1 pr-4 text-right">{roleLabel(role)} benefit</th>)}
              {groupRated && <th className="py-1 pr-4 text-right">Group rate per member</th>}
            </tr>
          </thead>
          <tbody>
            {terms.plans.map((plan) => (
              <tr key={plan.planCode} className="border-t border-border">
                <th scope="row" className="py-1 pr-4 text-left font-medium">{plan.planCode} · {plan.name}</th>
                {roles.map((role) => (
                  <td key={role} className="py-1 pr-4 text-right">
                    {amount(terms.benefits.find((b) => b.planCode === plan.planCode && b.role === role)?.benefit)}
                  </td>
                ))}
                {groupRated && (
                  <td className="py-1 pr-4 text-right">
                    {plan.groupMonthlyRate == null ? '—'
                      : `${amount(plan.groupMonthlyRate)} ${plan.groupRatePeriod === 'YEARLY' ? 'a year' : 'a month'}`}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {terms.premiums.length > 0 && (
        <>
          <SectionHeading>Premium table — yearly premium per life</SectionHeading>
          <div className="overflow-x-auto px-4">
            <table className="text-xs">
              <caption className="sr-only">Yearly premium per plan, role and age band</caption>
              <thead>
                <tr className="text-left text-muted-foreground">
                  <th className="py-1 pr-4">Plan</th>
                  {bandColumns.map((c) => (
                    <th key={`${c.role}-${c.from}`} className="py-1 pr-4 text-right font-normal">
                      <span className="block font-medium">{roleLabel(c.role)}</span>ages {c.from}–{c.to}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {terms.plans.map((plan) => (
                  <tr key={plan.planCode} className="border-t border-border">
                    <th scope="row" className="py-1 pr-4 text-left font-medium">{plan.planCode}</th>
                    {bandColumns.map((c) => (
                      <td key={`${c.role}-${c.from}`} className="py-1 pr-4 text-right">{amount(priceOf(plan.planCode, c.role, c.from))}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <FactorSection
        title="Who may be covered"
        empty="No roles."
        rows={terms.roles.map((r) => ({
          key: r.role,
          label: roleLabel(r.role),
          note: [`joins ${r.minEntryAge}–${r.maxEntryAge}`,
            r.coverStopAge != null ? `cover stops at ${r.coverStopAge}` : null,
            r.studentStopAge != null ? `${r.studentStopAge} as a student` : null].filter(Boolean).join(' · '),
          value: `up to ${r.maxLives ?? '—'}`,
        }))}
      />
      <FactorSection
        title="Claims"
        empty=""
        rows={[
          { key: 'wait', label: 'Waiting period', note: terms.accidentWaivesWaiting ? 'an accidental death is paid from day one' : null,
            value: terms.waitingPeriodMonths == null ? 'None' : `${terms.waitingPeriodMonths} months` },
          { key: 'payee', label: 'A dependant’s death is paid to', note: null,
            value: terms.dependantClaimPayee === 'MAIN_MEMBER' ? 'The main member' : 'The beneficiary the main member named' },
          { key: 'main', label: 'When the main member dies', note: terms.freeCoverToPaidDate ? 'the family stays covered free to the next premium date' : null,
            value: terms.onMainMemberDeath === 'SPOUSE_TAKES_OVER' ? 'The spouse takes over' : 'The policy ends' },
        ]}
      />
    </div>
  );
}

export function AnnuityTermsDetail({ terms }: { terms: AnnuityTermsView }) {
  return (
    <div className="pb-2">
      <FactorSection
        title="Annuity forms"
        empty="No forms."
        rows={terms.forms.map((f) => ({
          key: f.formCode,
          label: f.formCode,
          note: [f.joint ? `joint, ${f.survivorPercent ?? '—'}% to the survivor` : 'single life',
            f.guaranteeYears > 0 ? `guaranteed ${f.guaranteeYears} years` : null,
            Number(f.escalationPercent) > 0 ? `rises ${f.escalationPercent}% a year` : null,
            f.capitalProtected ? 'capital protected' : null,
            f.rateBasis === 'BY_SEX' ? 'rates by sex' : 'unisex rates'].filter(Boolean).join(' · '),
          value: '',
        }))}
      />
      <FactorSection
        title="Payments"
        empty="No frequencies."
        rows={[
          { key: 'timing', label: 'Paid', note: null, value: terms.timing === 'ADVANCE' ? 'In advance' : 'In arrears' },
          { key: 'proof', label: 'Proof of life', note: null, value: `every ${terms.proofOfLifeIntervalMonths} months` },
          ...terms.frequencies.map((f) => ({ key: f.frequency, label: f.frequency.toLowerCase().replace('_', '-'), note: 'factor', value: f.factor })),
        ]}
      />
    </div>
  );
}

export function UnitLinkedTermsDetail({ terms }: { terms: UnitLinkedTermsView }) {
  return (
    <div className="pb-2">
      <FactorSection
        title="Funds and charges"
        empty=""
        rows={[
          { key: 'funds', label: 'Funds offered', note: null, value: terms.fundCodes.join(', ') || '—' },
          { key: 'fee', label: 'Monthly policy fee', note: null, value: amount(terms.monthlyPolicyFee) },
          { key: 'mortality', label: 'Mortality table', note: `${terms.mortality.length} rows`, value: terms.mortalityBasis === 'BY_SEX' ? 'By sex' : 'Unisex' },
          { key: 'death', label: 'On death pays', note: null,
            value: terms.deathRule === 'HIGHER_OF' ? 'The higher of sum assured and fund' : 'Sum assured plus fund' },
          { key: 'lapse', label: 'Lapses on', note: null, value: terms.lapseRule === 'NON_PAYMENT' ? 'Non-payment' : 'The fund running out' },
          { key: 'surrender', label: 'Surrender from year', note: null, value: String(terms.minimumSurrenderYears) },
          { key: 'multiple', label: 'Sum assured', note: 'times the annual premium',
            value: `${terms.sumAssuredMultipleMin}–${terms.sumAssuredMultipleMax}` },
        ]}
      />
      <FactorSection
        title="Allocation"
        empty="No bands."
        rows={terms.allocationBands.map((b) => ({
          key: String(b.fromYear),
          label: `Year ${b.fromYear}${b.toYear == null ? ' on' : `–${b.toYear}`}`,
          note: null,
          value: `${b.percent}% buys units`,
        }))}
      />
    </div>
  );
}
