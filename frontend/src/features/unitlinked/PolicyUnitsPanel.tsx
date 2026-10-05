import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import type { PolicyView } from '@/api/types';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { approveSurrenderGates } from '@/gates/valueGates';
import { formatDate, formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectApprovingSurrender, selectRequestingSurrender, selectSurrenderRequest, usePolicyStore } from '@/store/policyStore';
import { useUnitLinkedStore } from '@/store/unitLinkedStore';

const ENTRY_LABEL: Record<string, string> = {
  ALLOCATION: 'Premium allocated',
  ALLOCATION_CHARGE: 'Allocation charge',
  POLICY_FEE: 'Policy fee',
  COST_OF_INSURANCE: 'Cost of insurance',
  DEATH_SALE: 'Sold on death',
  SURRENDER_SALE: 'Sold on surrender',
  MATURITY_SALE: 'Sold at maturity',
  LAPSE_SALE: 'Sold on lapse',
  FREE_LOOK_SALE: 'Sold on free-look cancellation',
  CHARGE_REFUND: 'Charge refunded',
  REINVESTMENT: 'Reinvested',
  PRICE_CORRECTION: 'Price correction',
  WRITE_OFF: 'Charge written off',
};

const FROZEN_LABEL: Record<string, string> = {
  DEATH: 'a death claim',
  SURRENDER: 'a surrender',
  MATURITY: 'maturity',
  FREE_LOOK: 'a free-look cancellation',
  LAPSE: 'lapse',
  EXHAUSTED: 'the fund running out',
};

/**
 * A unit-linked policy's units (product step 6): what it holds in each fund and what that is worth at the
 * fund's latest approved price -- shown, never posted -- what is waiting for a forward price and the date
 * it is bound to, and every ledger entry. A surrender is requested here with no figure: the units are sold
 * at the first price after a second person approves it, never at the price on screen when it was clicked.
 */
export function PolicyUnitsPanel({ policy, isStaff }: { policy: PolicyView; isStaff: boolean }) {
  const policyNumber = policy.policyNumber ?? '';
  const units = useUnitLinkedStore((s) => s.units[policyNumber]);
  const loadUnits = useUnitLinkedStore((s) => s.loadUnits);

  useEffect(() => {
    if (policyNumber) void loadUnits(policyNumber);
  }, [policyNumber, loadUnits]);

  if (!units || isInitialLoad(units)) return <LoadingBlock />;
  if (units.status === 'error' && units.error && units.data === null) {
    return <ErrorPanel error={units.error} onRetry={() => void loadUnits(policyNumber)} />;
  }
  const data = units.data;
  if (!data) return null;
  const currency = data.currency ?? policy.premium?.currencyCode ?? 'TZS';

  return (
    <div className="space-y-4">
      {data.frozen && (
        <p role="status" className="rounded-md border border-border bg-status-warning-bg px-3 py-2 text-xs">
          The units are being sold for {FROZEN_LABEL[data.frozenReason ?? ''] ?? 'an exit'}; nothing else moves them until it completes.
        </p>
      )}

      <section aria-label="Holdings">
        <p className="mb-1 text-xs font-medium text-muted-foreground">Holdings</p>
        {data.holdings.length === 0 ? (
          <EmptyState title="No units yet" description="Units are bought when the first premium is priced." />
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-sm" aria-label="Units held">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">Fund</th>
                  <th className="px-3 py-2 font-medium">Units</th>
                  <th className="px-3 py-2 font-medium">Latest price</th>
                  <th className="px-3 py-2 font-medium">Value</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {data.holdings.map((h) => (
                  <tr key={h.fundCode}>
                    <td className="px-3 py-2">
                      <span className="font-mono text-xs">{h.fundCode}</span> {h.fundName}
                    </td>
                    <td className="px-3 py-2 font-mono">{h.units}</td>
                    <td className="px-3 py-2 font-mono">
                      {h.price ?? '—'}
                      {h.priceDate ? <span className="ml-1 text-xs text-muted-foreground">({formatDate(h.priceDate)})</span> : null}
                    </td>
                    <td className="px-3 py-2">{h.value ? formatMoney({ amount: h.value, currencyCode: currency }) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="mt-1 text-xs text-muted-foreground">
          Worth {formatMoney({ amount: data.totalValue, currencyCode: currency })} at the latest approved prices. An indication only:
          any sale is at the first price after it is asked for.
        </p>
      </section>

      {data.pending.length > 0 && (
        <section aria-label="Waiting for a price">
          <p className="mb-1 text-xs font-medium text-muted-foreground">Waiting for a price</p>
          <ul className="space-y-1 text-xs">
            {data.pending.map((o) => (
              <li key={o.orderId}>
                {o.side === 'BUY' ? 'Buy' : 'Sell'} {o.sellAll ? 'every unit' : o.amount ? formatMoney({ amount: o.amount, currencyCode: currency }) : ''}{' '}
                in <span className="font-mono">{o.fundCode}</span> ({o.purpose.toLowerCase().replace('_', ' ')}) at the{' '}
                {formatDate(o.boundDate)} price · received {formatInstant(o.receivedAt)}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-label="Unit ledger">
        <p className="mb-1 text-xs font-medium text-muted-foreground">Ledger</p>
        {data.entries.length === 0 ? (
          <p className="text-xs text-muted-foreground">No entry yet.</p>
        ) : (
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-xs" aria-label="Unit ledger entries">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="px-2 py-1.5 font-medium">Priced</th>
                  <th className="px-2 py-1.5 font-medium">Entry</th>
                  <th className="px-2 py-1.5 font-medium">Fund</th>
                  <th className="px-2 py-1.5 font-medium">Units</th>
                  <th className="px-2 py-1.5 font-medium">Price</th>
                  <th className="px-2 py-1.5 font-medium">Amount</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {data.entries.map((e) => (
                  <tr key={e.entryId}>
                    <td className="px-2 py-1.5">{e.valuationDate ? formatDate(e.valuationDate) : formatInstant(e.createdAt)}</td>
                    <td className="px-2 py-1.5">{ENTRY_LABEL[e.type] ?? e.type}</td>
                    <td className="px-2 py-1.5 font-mono">{e.fundCode ?? '—'}</td>
                    <td className="px-2 py-1.5 font-mono">{e.units}</td>
                    <td className="px-2 py-1.5 font-mono">{e.price ?? '—'}</td>
                    <td className="px-2 py-1.5">{formatMoney({ amount: e.amount, currencyCode: currency })}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {isStaff && <UnitLinkedSurrender policy={policy} />}
      {isStaff && data.frozen && (data.frozenReason === 'MATURITY' || data.frozenReason === 'LAPSE') && data.pending.length === 0 && (
        <NamePayee policyNumber={policyNumber} />
      )}
    </div>
  );
}

function UnitLinkedSurrender({ policy }: { policy: PolicyView }) {
  const policyNumber = policy.policyNumber ?? '';
  const auth = useAuth();
  const viewerSubject = readIdentity(auth.user?.access_token)?.subject ?? undefined;
  const request = usePolicyStore(selectSurrenderRequest(policyNumber));
  const loadSurrenderRequest = usePolicyStore((s) => s.loadSurrenderRequest);
  const requestSurrender = usePolicyStore((s) => s.requestSurrender);
  const approveSurrender = usePolicyStore((s) => s.approveSurrender);
  const requesting = usePolicyStore(selectRequestingSurrender(policyNumber));
  const approving = usePolicyStore(selectApprovingSurrender(policyNumber));
  const loadUnits = useUnitLinkedStore((s) => s.loadUnits);
  const [payeeRef, setPayeeRef] = useState('');
  const [armed, setArmed] = useState<'request' | 'approve' | null>(null);

  useEffect(() => {
    if (policyNumber) void loadSurrenderRequest(policyNumber);
  }, [policyNumber, loadSurrenderRequest]);

  const current = request.data ?? null;
  const awaitingApproval = current?.status === 'REQUESTED';
  const inForce = ['ACTIVE', 'REINSTATED'].includes(policy.status ?? '');
  const gates = awaitingApproval ? approveSurrenderGates(current, viewerSubject) : [];
  const refused = gates.some((g) => !g.ok && g.hard);

  return (
    <section aria-label="Surrender" className="space-y-2 border-t border-border pt-4">
      <div className="flex items-center gap-2">
        <p className="text-xs font-medium">Surrender</p>
        {current?.status && <StatusBadge kind="surrenderRequest" value={current.status} />}
      </div>
      <p className="text-xs text-muted-foreground">
        No figure is quoted: every unit is sold at the first fund price approved after a second person approves the
        surrender, and the proceeds are paid to the payee.
      </p>
      {current && !awaitingApproval && current.status !== 'FAILED' && (
        <p className="text-xs text-muted-foreground">
          To {current.payeeRef}, requested by {current.requestedBy}
          {current.approvedBy ? `, approved by ${current.approvedBy}` : ''}.
        </p>
      )}
      {requesting.status === 'error' && requesting.error && <InlineError error={requesting.error} />}
      {approving.status === 'error' && approving.error && <InlineError error={approving.error} />}

      {awaitingApproval ? (
        <>
          <GatePanel gates={gates} title="Before approving this surrender" />
          {armed === 'approve' ? (
            <ConfirmAct
              heading="Approve this surrender?"
              tone="danger"
              consequence={
                <>
                  Stop cover on <strong>{policyNumber}</strong> and sell every unit at the next approved price, paying the
                  proceeds to {current?.payeeRef}.
                </>
              }
              reversal="Cover stops immediately and nothing restores it. The value is whatever the next price makes it."
              confirmLabel="Approve and sell the units"
              busy={approving.status === 'loading'}
              onConfirm={() => {
                // The approval freezes the units and queues their sale: reread them, or the panel shows holdings
                // that are no longer the policy's to keep.
                void approveSurrender(policyNumber, current?.surrenderRequestId ?? '').then(() => loadUnits(policyNumber));
                setArmed(null);
              }}
              onCancel={() => setArmed(null)}
            />
          ) : (
            <Button size="sm" disabled={refused} onClick={() => setArmed('approve')}>
              Approve surrender
            </Button>
          )}
        </>
      ) : !inForce ? (
        <p className="text-xs text-muted-foreground">Only a policy in force can be surrendered.</p>
      ) : armed === 'request' ? (
        <ConfirmAct
          heading="Request this surrender?"
          consequence={
            <>
              Record a surrender of <strong>{policyNumber}</strong> to {payeeRef}. Cover does <strong>not</strong> stop yet —
              a second person has to approve it, and the units are valued only then.
            </>
          }
          reversal="A request that is never approved leaves the policy exactly as it is."
          confirmLabel="Record surrender request"
          busy={requesting.status === 'loading'}
          onConfirm={() => {
            void requestSurrender(policyNumber, payeeRef);
            setArmed(null);
          }}
          onCancel={() => setArmed(null)}
        />
      ) : (
        <div className="space-y-2">
          <FormField label="Payee (mobile money or bank destination)">
            <Input inputSize="sm" placeholder="+255712345678" value={payeeRef} onChange={(e) => setPayeeRef(e.target.value)} />
          </FormField>
          <Button size="sm" disabled={payeeRef.trim() === ''} onClick={() => setArmed('request')}>
            Request surrender
          </Button>
        </div>
      )}
    </section>
  );
}

/** A maturity or lapse payout the policyholder's record could not supply a payee for. */
function NamePayee({ policyNumber }: { policyNumber: string }) {
  const namePayee = useUnitLinkedStore((s) => s.namePayee);
  const acting = useUnitLinkedStore((s) => s.acting[`payee.${policyNumber}`]);
  const [payee, setPayee] = useState('');
  return (
    <section aria-label="Name the payee" className="space-y-2 border-t border-border pt-4">
      <p className="text-xs font-medium">Name the payee</p>
      <p className="text-xs text-muted-foreground">
        If the units have been sold and the policyholder&apos;s record has no number to pay, name where the proceeds go.
      </p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="flex items-end gap-2">
        <FormField label="Pay to">
          <Input inputSize="sm" value={payee} onChange={(e) => setPayee(e.target.value)} />
        </FormField>
        <Button size="sm" disabled={payee.trim() === '' || acting?.status === 'loading'} onClick={() => void namePayee(policyNumber, payee.trim())}>
          Pay the proceeds
        </Button>
      </div>
    </section>
  );
}
