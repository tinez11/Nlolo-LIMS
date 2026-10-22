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
      <SectionHeading hint="What each plan pays for each person it covers.">Plans and benefits</SectionHeading>
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
          <SectionHeading hint="The yearly premium for each life, by plan, role and age.">Premium table</SectionHeading>
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
        hint="Entry ages, when cover stops, and how many of each."
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
        hint="When a death is paid, and to whom."
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
        hint="The kinds of income a buyer can choose."
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
        hint="When income is paid, and the factor for paying it more often."
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

const FREQUENCY: Record<string, string> = { MONTHLY: 'Monthly', QUARTERLY: 'Quarterly', ANNUALLY: 'Annually', SINGLE: 'Single' };

/** "Year 1", "Years 2–5", "Year 6 and after". */
function years(from: number, to: number | null | undefined): string {
  if (to == null) return `Year ${from} and after`;
  return from === to ? `Year ${from}` : `Years ${from}–${to}`;
}

/** "Ages 18–39", "Ages 60 and over". */
function ages(from: number, to: number | null | undefined): string {
  return to == null ? `Ages ${from} and over` : `Ages ${from}–${to}`;
}

/**
 * A version's bands, read-only: the table it was published with, not a count of its rows (the user, 2026-10-22 --
 * "Mortality table, 1 rows" said nothing a reviewer could check).
 */
function BandsShown({ caption, columns, rows }: { caption: string; columns: string[]; rows: string[][] }) {
  return (
    <div className="px-4">
      <table className="w-full max-w-xl text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="bg-control text-xs text-muted-foreground">
            {columns.map((c, i) => (
              <th key={c} scope="col" className={i === 0 ? 'px-3 py-1.5 text-left font-medium' : 'px-3 py-1.5 text-right font-medium'}>{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, r) => (
            <tr key={r} className="border-b border-border">
              {row.map((cell, i) => (
                i === 0
                  ? <th key={i} scope="row" className="px-3 py-1.5 text-left font-normal">{cell}</th>
                  : <td key={i} className="px-3 py-1.5 text-right tabular-nums">{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function UnitLinkedTermsDetail({ terms }: { terms: UnitLinkedTermsView }) {
  const bySex = terms.mortalityBasis === 'BY_SEX';
  const options = terms.options;
  const multiple = terms.sumAssuredMultipleMin === terms.sumAssuredMultipleMax
    ? `${terms.sumAssuredMultipleMin}× the annual premium`
    : `${terms.sumAssuredMultipleMin}× to ${terms.sumAssuredMultipleMax}× the annual premium`;
  const optionRows = options ? [
    ...(options.freeSwitchesPerYear != null ? [{ key: 'switch', label: 'Fund switches', note: null,
      value: `${options.freeSwitchesPerYear} free a year, then ${amount(options.switchFee)} each` }] : []),
    ...(options.minimumWithdrawal != null ? [{ key: 'withdraw', label: 'Withdrawals',
      note: options.withdrawalReducesSumAssured ? 'a withdrawal reduces the sum assured' : null,
      value: `from ${amount(options.minimumWithdrawal)}, leaving at least ${amount(options.minimumRemainingValue)}` }] : []),
    ...(options.topUpAllocationPercent != null ? [{ key: 'topup', label: 'Top-ups', note: null,
      value: `from ${amount(options.minimumTopUp)}, ${options.topUpAllocationPercent}% buys units` }] : []),
  ] : [];
  const surrender = options?.surrenderCharges ?? [];

  return (
    <div className="pb-2">
      <FactorSection
        title="Funds and premiums"
        hint="What the policy invests in, what it must be paid, and the cover it buys."
        empty=""
        rows={[
          { key: 'funds', label: 'Funds offered', note: null, value: terms.fundCodes.join(', ') || '—' },
          { key: 'fee', label: 'Monthly policy fee', note: null,
            value: terms.monthlyPolicyFee > 0 ? amount(terms.monthlyPolicyFee) : 'None' },
          ...terms.premiumMinimums.map((m) => ({ key: `min-${m.frequency}`, label: `Minimum premium, ${(FREQUENCY[m.frequency] ?? m.frequency).toLowerCase()}`,
            note: null, value: amount(m.amount) })),
          { key: 'multiple', label: 'Sum assured', note: null, value: multiple },
        ]}
      />

      <section>
        <SectionHeading hint="The share of each premium that buys units; the rest is the allocation charge.">Allocation</SectionHeading>
        {terms.allocationBands.length === 0 ? <p className="px-4 text-xs text-muted-foreground">No bands.</p> : (
          <BandsShown caption="Allocation by policy year" columns={['Policy years', 'Buys units', 'Charge']}
            rows={terms.allocationBands.map((b) => [years(b.fromYear, b.toYear), `${b.percent}%`, `${AMOUNT.format(100 - b.percent)}%`])} />
        )}
      </section>

      <section>
        <SectionHeading hint={`A year's charge per 1,000 of the amount at risk, ${bySex ? 'by sex' : 'the same for women and men'}.`}>
          Cost of insurance
        </SectionHeading>
        {terms.mortality.length === 0 ? <p className="px-4 text-xs text-muted-foreground">No mortality bands.</p> : (
          <BandsShown caption="Mortality rates" columns={bySex ? ['Ages', 'Sex', 'Rate per 1,000'] : ['Ages', 'Rate per 1,000']}
            rows={terms.mortality.map((m) => [ages(m.ageFrom, m.ageTo),
              ...(bySex ? [m.sex === 'FEMALE' ? 'Female' : m.sex === 'MALE' ? 'Male' : '—'] : []),
              String(m.annualRatePerMille)])} />
        )}
      </section>

      <FactorSection
        title="Rules"
        hint="What death pays, when the policy lapses, and when it can be surrendered."
        empty=""
        rows={[
          { key: 'death', label: 'On death pays', note: null,
            value: terms.deathRule === 'HIGHER_OF' ? 'The higher of sum assured and fund' : 'Sum assured plus fund' },
          { key: 'lapse', label: 'Lapses on', note: null, value: terms.lapseRule === 'NON_PAYMENT' ? 'Non-payment' : 'The fund running out' },
          { key: 'warn', label: 'Low-fund warning', note: null,
            value: `Under ${terms.lowFundWarningMonths} months of charges left` },
          { key: 'surrender', label: 'Surrender', note: null,
            value: terms.minimumSurrenderYears > 0 ? `After ${terms.minimumSurrenderYears} years` : 'Any time' },
          ...(terms.minimumPremiumYears != null ? [{ key: 'minyears', label: 'Premiums required for', note: null,
            value: `${terms.minimumPremiumYears} years` }] : []),
        ]}
      />

      {optionRows.length > 0 && (
        <FactorSection title="Options" hint="Switching funds, taking money out, and paying in more." empty="" rows={optionRows} />
      )}

      {surrender.length > 0 && (
        <section>
          <SectionHeading hint="Taken on surrenders, withdrawals and a lapse; never on death, maturity or free-look.">
            Surrender charges
          </SectionHeading>
          <BandsShown caption="Surrender charge by policy year" columns={['Policy years', 'Charge']}
            rows={surrender.map((b) => [years(b.fromYear, b.toYear), `${b.percent}%`])} />
        </section>
      )}
    </div>
  );
}
