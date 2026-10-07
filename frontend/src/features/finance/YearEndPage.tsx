import { useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useYearEndStore } from '@/store/yearEndStore';
import { CLOSE_STATUS_LABEL, lastYear, resultLabel } from './yearEnd';
import { YearEndFigures } from './YearEndFigures';

/**
 * The year-end close (IFRS 17 I6, guide 5.7): a year's class 4-8 accounts and the journal that closes them to retained
 * earnings, prepared here by finance once December is closing; a finance approver who did not prepare it approves it on
 * its own page. December locks only once its year is closed.
 */
export function YearEndPage() {
  const [params, setParams] = useSearchParams();
  const yearText = params.get('year') ?? String(lastYear());
  const year = /^\d{4}$/.test(yearText) ? Number(yearText) : null;
  const preview = useYearEndStore((s) => s.preview);
  const closes = useYearEndStore((s) => s.closes);
  const loadYear = useYearEndStore((s) => s.loadYear);
  const prepare = useYearEndStore((s) => s.prepare);
  const preparing = useYearEndStore((s) => (year ? s.acting[`prepare.${year}`] : undefined));
  const pending = (closes.data ?? []).some((c) => c.status === 'PREPARED');

  useEffect(() => {
    if (year) void loadYear(year);
  }, [year, loadYear]);

  return (
    <>
      <PageHeader
        title="Year-end close"
        description="Classes 4–8 closed to 3310, the result to retained earnings (M-07), dividends declared too (M-11). December locks only once its year is closed."
      />
      <div className="space-y-4 px-6 pb-6">
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-32">
            <FormField label="Year">
              <Input inputSize="sm" value={yearText} placeholder="YYYY" onChange={(e) => setParams({ year: e.target.value.trim() })} />
            </FormField>
          </div>
          <Button type="button" size="sm" variant="primary"
            disabled={!year || pending || preparing?.status === 'loading' || (preview.data?.lines.length ?? 0) === 0}
            onClick={() => year && void prepare(year)}>
            Prepare close
          </Button>
        </div>
        {preparing?.status === 'error' && preparing.error && <InlineError error={preparing.error} />}

        {(closes.data ?? []).length > 0 && (
          <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Closes">
            <table className="w-full text-sm" aria-label="Year-end closes">
              <tbody>
                {(closes.data ?? []).map((c) => (
                  <tr key={c.closeId ?? ''} className="border-t border-border">
                    <td className="py-1 pr-3">
                      <Link className="font-medium underline-offset-2 hover:underline" to={`closes/${c.closeId}`}>
                        Close of {c.year} · {CLOSE_STATUS_LABEL[c.status ?? ''] ?? c.status}
                      </Link>
                    </td>
                    <td className="py-1 pr-3">{resultLabel(c.profit)}</td>
                    <td className="py-1 text-xs text-muted-foreground">
                      {c.preparedAt ? formatInstant(c.preparedAt) : ''} by {c.preparedBy}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}

        {!year ? (
          <p className="text-sm text-muted-foreground">A year is four digits, for example 2026.</p>
        ) : isInitialLoad(preview) ? (
          <LoadingBlock />
        ) : preview.status === 'error' && preview.error ? (
          <InlineError error={preview.error} />
        ) : preview.data && preview.data.lines.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing to close in {year}: no class 4–8 or dividend postings.</p>
        ) : (
          preview.data && <YearEndFigures close={preview.data} />
        )}
      </div>
    </>
  );
}
