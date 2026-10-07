import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { ExpenseAllocationView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useExpenseAllocationStore } from '@/store/expenseAllocationStore';
import { ALLOCATION_STATUS_LABEL, CATEGORY_LABEL, DRIVER_LABEL, money, parseAmount } from './expenseAllocation';

type Totals = { maintenance: string; claimsHandling: string; acquisition: string };
const EMPTY: Totals = { maintenance: '', claimsHandling: '', acquisition: '' };

/**
 * Month-end step 5 on the IFRS 17 engine page (IFRS 17 I5b, P-19): the month's expense allocations, and the form that
 * prepares one -- three totals from the expense study, previewed against the month's pool and split over the groups as
 * they would post -- or records that there is none this month. A finance approver decides it on its own page.
 */
export function ExpenseAllocationSection({ period }: { period: string }) {
  const allocations = useExpenseAllocationStore((s) => s.allocations);
  const preview = useExpenseAllocationStore((s) => s.preview);
  const previewTotals = useExpenseAllocationStore((s) => s.previewTotals);
  const clearPreview = useExpenseAllocationStore((s) => s.clearPreview);
  const prepare = useExpenseAllocationStore((s) => s.prepare);
  const preparing = useExpenseAllocationStore((s) => s.acting[`prepare.${period}`]);
  const [totals, setTotals] = useState<Totals>(EMPTY);
  const [study, setStudy] = useState('');
  const [note, setNote] = useState('');
  const [none, setNone] = useState(false);
  const [nilReason, setNilReason] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const parsed = {
    maintenance: parseAmount(totals.maintenance),
    claimsHandling: parseAmount(totals.claimsHandling),
    acquisition: parseAmount(totals.acquisition),
  };
  const valid = parsed.maintenance !== null && parsed.claimsHandling !== null && parsed.acquisition !== null;
  const total = valid ? (parsed.maintenance ?? 0) + (parsed.claimsHandling ?? 0) + (parsed.acquisition ?? 0) : 0;
  const pending = (allocations.data ?? []).some((a) => a.status === 'PREPARED');

  /** Typing a total asks for the split after a pause -- from the handler, not an effect. */
  function change(field: keyof Totals, value: string) {
    const next = { ...totals, [field]: value };
    setTotals(next);
    if (timer.current) clearTimeout(timer.current);
    const m = parseAmount(next.maintenance);
    const c = parseAmount(next.claimsHandling);
    const a = parseAmount(next.acquisition);
    if (m === null || c === null || a === null || m + c + a === 0) {
      clearPreview();
      return;
    }
    timer.current = setTimeout(() => void previewTotals(period, m, c, a), 400);
  }

  async function submit() {
    const ok = await prepare(
      period,
      none
        ? { maintenance: 0, claimsHandling: 0, acquisition: 0, nilReason: nilReason.trim() }
        : {
            maintenance: parsed.maintenance ?? 0,
            claimsHandling: parsed.claimsHandling ?? 0,
            acquisition: parsed.acquisition ?? 0,
            studyReference: study.trim(),
            note: note.trim() === '' ? null : note.trim(),
          },
    );
    if (ok) {
      setTotals(EMPTY);
      setStudy('');
      setNote('');
      setNone(false);
      setNilReason('');
    }
  }

  const canSubmit = none ? nilReason.trim() !== '' : valid && total > 0 && study.trim() !== '';

  return (
    <section className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="Expense allocation">
      <p className="text-xs font-medium">Expense allocation (step 5)</p>
      {isInitialLoad(allocations) ? (
        <LoadingBlock />
      ) : (allocations.data ?? []).length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No allocation yet. The attributable part of the month's expenses moves into 5210, 5215 and acquisition cash flows.
        </p>
      ) : (
        <AllocationList allocations={allocations.data ?? []} />
      )}

      {!pending && (
        <form
          className="space-y-3"
          aria-label="Prepare expense allocation"
          onSubmit={(e) => {
            e.preventDefault();
            if (canSubmit) void submit();
          }}
        >
          <CheckboxField label="No allocation this month" checked={none} onChange={(e) => setNone(e.target.checked)} />
          {none ? (
            <div className="max-w-xl">
              <FormField label="Reason there is none">
                <Input inputSize="sm" value={nilReason} onChange={(e) => setNilReason(e.target.value)} />
              </FormField>
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-end gap-3">
                {(
                  [
                    ['maintenance', 'Maintenance'],
                    ['claimsHandling', 'Claims handling'],
                    ['acquisition', 'Acquisition'],
                  ] as const
                ).map(([field, label]) => (
                  <div key={field} className="w-40">
                    <FormField
                      label={label}
                      error={parseAmount(totals[field]) === null ? 'Up to two decimals, not negative' : undefined}
                    >
                      <Input
                        inputSize="sm"
                        inputMode="decimal"
                        placeholder="0.00"
                        value={totals[field]}
                        onChange={(e) => change(field, e.target.value)}
                      />
                    </FormField>
                  </div>
                ))}
                <div className="w-56">
                  <FormField label="Study reference">
                    <Input inputSize="sm" value={study} onChange={(e) => setStudy(e.target.value)} />
                  </FormField>
                </div>
                <div className="w-72">
                  <FormField label="Note">
                    <Input inputSize="sm" value={note} onChange={(e) => setNote(e.target.value)} />
                  </FormField>
                </div>
              </div>
              {total > 0 && preview.data && <Preview />}
              {preview.status === 'error' && preview.error && <InlineError error={preview.error} />}
            </>
          )}
          {preparing?.status === 'error' && preparing.error && <InlineError error={preparing.error} />}
          <Button type="submit" size="sm" variant="primary" disabled={!canSubmit || preparing?.status === 'loading'}>
            {none ? 'Record no allocation' : 'Prepare allocation'}
          </Button>
        </form>
      )}
      {pending && (
        <p className="text-sm text-muted-foreground">An allocation awaits a decision; a new one can be prepared after it.</p>
      )}
    </section>
  );
}

function AllocationList({ allocations }: { allocations: ExpenseAllocationView[] }) {
  return (
    <table className="w-full text-sm" aria-label="Expense allocations">
      <tbody>
        {allocations.map((a) => (
          <tr key={a.allocationId} className="border-t border-border">
            <td className="py-1 pr-3">
              <Link className="font-medium underline-offset-2 hover:underline" to={`allocations/${a.allocationId}`}>
                {a.period} · {money(a.total)}
              </Link>
            </td>
            <td className="py-1 pr-3">{ALLOCATION_STATUS_LABEL[a.status] ?? a.status}</td>
            <td className="py-1 pr-3 text-xs text-muted-foreground">
              {a.nilReason ? `None this month: ${a.nilReason}` : a.studyReference}
            </td>
            <td className="py-1 text-xs text-muted-foreground">
              {formatInstant(a.preparedAt)} by {a.preparedBy}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Preview() {
  const preview = useExpenseAllocationStore((s) => s.preview.data);
  if (!preview) return null;
  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">
        Pool {money(preview.pool)} · total {money(preview.total)}
      </p>
      {preview.overPool && (
        <p role="status" className="text-sm text-status-danger-fg">
          The total is above the month's pool of {money(preview.pool)}; the approver must approve above the pool.
        </p>
      )}
      <table className="w-full text-sm" aria-label="Allocation preview">
        <thead>
          <tr className="text-left text-xs text-muted-foreground">
            <th className="py-1 pr-3 font-normal">Group</th>
            <th className="py-1 pr-3 font-normal">Category</th>
            <th className="py-1 pr-3 font-normal">Account</th>
            <th className="py-1 pr-3 font-normal">Shared by</th>
            <th className="py-1 text-right font-normal">Amount</th>
          </tr>
        </thead>
        <tbody>
          {preview.lines.map((l) => (
            <tr key={`${l.group}:${l.category}`} className="border-t border-border">
              <td className="py-1 pr-3 font-mono text-xs">{l.group}</td>
              <td className="py-1 pr-3">{CATEGORY_LABEL[l.category] ?? l.category}</td>
              <td className="py-1 pr-3 font-mono">{l.account}</td>
              <td className="py-1 pr-3 text-xs text-muted-foreground">
                {l.driverCount} {DRIVER_LABEL[l.driver] ?? l.driver}
              </td>
              <td className="py-1 text-right tabular-nums">{money(l.amount)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
