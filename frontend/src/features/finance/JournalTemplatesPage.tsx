import { useEffect } from 'react';
import type { JournalTemplateView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useManualJournalsStore } from '@/store/manualJournalsStore';

/**
 * The manual journal templates (IFRS 17 I4): the guide's Part 4 entries (equity, investments, operating costs,
 * intermediaries, reinsurance statements) and the tenant's saved recurring ones. An entry the guide puts on an
 * automatic (AUTO) account is shown with where it comes from instead -- it cannot be a manual journal.
 */
export function JournalTemplatesPage() {
  const templates = useManualJournalsStore((s) => s.templates);
  const loadTemplates = useManualJournalsStore((s) => s.loadTemplates);

  useEffect(() => {
    void loadTemplates();
  }, [loadTemplates]);

  function renderBody() {
    if (isInitialLoad(templates)) return <LoadingBlock />;
    if (templates.status === 'error' && templates.error && templates.data === null) {
      return <ErrorPanel error={templates.error} onRetry={() => void loadTemplates()} />;
    }
    const all = templates.data ?? [];
    const saved = all.filter((t) => t.source === 'SAVED');
    const guide = all.filter((t) => t.source === 'GUIDE');
    return (
      <>
        {saved.length > 0 && <TemplateTable label="Saved templates" rows={saved} />}
        <TemplateTable label="The guide's manual entries" rows={guide} />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Journal templates"
        description="Start a manual journal from one of these: accounts and sides are filled in, you enter the amounts."
      />
      <div className="space-y-4 px-6 pb-6">{renderBody()}</div>
    </>
  );
}

function TemplateTable({ label, rows }: { label: string; rows: JournalTemplateView[] }) {
  return (
    <section className="rounded-lg border border-border bg-surface p-4" aria-label={label}>
      <div className="overflow-x-auto">
        <table className="w-full text-sm" aria-label={label}>
          <caption className="pb-1 text-left text-xs font-medium">{label}</caption>
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="w-20 py-1 pr-3 font-normal">Entry</th>
              <th className="py-1 pr-3 font-normal">What</th>
              <th className="py-1 font-normal">Lines</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id} className="border-t border-border align-top">
                <td className="py-1 pr-3 font-mono text-xs">{t.source === 'GUIDE' ? t.id : ''}</td>
                <td className="py-1 pr-3">
                  {t.title}
                  {t.when && <p className="text-xs text-muted-foreground">{t.when}</p>}
                  {t.postedBy && <p className="text-xs text-status-warning-fg">Posted by the system: {t.postedBy}</p>}
                </td>
                <td className="py-1 text-xs">
                  {t.lines.map((l, i) => (
                    <div key={i}>
                      {l.side === 'DR' ? 'Dr' : 'Cr'} <span className="font-mono">{l.accountCode}</span>{' '}
                      <span className="text-muted-foreground">{l.accountName ?? ''}</span>
                    </div>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
