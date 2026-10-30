import type { SubmissionStatus } from '@/api/types';
import { Field } from '@/components/Field';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatMoney, NO_VALUE } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectQuotation, useCreditLifeStore } from '@/store/creditLifeStore';
import { isPositive, trimZeros } from './creditLifeAgreementForm';

/**
 * What a lender's file costs, shown with the file before anybody accepts it (2026-10-30).
 *
 * The answer leads on the figures band -- the amount to pay, the lender's commission and the borrowers covered --
 * every one of them the server's. Beneath: the total lent, the lender's own premium beside ours and the difference the
 * server worked out, and the rows where the two disagree, so the person accepting can tell the lender which.
 *
 * Priced exactly as acceptance prices it, so this is the invoice. A withdrawn file shows none: it charges nothing.
 */
export function EnrolmentQuotation({ submissionId, status }: { submissionId: string; status: SubmissionStatus }) {
  const quotation = useCreditLifeStore(selectQuotation(submissionId));

  if (status === 'WITHDRAWN') return null;
  if (isInitialLoad(quotation)) return <LoadingBlock label="Pricing this file" />;
  if (quotation.error && !quotation.data) return <ErrorPanel error={quotation.error} />;
  const q = quotation.data;
  if (!q) return null;

  const money = (amount: string | null | undefined) =>
    amount == null ? NO_VALUE : formatMoney({ amount, currencyCode: q.currency });
  const accepted = status === 'ACCEPTED';
  const commissionLabel = q.commissionPercent
    ? `Lender's commission (${trimZeros(q.commissionPercent)}%)`
    : "Lender's commission";

  return (
    <div className="space-y-4 border-t border-border p-4">
      <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-xl bg-hero p-5 sm:grid-cols-3">
        <Field
          stacked
          label={accepted ? 'Charged' : 'Amount to pay'}
          value={money(q.amountToPay)}
          note={accepted ? 'Invoiced to the lender when the file was accepted.' : 'The full premium, invoiced on acceptance.'}
        />
        <Field
          stacked
          label={commissionLabel}
          value={money(q.commission)}
          note={isPositive(q.commission) ? 'Paid to the lender after the premium is received.' : 'No agreement states one.'}
        />
        <Field
          stacked
          label={accepted ? 'Borrowers covered' : 'Borrowers to cover'}
          value={String(q.borrowers)}
          note="Refused rows are in none of these figures."
        />
      </dl>

      <dl>
        <Field label="Total lent" value={money(q.totalLoanAmount)} />
        <Field
          label="The lender's own premium"
          value={q.statedPremium == null ? 'Not in their file' : money(q.statedPremium)}
          {...(q.statedPremium == null
            ? { note: 'Their file had no premium column, so there is nothing to reconcile.' }
            : {})}
        />
        {q.difference != null && (
          <Field
            label="Difference"
            value={
              isPositive(q.difference) || q.difference.startsWith('-') ? (
                <span className="text-status-warning-fg">{money(q.difference)}</span>
              ) : (
                <span className="text-status-success-fg">Matches to the cent</span>
              )
            }
            {...(q.difference.startsWith('-')
              ? { note: 'The lender worked out more than they are charged.' }
              : isPositive(q.difference)
                ? { note: 'The lender is charged more than their file says.' }
                : {})}
          />
        )}
      </dl>

      {q.mismatches.length > 0 && (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-sm">
            <caption className="px-4 pt-3 text-left text-xs font-medium">
              Rows where the lender&rsquo;s premium is not ours
            </caption>
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th scope="col" className="px-4 py-2 font-medium">Row</th>
                <th scope="col" className="px-4 py-2 font-medium">Borrower</th>
                <th scope="col" className="px-4 py-2 text-right font-medium">Their premium</th>
                <th scope="col" className="px-4 py-2 text-right font-medium">Ours</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {q.mismatches.map((m) => (
                <tr key={m.lineNumber}>
                  <td className="px-4 py-2 text-muted-foreground">{m.lineNumber}</td>
                  <td className="px-4 py-2">{m.borrowerFullName ?? NO_VALUE}</td>
                  <td className="px-4 py-2 text-right">{money(m.statedPremium)}</td>
                  <td className="px-4 py-2 text-right">{money(m.premium)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
