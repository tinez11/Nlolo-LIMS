import type { YearEndCloseView } from '@/api/types';
import { CLASS_LABEL, money, resultLabel } from './yearEnd';

/**
 * A year's close in figures (IFRS 17 I6): the accounts closed with their balances for the year, the totals per class,
 * the result and dividends, and the closing journal -- as it would post now, or as it posted.
 */
export function YearEndFigures({ close }: { close: YearEndCloseView }) {
  return (
    <div className="space-y-4">
      <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Result">
        <p className="text-sm font-medium">{resultLabel(close.profit)}</p>
        <p className="text-xs text-muted-foreground">
          Dividends declared {money(close.dividends)} · retained earnings move by {money(close.profit - close.dividends)}
        </p>
        <ul className="text-xs text-muted-foreground">
          {Object.entries(close.classTotals).map(([cls, total]) => (
            <li key={cls}>
              Class {cls} {CLASS_LABEL[cls] ? `(${CLASS_LABEL[cls]})` : ''}: {money(total)} net Dr − Cr
            </li>
          ))}
        </ul>
      </section>

      <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Accounts">
        <p className="text-xs font-medium">Accounts closed (balance for the year, net Dr − Cr)</p>
        <table className="w-full text-sm" aria-label="Accounts closed">
          <tbody>
            {close.accounts.map((a) => (
              <tr key={a.code} className="border-t border-border">
                <td className="w-16 py-1 pr-3 font-mono">{a.code}</td>
                <td className="py-1 pr-3">{a.name}</td>
                <td className="py-1 text-right tabular-nums">{money(a.balance)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Journal">
        <p className="text-xs font-medium">The closing journal, in December</p>
        <table className="w-full text-sm" aria-label="Closing journal">
          <tbody>
            {close.lines.map((l, i) => (
              <tr key={`${l.account}:${l.side}:${i}`} className="border-t border-border">
                <td className="w-12 py-1 pr-3 font-mono">{l.side === 'DR' ? 'Dr' : 'Cr'}</td>
                <td className="w-16 py-1 pr-3 font-mono">{l.account}</td>
                <td className="py-1 text-right tabular-nums">{money(l.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
