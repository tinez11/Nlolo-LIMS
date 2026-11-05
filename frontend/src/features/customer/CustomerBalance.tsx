import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import {
  getCustomerStatement,
  requestCustomerWithdrawal,
  type CustomerPolicyView,
  type CustomerStatementView,
} from '@/api/portal';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { money } from './customerText';
import { useWords } from './words';

/** How many statement lines one page brings. */
const PAGE = 10;
const AMOUNT = /^\d+(\.\d{1,2})?$/;

type Balance = NonNullable<CustomerPolicyView['balance']>;

/**
 * The policy's balance as its holder reads it (money movement step 5, design §5.2): what is on it and how far ahead it
 * pays premiums -- both the server's -- and a request for money back to their phone, which staff approve.
 */
export function CustomerBalanceCard({ policyNumber, balance, card }: {
  policyNumber: string;
  balance: Balance;
  card: (title: string, lead: string | undefined, children: ReactNode) => ReactNode;
}) {
  const w = useWords().portal.policy;
  const [asking, setAsking] = useState(false);
  const [amount, setAmount] = useState('');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [sent, setSent] = useState(false);
  const valid = AMOUNT.test(amount.trim()) && Number(amount) > 0 && phone.trim() !== '';

  async function send(e: FormEvent) {
    e.preventDefault();
    if (!valid) return;
    setBusy(true);
    setError(null);
    try {
      await requestCustomerWithdrawal(policyNumber, amount.trim(), phone.trim());
      setSent(true);
      setAsking(false);
    } catch (cause) {
      setError(toApiError(cause));
    } finally {
      setBusy(false);
    }
  }

  const hasMoney = Number(balance.amount) > 0;
  return card(w.moneyTitle, undefined, (
    <div className="space-y-3">
      <dl className="divide-y divide-border">
        <div className="flex flex-wrap justify-between gap-x-4 gap-y-0.5 py-2.5">
          <dt className="text-muted-foreground">{w.moneyNow}</dt>
          <dd className="text-right font-medium tabular-nums">{money(balance.amount, balance.currency)}</dd>
        </div>
        <div className="py-2.5 text-sm text-muted-foreground">
          {balance.coversPremiumsTo ? w.coversTo(formatDate(balance.coversPremiumsTo)) : w.noPremiumAhead}
        </div>
      </dl>
      {sent && <p className="text-sm" role="status">{w.sent}</p>}
      {hasMoney && !asking && !sent && (
        <Button variant="outline" onClick={() => setAsking(true)}>{w.withdraw}</Button>
      )}
      {asking && (
        <form className="space-y-3" onSubmit={(e) => void send(e)} aria-label={w.withdraw}>
          <p className="text-sm text-muted-foreground">{w.withdrawLead}</p>
          <label className="block space-y-1">
            <span className="text-sm font-medium">{w.amount} ({balance.currency})</span>
            <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <label className="block space-y-1">
            <span className="text-sm font-medium">{w.phone}</span>
            <Input inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />
          </label>
          {error && <InlineError error={error} />}
          <div className="flex gap-2">
            <Button type="submit" variant="primary" disabled={!valid} pending={busy}>{w.send}</Button>
            <Button type="button" variant="ghost" onClick={() => setAsking(false)}>{w.cancel}</Button>
          </div>
        </form>
      )}
    </div>
  ));
}

/** The balance's statement, newest first, a page at a time: what each line was, its amount, and the balance after it. */
export function CustomerStatementCard({ policyNumber, card }: {
  policyNumber: string;
  card: (title: string, lead: string | undefined, children: ReactNode) => ReactNode;
}) {
  const w = useWords().portal.policy;
  const [statement, setStatement] = useState<CustomerStatementView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [pages, setPages] = useState(1);

  useEffect(() => {
    let live = true;
    getCustomerStatement(policyNumber, 0, PAGE * pages).then(
      (s) => { if (live) { setStatement(s); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [policyNumber, pages]);

  return card(w.statement, w.statementLead, (
    <>
      {error && <InlineError error={error} />}
      {statement && statement.lines.length === 0 && <p className="text-sm text-muted-foreground">{w.nothingYet}</p>}
      {statement && statement.lines.length > 0 && (
        <ul className="divide-y divide-border">
          {statement.lines.map((l) => (
            <li key={l.entryId} className="flex flex-wrap items-baseline justify-between gap-2 py-3">
              <span>
                <span className="font-medium">{w.entry[l.type] ?? l.type}</span>
                <span className="block text-sm text-muted-foreground">{formatDate(l.at)}</span>
              </span>
              <span className="text-right tabular-nums">
                <span className="font-semibold">{money(l.amount, statement.currency)}</span>
                <span className="block text-sm text-muted-foreground">{money(l.balanceAfter, statement.currency)}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
      {statement && statement.lines.length < statement.total && (
        <Button variant="ghost" onClick={() => setPages((p) => p + 1)}>{w.older}</Button>
      )}
    </>
  ));
}
