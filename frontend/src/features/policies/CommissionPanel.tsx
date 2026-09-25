import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { getAgent, getApplicablePlan, listAgents, listPolicyAccruals, onboardAgent, setCommissionRate } from '@/api/distribution';
import { changeSchemeAgentOfRecord, getPolicy } from '@/api/policies';
import type { AgentView, CommissionAccrualView, CommissionPlanView, PolicyView } from '@/api/types';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { PartyName } from '@/components/PartyName';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { commissionStanding, ratePercentLabel, firstYearRate } from './commissionStanding';

interface Loaded {
  policy: PolicyView | null;
  agent: AgentView | null;
  lenderAgent: AgentView | null;
  plan: CommissionPlanView | null;
  accruals: CommissionAccrualView[];
}

/**
 * Who earns commission on this scheme, at what rate, and what it has earned.
 *
 * ## Why this exists
 *
 * On credit life the LENDER earns commission on each file's single premium, and loses it pro rata
 * when a borrower repays early (spec 2.8). In dev none of it worked, and none of it showed: 67 of
 * 72 schemes had no agent, the other 5 carried an individual agent who had merely registered the
 * lender, no credit-life product had a plan, and the month-end close that makes commission payable
 * was never installed. Every file earned exactly zero, with nothing on any screen to say so.
 *
 * The rate is agreed per lender (client answer 3.1), so it is set on the lender's own agent rather
 * than on the product. Every change here is forward only: nothing already earned moves.
 */
export function CommissionPanel({ policyNumber }: { policyNumber: string }) {
  const auth = useAuth();
  const isFinance = canSeeFinance(readIdentity(auth.user?.access_token));
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const policy = await getPolicy(policyNumber).catch(() => null);
      const agentId = policy?.agentOfRecordId ?? null;
      const [agent, lenderAgents, accruals] = await Promise.all([
        agentId ? getAgent(agentId).catch(() => null) : Promise.resolve(null),
        policy?.policyholderPartyId
          ? listAgents({ partyId: policy.policyholderPartyId }).catch(() => null)
          : Promise.resolve(null),
        listPolicyAccruals(policyNumber).catch(() => [] as CommissionAccrualView[]),
      ]);
      const plan =
        agentId && policy?.productId
          ? await getApplicablePlan(agentId, policy.productId).catch(() => null)
          : null;
      if (!cancelled) {
        setLoaded({ policy, agent, lenderAgent: lenderAgents?.items[0] ?? null, plan, accruals });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [policyNumber, reload]);

  if (!loaded) return <p className="px-4 py-3 text-xs text-muted-foreground">Loading commission…</p>;

  const { policy, agent, lenderAgent, plan, accruals } = loaded;
  const standing = commissionStanding({ agentOfRecordId: policy?.agentOfRecordId, lenderAgent, plan });
  const refresh = () => setReload((n) => n + 1);

  return (
    <div className="space-y-3 px-4 py-3 text-sm">
      <p
        className={
          standing.kind === 'earning' ? 'text-status-success-fg' : 'font-medium text-status-warning-fg'
        }
      >
        {standing.kind === 'earning' &&
          `The lender earns ${standing.ratePercent} of each accepted file's premium, clawed back pro rata when a borrower repays early.`}
        {standing.kind === 'no-agent' && 'No commission will accrue: this scheme has no agent of record.'}
        {standing.kind === 'not-the-lender' &&
          'Commission would go to someone who is not the lender. On credit life the lender earns it (spec 2.8).'}
        {standing.kind === 'no-rate' && 'No commission will accrue: no rate has been set for the lender.'}
      </p>

      <dl className="space-y-1 text-xs">
        <div className="flex justify-between gap-2">
          <dt className="text-muted-foreground">Agent of record</dt>
          <dd>{agent ? <PartyName partyId={agent.partyId ?? ''} /> : 'None — sold direct'}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-muted-foreground">Lender</dt>
          <dd>{policy?.policyholderPartyId ? <PartyName partyId={policy.policyholderPartyId} /> : '—'}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-muted-foreground">Rate</dt>
          <dd>{ratePercentLabel(firstYearRate(plan)) ?? 'Not set'}</dd>
        </div>
      </dl>

      {isFinance && policy && (
        <LenderCommissionActions policy={policy} lenderAgent={lenderAgent} onChanged={refresh} />
      )}

      <div>
        <p className="text-xs font-medium">Earned on this scheme</p>
        {accruals.length === 0 ? (
          <p className="text-xs text-muted-foreground">Nothing yet. Commission accrues when a file is accepted.</p>
        ) : (
          <ul className="mt-1 space-y-0.5 text-xs">
            {accruals.map((a) => (
              <li key={a.accrualId} className="flex justify-between gap-2">
                <span className="text-muted-foreground">
                  {a.period} · {a.reversesAccrualId ? 'clawback' : 'earned'}
                </span>
                <span className="font-mono">{formatMoney(a.amount)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/** The three finance actions, in the order the lender needs them: become an agent, earn a rate, be the earner. */
function LenderCommissionActions({
  policy,
  lenderAgent,
  onChanged,
}: {
  policy: PolicyView;
  lenderAgent: AgentView | null;
  onChanged: () => void;
}) {
  const [licenseNumber, setLicenseNumber] = useState('');
  const [licenseExpiry, setLicenseExpiry] = useState<string | null>(null);
  const [ratePercent, setRatePercent] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [attempt] = useState(() => startMutation());

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (cause) {
      const apiError = cause as ApiError;
      setError(apiError?.detail ?? apiError?.title ?? 'That did not go through.');
    } finally {
      setBusy(false);
    }
  }

  const isEarner = !!lenderAgent && lenderAgent.agentId === policy.agentOfRecordId;

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      {!lenderAgent ? (
        <>
          <p className="text-xs">The lender is not registered as an agent yet, so it cannot earn commission.</p>
          <div className="grid grid-cols-2 gap-2">
            <FormField label="Lender's licence number">
              <Input value={licenseNumber} onChange={(e) => setLicenseNumber(e.target.value)} />
            </FormField>
            <FormField label="Licence expiry">
              <DatePicker value={licenseExpiry} onChange={setLicenseExpiry} disabled={{ before: new Date() }} />
            </FormField>
          </div>
          <Button
            size="sm"
            disabled={busy || !licenseNumber.trim() || !licenseExpiry || !policy.policyholderPartyId}
            onClick={() =>
              void run(() =>
                onboardAgent(
                  {
                    partyId: policy.policyholderPartyId ?? '',
                    licenseNumber: licenseNumber.trim(),
                    licenseExpiryDate: licenseExpiry ?? '',
                  },
                  attempt,
                ),
              )
            }
          >
            Register the lender as an agent
          </Button>
        </>
      ) : (
        <>
          <div className="flex items-end gap-2">
            <FormField label="Commission rate (%)" className="flex-1">
              <Input
                inputMode="decimal"
                placeholder="e.g. 10 or 12.5"
                value={ratePercent}
                onChange={(e) => setRatePercent(e.target.value)}
              />
            </FormField>
            <Button
              size="sm"
              disabled={busy || !/^\d{1,3}(\.\d{1,2})?$/.test(ratePercent.trim()) || !policy.productId}
              onClick={() =>
                void run(() => setCommissionRate(lenderAgent.agentId ?? '', policy.productId ?? '', ratePercent.trim()))
              }
            >
              Set the lender&rsquo;s rate
            </Button>
          </div>
          {!isEarner && (
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() =>
                void run(() =>
                  changeSchemeAgentOfRecord(
                    policy.policyNumber ?? '',
                    lenderAgent.agentId ?? null,
                    'The lender earns commission on credit life (spec 2.8)',
                  ),
                )
              }
            >
              Make the lender the commission earner
            </Button>
          )}
          <p className="text-[11px] text-muted-foreground">
            From now on only — commission already earned stays where it was booked.
          </p>
        </>
      )}
      {error && (
        <p role="alert" className="text-xs text-status-danger-fg">
          {error}
        </p>
      )}
    </div>
  );
}
