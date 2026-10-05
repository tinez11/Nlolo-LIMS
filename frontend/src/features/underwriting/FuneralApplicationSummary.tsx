import type { FuneralApplicationView } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { FUNERAL_ROLE_LABELS, type FuneralRoleName } from '@/features/products/funeralSchema';

const tzs = (amount: number) => formatMoney({ amount: String(amount), currencyCode: 'TZS' });

/** A funeral case's plan and family, priced today by the server (family funeral cover). */
export function FuneralApplicationSummary({ application }: { application: FuneralApplicationView }) {
  const quote = application.quote;
  return (
    <div className="space-y-2 text-sm">
      <p>Plan <span className="font-medium">{application.planCode}</span></p>
      {quote ? (
        <table className="w-full">
          <caption className="sr-only">Funeral plan lives</caption>
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="py-1">Life</th><th className="py-1">Role</th><th className="py-1">Age</th>
              <th className="py-1">Benefit</th><th className="py-1">Yearly premium</th>
            </tr>
          </thead>
          <tbody>
            {quote.lines.map((line, i) => (
              <tr key={i} className="border-t border-border">
                <td className="py-1">{line.name}</td>
                <td className="py-1">{FUNERAL_ROLE_LABELS[line.role as FuneralRoleName]}</td>
                <td className="py-1">{line.age}</td>
                <td className="py-1">{tzs(line.benefit)}</td>
                <td className="py-1">{tzs(line.yearlyPremium)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t border-border font-medium">
              <td className="py-1" colSpan={4}>Each {quote.frequency.toLowerCase()} payment</td>
              <td className="py-1">{tzs(quote.instalment)}</td>
            </tr>
          </tfoot>
        </table>
      ) : (
        <p role="alert" className="text-xs text-status-danger-fg">
          This family no longer prices on the plan — a life has aged out of entry since it was recorded. Record the
          application again before accepting.
        </p>
      )}
    </div>
  );
}
