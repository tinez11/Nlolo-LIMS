import { TriangleAlert } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import {
  listSolvencyFactorSets,
  publishSolvencyFactorSet,
  type SolvencyFactorSet,
  type SolvencyFactors,
} from '@/api/reporting';
import { can } from '@/auth/access';
import { DataTable, type Column } from '@/components/DataTable';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';

type Charge = SolvencyFactors['insurance'][number];
type Shock = SolvencyFactors['shocks'][number];

const RISKS = [
  { key: 'insurance', title: 'Insurance risk', subtitle: 'A rate on insurance contract liabilities, by portfolio.' },
  { key: 'market', title: 'Market risk', subtitle: 'A rate on each class of investment.' },
  { key: 'credit', title: 'Credit risk', subtitle: 'A rate on what others owe the company.' },
] as const;

const COMBINATION = {
  ROOT_SUM_OF_SQUARES: '√(insurance² + market² + credit²) + operational',
  SIMPLE_SUM: 'insurance + market + credit + operational',
} as const;

const SHOCK_KIND = { ASSETS: 'Moves account balances', INSURANCE: 'Scales the insurance-risk charge', LOSS: 'A loss on capital' } as const;

/**
 * The solvency factors (2026-11-09, the reports redesign batch 3, decision D5): the risk charges, thresholds and stress
 * shocks the capital adequacy, stress test and forecast reports are worked out with. Read by anyone who reads
 * reports; a new version published by someone holding reports.solvency-factors. A version is never edited -- a past
 * month re-runs on the version in force at its end -- so changing a factor means publishing the next version.
 */
export function SolvencyFactorsPage() {
  const [sets, setSets] = useState<SolvencyFactorSet[] | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);

  async function load() {
    setLoadError(null);
    try {
      setSets(await listSolvencyFactorSets());
    } catch (e) {
      setLoadError(toApiError(e));
    }
  }

  useEffect(() => {
    let live = true;
    listSolvencyFactorSets()
      .then((s) => live && setSets(s))
      .catch((e: unknown) => live && setLoadError(toApiError(e)));
    return () => {
      live = false;
    };
  }, []);

  if (loadError) return <div className="p-6"><ErrorPanel error={loadError} onRetry={() => void load()} /></div>;
  if (!sets) return <div className="p-6"><TableSkeleton columns={4} /></div>;

  // The version a report run today uses: the newest already in force, else the built-in set (always last).
  const today = new Date().toISOString().slice(0, 10);
  const current = sets.find((s) => s.effectiveFrom <= today) ?? sets[sets.length - 1]!;
  const mayPublish = can('reports.solvency-factors');

  return (
    <>
      <PageHeader
        title="Solvency factors"
        breadcrumb={[{ label: 'Reports', to: '/staff/reports' }]}
        description="The risk charges, thresholds and stress shocks the solvency reports are worked out with."
        actions={mayPublish && !draft ? (
          <Button size="sm" variant="primary" onClick={() => setDraft(toDraft(current))}>New version</Button>
        ) : undefined}
      />
      <div className="space-y-4 px-6 pb-8">
        {draft ? (
          <DraftForm
            draft={draft}
            onChange={setDraft}
            onCancel={() => setDraft(null)}
            onPublished={() => {
              setDraft(null);
              void load();
            }}
          />
        ) : (
          <>
            {current.placeholder && (
              <p className="flex items-start gap-2 rounded-lg bg-status-warning-bg px-3 py-2 text-sm text-status-warning-fg">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
                Placeholder risk charges, not TIRA&apos;s. Every solvency report says so until a version with the
                regulator&apos;s factors is published.
              </p>
            )}
            <FactorTables set={current} />
          </>
        )}
        <Versions sets={sets} />
      </div>
    </>
  );
}

/** The factors in force, read-only: what each risk charges and on which accounts. */
function FactorTables({ set }: { set: SolvencyFactorSet }) {
  const f = set.factors;
  const chargeColumns: Column<Charge>[] = [
    { key: 'label', header: 'Charge', render: (c) => c.label },
    { key: 'accounts', header: 'Accounts', render: (c) => c.accounts.join(', ') },
    { key: 'percent', header: 'Rate', align: 'right', render: (c) => `${c.percent}%` },
  ];
  return (
    <>
      <p className="text-sm text-muted-foreground">
        In use: <span className="font-semibold text-foreground">{set.name}</span>
        {set.version === 0 ? '' : `, version ${set.version}, from ${set.effectiveFrom}`}.
        Required capital = {COMBINATION[f.combination]}.
      </p>
      <div className="grid gap-4 xl:grid-cols-3">
        {RISKS.map((r) => (
          <Panel key={r.key} title={r.title} subtitle={r.subtitle}>
            <DataTable columns={chargeColumns} rows={f[r.key]} rowKey={(c) => c.key} caption={r.title} />
          </Panel>
        ))}
      </div>
      <div className="grid gap-4 xl:grid-cols-2">
        <Panel title="Operational risk and capital" subtitle="What is charged on revenue, and what is not capital.">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 px-4 pb-4 text-sm">
            <dt className="text-muted-foreground">Operational risk</dt>
            <dd>{f.operationalPercent}% of insurance revenue over the last 12 months</dd>
            <dt className="text-muted-foreground">Not capital</dt>
            <dd>Accounts {f.inadmissible.join(', ') || 'none'}</dd>
          </dl>
        </Panel>
        <Panel title="Thresholds" subtitle="The capital adequacy ratios the company is measured against.">
          <DataTable
            columns={[
              { key: 'label', header: 'Threshold', render: (t) => t.label },
              { key: 'ratio', header: 'Ratio', align: 'right', render: (t) => `${t.ratioPercent}%` },
            ]}
            rows={f.thresholds}
            rowKey={(t) => t.key}
            caption="Thresholds"
          />
        </Panel>
      </div>
      <Panel title="Stress shocks" subtitle="The scenarios the stress test re-runs the ratio under.">
        <DataTable
          columns={[
            { key: 'label', header: 'Shock', render: (s: Shock) => s.label },
            { key: 'kind', header: 'Acts on', render: (s: Shock) => SHOCK_KIND[s.kind] },
            { key: 'accounts', header: 'Accounts', render: (s: Shock) => s.accounts.join(', ') },
            { key: 'size', header: 'Size', align: 'right', render: (s: Shock) => shockSize(s) },
          ]}
          rows={f.shocks}
          rowKey={(s) => s.key}
          caption="Stress shocks"
        />
      </Panel>
    </>
  );
}

function shockSize(s: Shock): string {
  if (s.kind === 'LOSS') return Number(s.amount ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2 });
  const p = Number(s.percent ?? 0);
  return `${p > 0 ? '+' : ''}${p}%`;
}

function Versions({ sets }: { sets: SolvencyFactorSet[] }) {
  return (
    <Panel title="Versions" subtitle="A month is worked out on the newest version in force at its end.">
      <DataTable
        columns={[
          { key: 'version', header: 'Version', render: (s) => (s.version === 0 ? 'Built in' : String(s.version)) },
          { key: 'name', header: 'Name', render: (s) => s.name },
          { key: 'from', header: 'In force from', nowrap: true, render: (s) => (s.version === 0 ? 'Always' : s.effectiveFrom) },
          { key: 'status', header: 'Status', render: (s) => (s.placeholder ? 'Placeholder' : 'Approved factors') },
          { key: 'by', header: 'Published by', render: (s) => s.createdBy ?? '' },
        ]}
        rows={sets}
        rowKey={(s) => String(s.version)}
        caption="Versions"
      />
    </Panel>
  );
}

// ---- the new-version form -------------------------------------------------------------------------------------

/** The form's state: the factors as typed, every number still a string until it is sent. */
interface Draft {
  name: string;
  effectiveFrom: string;
  placeholder: boolean;
  combination: SolvencyFactors['combination'];
  charges: Record<'insurance' | 'market' | 'credit', (Charge & { typed: string })[]>;
  operational: string;
  inadmissible: string;
  thresholds: (SolvencyFactors['thresholds'][number] & { typed: string })[];
  shocks: (Shock & { typed: string })[];
}

function toDraft(set: SolvencyFactorSet): Draft {
  const f = set.factors;
  const typed = (c: Charge) => ({ ...c, typed: String(c.percent) });
  return {
    name: set.version === 0 ? '' : set.name,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    placeholder: set.placeholder,
    combination: f.combination,
    charges: { insurance: f.insurance.map(typed), market: f.market.map(typed), credit: f.credit.map(typed) },
    operational: String(f.operationalPercent),
    inadmissible: f.inadmissible.join(', '),
    thresholds: f.thresholds.map((t) => ({ ...t, typed: String(t.ratioPercent) })),
    shocks: f.shocks.map((s) => ({ ...s, typed: String(s.kind === 'LOSS' ? s.amount ?? 0 : s.percent ?? 0) })),
  };
}

/** The draft as the server takes it. A number that is not one is sent as typed, and the server says which. */
function toFactors(d: Draft): SolvencyFactors {
  const num = (s: string) => Number(s.trim());
  const charge = ({ typed, ...c }: Charge & { typed: string }) => ({ ...c, percent: num(typed) });
  return {
    insurance: d.charges.insurance.map(charge),
    market: d.charges.market.map(charge),
    credit: d.charges.credit.map(charge),
    operationalPercent: num(d.operational),
    inadmissible: d.inadmissible.split(/[\s,]+/).filter(Boolean),
    combination: d.combination,
    thresholds: d.thresholds.map(({ typed, ...t }) => ({ ...t, ratioPercent: num(typed) })),
    shocks: d.shocks.map(({ typed, ...s }) =>
      s.kind === 'LOSS' ? { ...s, amount: num(typed), percent: null } : { ...s, percent: num(typed), amount: null }),
  };
}

function DraftForm({ draft, onChange, onCancel, onPublished }: {
  draft: Draft;
  onChange: (d: Draft) => void;
  onCancel: () => void;
  onPublished: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setPending(true);
    setError(null);
    try {
      await publishSolvencyFactorSet({
        name: draft.name.trim(),
        effectiveFrom: draft.effectiveFrom,
        placeholder: draft.placeholder,
        factors: toFactors(draft),
      });
      onPublished();
    } catch (err) {
      setError(toApiError(err));
    } finally {
      setPending(false);
    }
  }

  const setCharge = (risk: keyof Draft['charges'], i: number, typed: string) =>
    onChange({ ...draft, charges: { ...draft.charges, [risk]: draft.charges[risk].map((c, j) => (j === i ? { ...c, typed } : c)) } });

  return (
    <form onSubmit={(e) => void submit(e)} className="space-y-4" aria-label="New version of the solvency factors">
      <Panel title="New version" subtitle="Published versions are never changed: this one applies from its date on.">
        <div className="flex flex-wrap items-end gap-4 px-4 pb-4">
          <label className="flex min-w-64 flex-1 flex-col gap-1 text-xs" htmlFor="factor-name">
            Name *
            <Input id="factor-name" value={draft.name} onChange={(e) => onChange({ ...draft, name: e.target.value })}
              placeholder="TIRA Risk-Based Capital 2025" required />
          </label>
          <label className="flex flex-col gap-1 text-xs" htmlFor="factor-from">
            In force from *
            <Input id="factor-from" type="date" value={draft.effectiveFrom}
              onChange={(e) => onChange({ ...draft, effectiveFrom: e.target.value })} required />
          </label>
          <label className="flex flex-col gap-1 text-xs" htmlFor="factor-combination">
            Charges combine as
            <select id="factor-combination" value={draft.combination}
              onChange={(e) => onChange({ ...draft, combination: e.target.value as Draft['combination'] })}
              className="h-9 rounded-md border border-border-strong bg-surface px-2 text-sm">
              {Object.entries(COMBINATION).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <CheckboxField label="These are placeholders, not the regulator's factors" checked={draft.placeholder}
            onChange={(e) => onChange({ ...draft, placeholder: e.target.checked })} />
        </div>
      </Panel>

      <div className="grid gap-4 xl:grid-cols-3">
        {RISKS.map((r) => (
          <Panel key={r.key} title={r.title} subtitle="Rate in per cent of the accounts' balances.">
            <ul className="space-y-2 px-4 pb-4">
              {draft.charges[r.key].map((c, i) => (
                <li key={c.key} className="grid grid-cols-[minmax(0,1fr)_6rem] items-center gap-3">
                  <label htmlFor={`rate-${r.key}-${c.key}`} className="min-w-0 text-sm">
                    {c.label}
                    <span className="block text-xs text-muted-foreground">{c.accounts.join(', ')}</span>
                  </label>
                  <Input id={`rate-${r.key}-${c.key}`} inputMode="decimal" value={c.typed}
                    onChange={(e) => setCharge(r.key, i, e.target.value)} className="text-right" />
                </li>
              ))}
            </ul>
          </Panel>
        ))}
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Panel title="Operational risk and capital" subtitle="Rate on 12 months' insurance revenue; accounts that are not capital.">
          <div className="space-y-3 px-4 pb-4">
            <label className="grid grid-cols-[minmax(0,1fr)_6rem] items-center gap-3 text-sm" htmlFor="rate-operational">
              Operational risk, per cent
              <Input id="rate-operational" inputMode="decimal" value={draft.operational}
                onChange={(e) => onChange({ ...draft, operational: e.target.value })} className="text-right" />
            </label>
            <label className="flex flex-col gap-1 text-sm" htmlFor="inadmissible">
              Not capital (account codes)
              <Input id="inadmissible" value={draft.inadmissible}
                onChange={(e) => onChange({ ...draft, inadmissible: e.target.value })} />
            </label>
          </div>
        </Panel>
        <Panel title="Thresholds" subtitle="Ratio in per cent of required capital.">
          <ul className="space-y-2 px-4 pb-4">
            {draft.thresholds.map((t, i) => (
              <li key={t.key} className="grid grid-cols-[minmax(0,1fr)_6rem] items-center gap-3">
                <label htmlFor={`threshold-${t.key}`} className="text-sm">{t.label}</label>
                <Input id={`threshold-${t.key}`} inputMode="decimal" value={t.typed} className="text-right"
                  onChange={(e) => onChange({ ...draft, thresholds: draft.thresholds.map((x, j) => (j === i ? { ...x, typed: e.target.value } : x)) })} />
              </li>
            ))}
          </ul>
        </Panel>
      </div>

      <Panel title="Stress shocks" subtitle="A move in per cent, or a loss in shillings.">
        <ul className="grid gap-x-6 gap-y-2 px-4 pb-4 lg:grid-cols-2">
          {draft.shocks.map((s, i) => (
            <li key={s.key} className="grid grid-cols-[minmax(0,1fr)_9rem] items-center gap-3">
              <label htmlFor={`shock-${s.key}`} className="min-w-0 text-sm">
                {s.label}
                <span className="block text-xs text-muted-foreground">
                  {SHOCK_KIND[s.kind]}{s.accounts.length > 0 ? `: ${s.accounts.join(', ')}` : ''}
                </span>
              </label>
              <Input id={`shock-${s.key}`} inputMode="decimal" value={s.typed} className="text-right"
                onChange={(e) => onChange({ ...draft, shocks: draft.shocks.map((x, j) => (j === i ? { ...x, typed: e.target.value } : x)) })} />
            </li>
          ))}
        </ul>
      </Panel>

      {error && <InlineError lead="The factors were not published" error={error} />}
      <div className="flex gap-2">
        <Button type="submit" variant="primary" pending={pending}>Publish version</Button>
        <Button type="button" variant="secondary" onClick={onCancel} disabled={pending}>Cancel</Button>
      </div>
    </form>
  );
}
