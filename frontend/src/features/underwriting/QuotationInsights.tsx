import { Legend } from '@/components/charts/ChartTip';
import { ColumnChart } from '@/components/charts/ColumnChart';
import { ShareBars } from '@/components/charts/ShareBars';
import { TrendChart, type Category } from '@/components/charts/TrendChart';
import { Panel } from '@/components/Panel';
import { FUNERAL_ROLE_LABELS, type FuneralRoleName } from '@/features/products/funeralSchema';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/dates';
import { formatMoney, type Money } from '@/lib/money';
import type { QuoteResult } from './quoteResult';
import { useAlternatives, type Alternative } from './useAlternatives';

/** A figure the quote endpoints still send as a JSON number, shown as money -- formatting, never arithmetic. */
const asMoney = (amount: number | string | undefined | null, currency = 'TZS'): Money | null =>
  amount === undefined || amount === null ? null : { amount: String(amount), currencyCode: currency };

/**
 * The quotation over time, and what else the customer could choose (2026-10-23). Shown under the calculator once a
 * quote comes back, at full width so a chart has room: the account growing year by year against what was paid in,
 * cover against premiums paid, an annuity's income against its price, a family's cover person by person -- then three
 * alternatives the server priced, and the figures year by year.
 *
 * <p>Every number is the server's: a schedule it projected, a total it worked out, an alternative it priced. Heights
 * and lengths are drawn from them; nothing is added, scaled or subtracted here.
 */
export function QuotationInsights({ result, alternatives }: { result: QuoteResult; alternatives: Alternative[] }) {
  const priced = useAlternatives(alternatives);
  const visual = renderVisual(result);
  return (
    <div className="grid gap-5 xl:grid-cols-12">
      {visual && <div className="min-w-0 xl:col-span-8">{visual}</div>}
      {alternatives.length > 0 && (
        <div className={cn('min-w-0', visual ? 'xl:col-span-4' : 'xl:col-span-12')}>
          <Panel id="compare-options" title="Compare options" subtitle="Each one priced for this customer">
            <ul className="grid gap-2.5 px-4 pb-4">
              {priced.map((p) => (
                <li
                  key={p.key}
                  className={cn(
                    'rounded-xl border px-4 py-3',
                    p.current ? 'border-accent bg-status-active-bg' : 'border-border',
                  )}
                >
                  <span className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
                    {p.label}
                    {p.current && <span className="font-semibold text-accent">Quoted above</span>}
                  </span>
                  <span className="mt-0.5 block text-title tabular-nums">
                    {p.state === 'priced' ? p.value : p.state === 'loading' ? 'Pricing…' : 'Not offered'}
                  </span>
                  {p.state === 'priced' && <span className="block text-xs text-muted-foreground">{p.note}</span>}
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      )}
      <Schedule result={result} />
    </div>
  );
}

/** The chart for this kind of quotation, or null where a picture would add nothing (an account's rate). */
function renderVisual(result: QuoteResult) {
  if (result.kind === 'savings') {
    const q = result.quote;
    const schedule = q.termAccount?.schedule ?? [];
    if (q.kind === 'TERM_ACCOUNT' && schedule.length > 0) {
      const last = schedule[schedule.length - 1]!;
      const categories: Category[] = schedule.map((s) => ({
        key: String(s.month),
        tick: s === last ? 'Maturity' : s.month % 12 === 0 ? `Year ${s.month / 12}` : `Month ${s.month}`,
        long: s === last ? `At maturity, ${formatDate(s.date)}` : `${s.month % 12 === 0 ? `End of year ${s.month / 12}` : `After ${s.month} months`}, ${formatDate(s.date)}`,
      }));
      return (
        <Panel title="How the account grows" subtitle="What it is worth against what has been paid in">
          <div className="space-y-3 px-4 pb-4">
            <Legend items={[
              { label: 'Account value', color: 'chart-1', line: true },
              { label: 'Paid in so far', color: 'chart-2', dashed: true },
            ]} />
            <TrendChart
              categories={categories}
              series={[
                { label: 'Account value', color: 'chart-1', area: true,
                  points: schedule.map((s) => ({ key: String(s.month), value: Number(s.value.amount), display: formatMoney(s.value) })) },
                { label: 'Paid in so far', color: 'chart-2', dashed: true,
                  points: schedule.map((s) => ({ key: String(s.month), value: Number(s.paidIn.amount), display: formatMoney(s.paidIn) })) },
              ]}
              description={`The account's value against what has been paid in, to ${formatMoney(last.value)} at maturity on ${formatDate(last.date)}.`}
            />
          </div>
        </Panel>
      );
    }
    if (q.kind === 'DEPOSIT' && q.maturityValue && q.interest) {
      return (
        <Panel title="What the deposit becomes" subtitle={q.maturityDate ? `On ${formatDate(q.maturityDate)}` : 'At the end of the term'}>
          <div className="px-4 pb-4">
            <ShareBars
              label="Deposit, interest and value at maturity"
              bars={[
                { key: 'deposit', label: 'Deposit', value: Number(q.amount?.amount ?? 0), display: formatMoney(q.amount), color: 'chart-2' },
                { key: 'interest', label: 'Interest for the term', value: Number(q.interest.amount), display: formatMoney(q.interest), color: 'chart-3' },
                { key: 'maturity', label: 'Paid at maturity', value: Number(q.maturityValue.amount), display: formatMoney(q.maturityValue), color: 'chart-1' },
              ]}
            />
          </div>
        </Panel>
      );
    }
    return null;
  }

  if (result.kind === 'premium') {
    const q = result.quote;
    const years = q.paidByYear ?? [];
    if (years.length === 0) return null;
    const cover = asMoney(result.sumAssured, result.currency);
    const categories = years.map((y) => ({ key: String(y.year), tick: `Year ${y.year}`, long: `By the end of year ${y.year}` }));
    return (
      <Panel title="Cover against what is paid" subtitle="The cover stays the same all term; premiums add up">
        <div className="space-y-3 px-4 pb-4">
          <Legend items={[
            { label: 'Paid to the family on a death', color: 'chart-2' },
            { label: 'Premiums paid so far', color: 'chart-3' },
          ]} />
          <ColumnChart
            categories={categories}
            series={[
              { label: 'Cover', color: 'chart-2', points: years.map((y) => ({ key: String(y.year), value: Number(result.sumAssured), display: formatMoney(cover) })) },
              { label: 'Premiums paid so far', color: 'chart-3', points: years.map((y) => ({ key: String(y.year), value: y.paid, display: formatMoney(asMoney(y.paid, result.currency)) })) },
            ]}
            description={`Cover of ${formatMoney(cover)} each year against premiums paid, which reach ${formatMoney(asMoney(q.totalOverTerm, result.currency))} over the term.`}
          />
          {q.totalOverTerm != null && (
            <p className="text-sm text-muted-foreground">
              Over the whole term the premiums add up to{' '}
              <b className="text-foreground">{formatMoney(asMoney(q.totalOverTerm, result.currency))}</b> in{' '}
              {q.instalmentsOverTerm} payments. The cover is {formatMoney(cover)} from the first day.
            </p>
          )}
        </div>
      </Panel>
    );
  }

  if (result.kind === 'funeral') {
    const q = result.quote;
    return (
      <Panel title="Who is covered, and for how much" subtitle="Paid to the family on a death, person by person">
        <div className="grid gap-6 px-4 pb-4 md:grid-cols-2">
          <div className="space-y-2">
            <p className="text-xs font-semibold text-muted-foreground">Paid on a death</p>
            <ShareBars
              label="Cover per person"
              bars={q.lines.map((l, i) => ({
                key: `${i}`,
                label: l.name || FUNERAL_ROLE_LABELS[l.role as FuneralRoleName],
                note: `${FUNERAL_ROLE_LABELS[l.role as FuneralRoleName]}, age ${l.age}`,
                value: l.benefit,
                display: formatMoney(asMoney(l.benefit)),
                color: l.role === 'MAIN_MEMBER' || l.role === 'SPOUSE' ? 'chart-2' : l.role === 'CHILD' ? 'chart-1' : 'chart-3',
              }))}
            />
          </div>
          <div className="space-y-2">
            <p className="text-xs font-semibold text-muted-foreground">What each person costs a year</p>
            <ShareBars
              label="Yearly premium per person"
              bars={q.lines.map((l, i) => ({
                key: `${i}`,
                label: l.name || FUNERAL_ROLE_LABELS[l.role as FuneralRoleName],
                value: l.yearlyPremium,
                display: formatMoney(asMoney(l.yearlyPremium)),
                color: 'chart-4',
              }))}
            />
          </div>
        </div>
      </Panel>
    );
  }

  // An annuity: the income it pays, year by year, against the price paid for it.
  const q = result.quote;
  if (q.schedule.length === 0) return null;
  const price = q.purchasePrice ?? asMoney(result.purchasePrice);
  const breakEven = q.schedule.find((s) => s.year === q.breakEvenYear);
  const categories = q.schedule.map((s) => ({ key: String(s.year), tick: `Age ${s.age}`, long: `By the end of year ${s.year}, at age ${s.age}` }));
  return (
    <Panel title="Income received against the price" subtitle="What the annuity has paid by each year end, for as long as the annuitant lives">
      <div className="space-y-3 px-4 pb-4">
        <Legend items={[
          { label: 'Income received so far', color: 'chart-1', line: true },
          { label: 'Purchase price', color: 'chart-3', dashed: true },
        ]} />
        <TrendChart
          categories={categories}
          series={[
            { label: 'Income received so far', color: 'chart-1', area: true,
              points: q.schedule.map((s) => ({ key: String(s.year), value: Number(s.received.amount), display: formatMoney(s.received) })) },
            { label: 'Purchase price', color: 'chart-3', dashed: true,
              points: q.schedule.map((s) => ({ key: String(s.year), value: Number(price?.amount ?? 0), display: formatMoney(price) })) },
          ]}
          description={`Income received by each year end against the purchase price of ${formatMoney(price)}.`}
        />
        {breakEven && (
          <p className="text-sm text-muted-foreground">
            By the end of year {breakEven.year}, at age {breakEven.age}, the income received has passed the price
            paid: <b className="text-foreground">{formatMoney(breakEven.received)}</b>. Every payment after that is
            more than was put in.
          </p>
        )}
      </div>
    </Panel>
  );
}

/** The figures behind the chart, row by row -- the table a customer can check the chart against. */
function Schedule({ result }: { result: QuoteResult }) {
  if (result.kind === 'savings' && result.quote.kind === 'TERM_ACCOUNT') {
    const schedule = result.quote.termAccount?.schedule ?? [];
    if (schedule.length === 0) return null;
    return (
      <div className="min-w-0 xl:col-span-12">
        <Panel title="Year by year" subtitle="Running totals from the first payment to maturity">
          <div className="max-h-96 overflow-auto">
            <table className="w-full text-sm tabular-nums">
              <thead className="sticky top-0 bg-band text-xs text-muted-foreground">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">When</th>
                  <th className="px-4 py-2.5 text-right font-medium">Paid in</th>
                  <th className="px-4 py-2.5 text-right font-medium">Charges</th>
                  <th className="px-4 py-2.5 text-right font-medium">Interest</th>
                  <th className="px-4 py-2.5 text-right font-medium">Account value</th>
                </tr>
              </thead>
              <tbody>
                {schedule.map((s, i) => {
                  const last = i === schedule.length - 1;
                  return (
                    <tr key={s.month} className={cn('border-t border-border', last && 'font-bold')}>
                      <td className="px-4 py-2 whitespace-nowrap">
                        {last ? 'At maturity' : s.month % 12 === 0 ? `End of year ${s.month / 12}` : `After ${s.month} months`}
                        <span className="block text-xs font-normal text-muted-foreground">{formatDate(s.date)}</span>
                      </td>
                      <td className="px-4 py-2 text-right whitespace-nowrap">{formatMoney(s.paidIn)}</td>
                      <td className="px-4 py-2 text-right whitespace-nowrap">{formatMoney(s.charges)}</td>
                      <td className="px-4 py-2 text-right whitespace-nowrap">{formatMoney(s.interest)}</td>
                      <td className="px-4 py-2 text-right whitespace-nowrap">{formatMoney(s.value)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    );
  }
  return null;
}
