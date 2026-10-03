import { useEffect } from 'react';
import type { BonusEntryView, BonusOutcomeView, BonusSettlementView } from '@/api/types';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useBonusStore } from '@/store/bonusStore';

/**
 * A with-profits policy's bonuses (product step 4): what is attached, the ledger that says how it
 * got there, why each declaration did or did not attach, and what was paid out at an exit.
 *
 * The ledger is the record, shown as one -- in sequence order, nothing edited. A correction is its
 * own REVERSAL line naming what it reverses, never a changed figure, as the Account tab's ledger is.
 */
export function PolicyBonusesPanel({ policyNumber }: { policyNumber: string }) {
  const bonuses = useBonusStore((s) => s.policy[policyNumber]);
  const loadPolicy = useBonusStore((s) => s.loadPolicy);

  useEffect(() => {
    void loadPolicy(policyNumber);
  }, [policyNumber, loadPolicy]);

  if (!bonuses || isInitialLoad(bonuses)) return <LoadingBlock />;
  if (bonuses.status === 'error' && bonuses.error && bonuses.data === null) {
    return <ErrorPanel error={bonuses.error} onRetry={() => void loadPolicy(policyNumber)} />;
  }
  if (!bonuses.data) return null;
  const view = bonuses.data;

  return (
    <div className="space-y-5">
      <dl className="divide-y divide-border rounded-md border border-border">
        <Field label="Attached bonuses" value={formatMoney(view.attachedTotal)} emphasis />
      </dl>
      <History entries={view.entries} />
      <Outcomes outcomes={view.outcomes} />
      {view.settlements.length > 0 && <Settlements settlements={view.settlements} />}
    </div>
  );
}

const ENTRY_LABEL: Record<BonusEntryView['type'], string> = {
  REVERSIONARY: 'Reversionary bonus',
  REVERSAL: 'Reversal',
};

function History({ entries }: { entries: BonusEntryView[] }) {
  if (entries.length === 0) {
    return (
      <EmptyState
        title="No bonus attached yet"
        description="A bonus attaches when a declaration on this product is approved and its valuation date arrives, if the policy is eligible then."
      />
    );
  }
  const seqById = new Map(entries.map((e) => [e.entryId, e.seq]));
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium">Bonus history</p>
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Bonus history">
        {entries.map((entry) => (
          <div key={entry.entryId} role="listitem" className="flex items-start justify-between gap-3 px-4 py-2.5">
            <div className="min-w-0">
              <span className="text-xs text-muted-foreground">#{entry.seq}</span>{' '}
              <span className="text-sm font-medium">{ENTRY_LABEL[entry.type]}</span>
              <p className="text-xs text-muted-foreground">
                as at {formatDate(entry.effectiveDate)}
                {entry.ratePercent && entry.basis ? ` · ${entry.ratePercent}% of ${formatMoney(entry.basis)}` : ''}
                {entry.reason ? ` · ${entry.reason}` : ''}
                {entry.reversesEntryId ? ` · reverses #${seqById.get(entry.reversesEntryId) ?? '?'}` : ''}
              </p>
            </div>
            <span className="shrink-0 text-right">
              <span className="block text-sm font-medium">{formatMoney(entry.amount)}</span>
              <span className="block text-xs text-muted-foreground">total {formatMoney(entry.totalAfter)}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

const OUTCOME_LABEL: Record<BonusOutcomeView['outcome'], string> = {
  ATTACHED: 'Attached',
  NOT_ELIGIBLE: 'Not eligible',
  NOTHING_DUE: 'Nothing due',
};

/** Why a declaration did or did not attach -- the answer to "why did this policy get nothing?" */
function Outcomes({ outcomes }: { outcomes: BonusOutcomeView[] }) {
  if (outcomes.length === 0) return null;
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium">Declarations</p>
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Declaration outcomes">
        {outcomes.map((o) => (
          <div key={o.declarationId} role="listitem" className="px-4 py-2.5">
            <span className="text-sm font-medium">
              {o.valuationDate ? `As at ${formatDate(o.valuationDate)}` : 'Declaration'}
            </span>{' '}
            <StatusBadge kind="bonusOutcome" value={o.outcome} />
            <p className="text-xs text-muted-foreground">
              {OUTCOME_LABEL[o.outcome]}
              {o.reason ? ` · ${o.reason}` : ''}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}

const EXIT_LABEL: Record<BonusSettlementView['exitType'], string> = {
  MATURITY: 'Maturity',
  DEATH: 'Death claim',
};

/** What bonuses added at an exit, recorded once at the exit and never revalued. */
function Settlements({ settlements }: { settlements: BonusSettlementView[] }) {
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium">Paid out</p>
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Bonus settlements">
        {settlements.map((s) => (
          <div key={`${s.exitType}.${s.exitRef}`} role="listitem" className="space-y-1 px-4 py-2.5">
            <p className="text-sm font-medium">
              {EXIT_LABEL[s.exitType]} · {formatDate(s.exitDate)}
            </p>
            <dl className="grid grid-cols-2 gap-x-4 text-xs sm:grid-cols-4">
              <SettlementLine label="Attached" value={formatMoney(s.value.attached)} />
              <SettlementLine label="Interim" value={formatMoney(s.value.interim)} />
              <SettlementLine label="Terminal" value={formatMoney(s.value.terminal)} />
              <SettlementLine label="Total" value={formatMoney(s.value.total)} />
            </dl>
          </div>
        ))}
      </div>
    </div>
  );
}

function SettlementLine({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium">{value}</dd>
    </div>
  );
}
