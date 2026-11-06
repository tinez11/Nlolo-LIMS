import { can } from '@/auth/access';
import { useState } from 'react';
import { onboardAgent } from '@/api/distribution';
import { changeSchemeAgentOfRecord } from '@/api/policies';
import type { AgentView, PolicyView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { commissionStanding } from './commissionStanding';
import { isPositive, trimZeros } from './creditLifeAgreementForm';
import type { CommissionFacts } from './useCommission';

/**
 * Who earns commission on this scheme, at what rate, and what it has earned -- facts only, for the record rail.
 *
 * ## Why this exists
 *
 * On credit life the LENDER earns commission on each file's single premium, and loses it pro rata
 * when a borrower repays early (spec 2.8). In dev none of it worked, and none of it showed: 67 of
 * 72 schemes had no agent, the other 5 carried an individual agent who had merely registered the
 * lender, no credit-life product had a plan, and the month-end close that makes commission payable
 * was never installed. Every file earned exactly zero, with nothing on any screen to say so.
 *
 * The rate is the yearly agreement's (2026-11-01), so it is read from the agreement in force, never from a plan or a
 * rate set on the lender's agent. Setting the lender up to be paid is an act, so it is in the work column
 * ({@link CommissionEarnerPanel}), not here (the Record-Rail Rule).
 */
export function CommissionPanel({ facts, agreedCommissionPercent }: {
  facts: CommissionFacts | null;
  /** The agreement in force today's commission; null when none covers today. */
  agreedCommissionPercent: string | null;
}) {
  if (!facts) return <p className="px-4 py-3 text-xs text-muted-foreground">Loading commission…</p>;

  const { policy, agent, lenderAgent, accruals } = facts;
  const standing = commissionStanding({
    agentOfRecordId: policy?.agentOfRecordId,
    lenderAgent,
    agreedCommissionPercent,
  });

  return (
    <div className="space-y-3 px-4 py-3 text-sm">
      <p
        className={
          standing.kind === 'earning' ? 'text-status-success-fg' : 'font-medium text-status-warning-fg'
        }
      >
        {standing.kind === 'earning' &&
          `The lender earns ${standing.ratePercent} of each paid file's premium, clawed back pro rata when a borrower repays early.`}
        {standing.kind === 'no-agent' && 'No commission will accrue: this scheme has no agent of record.'}
        {standing.kind === 'not-the-lender' &&
          'Commission would go to someone who is not the lender. On credit life the lender earns it (spec 2.8).'}
        {standing.kind === 'no-commission' && 'No commission will accrue: no agreement in force pays the lender any.'}
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
          <dd>
            {agreedCommissionPercent && isPositive(agreedCommissionPercent)
              ? `${trimZeros(agreedCommissionPercent)}%, as agreed`
              : 'None agreed'}
          </dd>
        </div>
      </dl>

      <div>
        <p className="text-xs font-medium">Earned on this scheme</p>
        {accruals.length === 0 ? (
          <p className="text-xs text-muted-foreground">Nothing yet. Commission is earned when the lender pays a file.</p>
        ) : (
          <ul className="mt-1 space-y-0.5 text-xs">
            {accruals.map((a) => (
              <li key={a.accrualId} className="flex justify-between gap-2">
                <span className="text-muted-foreground">
                  {a.period} · {a.reversesAccrualId ? 'clawback' : 'earned'}
                </span>
                <span>{formatMoney(a.amount)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/**
 * Making the lender the one paid its commission, in the work column: register it as an agent, then make it the
 * scheme's earner. Shown to finance only while one of the two is still to do, and gone once the lender earns.
 */
export function CommissionEarnerPanel({ facts, onChanged }: { facts: CommissionFacts | null; onChanged: () => void }) {
  const isFinance = can('agents.view');
  if (!isFinance || !facts?.policy) return null;
  const { policy, lenderAgent } = facts;
  const isEarner = !!lenderAgent && lenderAgent.agentId === policy.agentOfRecordId;
  if (isEarner) return null;

  return (
    <Panel
      title="Who is paid the commission"
      subtitle="The lender is paid the commission its agreement states, as the insurer's agent on this scheme."
    >
      <LenderCommissionActions policy={policy} lenderAgent={lenderAgent} onChanged={onChanged} />
    </Panel>
  );
}

/** The two finance actions, in the order the lender needs them: become an agent, be the earner. */
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

  return (
    <div className="space-y-3 p-4">
      {!lenderAgent ? (
        <>
          <p className="text-sm">The lender is not registered as an agent yet, so it cannot be paid commission.</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <FormField required label="Lender's licence number">
              <Input value={licenseNumber} onChange={(e) => setLicenseNumber(e.target.value)} />
            </FormField>
            <FormField required label="Licence expiry">
              <DatePicker value={licenseExpiry} onChange={setLicenseExpiry} disabled={{ before: new Date() }} />
            </FormField>
          </div>
          <Button
            size="sm"
            variant="primary"
            disabled={busy || !licenseNumber.trim() || !licenseExpiry || !policy.policyholderPartyId}
            onClick={() =>
              void run(() =>
                onboardAgent(
                  {
                    partyId: policy.policyholderPartyId ?? '',
                    licenseNumber: licenseNumber.trim(),
                    licenseExpiryDate: licenseExpiry ?? '',
                    // A lender selling its borrowers' cover is bancassurance (2026-10-30): its commission posts to the
                    // bancassurance payable (2530), not the tied agents' (2510) it fell into as the default channel.
                    salesChannel: 'BANCASSURANCE',
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
          <p className="text-sm">
            The lender is an agent, but not this scheme&rsquo;s commission earner yet.
          </p>
          <Button
            size="sm"
            variant="primary"
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
          <p className="text-xs text-muted-foreground">
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
