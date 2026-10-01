import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { Receipt } from '@/components/Receipt';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { Input } from '@/components/ui/input';
import { approveSurrenderGates, paidUpGates, surrenderGates } from '@/gates/valueGates';
import { formatMoney } from '@/lib/money';
import type { PolicyView, SurrenderQuote, SurrenderRequestView } from '@/api/types';
import {
  selectApprovingSurrender,
  selectMakingPaidUp,
  selectRequestingSurrender,
  selectSurrenderQuote,
  selectSurrenderRequest,
  usePolicyStore,
} from '@/store/policyStore';

/**
 * The two things a customer can do with a savings policy's value: stop paying and keep reduced
 * cover (paid-up), or cash it in (surrender). Both move or reduce what the insurer owes, so both
 * live on the page rather than in the drawer, and surrender needs a second person.
 *
 * Shown on the Overview tab beside the lifecycle actions, and taking no `emphasis` for the reason
 * the lifecycle panel does not: most visits to a policy are somebody looking something up, so
 * promoting a rare, irreversible act above the record would mis-state why the reader is here.
 */
export function ValueActions({ policy }: { policy: PolicyView }) {
  const policyNumber = policy.policyNumber ?? '';
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);

  const quote = usePolicyStore(selectSurrenderQuote(policyNumber));
  const request = usePolicyStore(selectSurrenderRequest(policyNumber));
  const loadSurrenderQuote = usePolicyStore((s) => s.loadSurrenderQuote);
  const loadSurrenderRequest = usePolicyStore((s) => s.loadSurrenderRequest);

  useEffect(() => {
    if (!policyNumber) return;
    void loadSurrenderQuote(policyNumber);
    void loadSurrenderRequest(policyNumber);
  }, [policyNumber, loadSurrenderQuote, loadSurrenderRequest]);

  return (
    <div className="space-y-4 px-4 pb-4">
      <PaidUpAction policy={policy} />
      <div className="border-t border-border pt-4">
        <SurrenderAction
          policy={policy}
          quote={quote.data ?? null}
          request={request.data ?? null}
          viewerSubject={identity?.subject ?? undefined}
        />
      </div>
    </div>
  );
}

function PaidUpAction({ policy }: { policy: PolicyView }) {
  const policyNumber = policy.policyNumber ?? '';
  const makePaidUp = usePolicyStore((s) => s.makePaidUp);
  const making = usePolicyStore(selectMakingPaidUp(policyNumber));
  const [armed, setArmed] = useState(false);
  const gates = paidUpGates(policy);
  const refused = gates.some((g) => !g.ok && g.hard);

  if (policy.status === 'PAID_UP') {
    return (
      <p className="text-xs text-muted-foreground">
        This policy is paid-up: no premium is due and it stays on cover for the reduced sum assured
        above.
      </p>
    );
  }

  return (
    <div className="space-y-2">
      <p className="text-xs font-medium">Make paid-up</p>
      <GatePanel gates={gates} title="Before making this policy paid-up" />
      {making.status === 'error' && making.error && <InlineError error={making.error} />}
      {armed ? (
        <ConfirmAct
          heading="Make this policy paid-up?"
          consequence={
            <>
              <strong>{policyNumber}</strong> stops being billed and keeps a reduced sum assured,
              worked out from the premiums already paid. The server calculates the new figure — this
              page shows it once it has.
            </>
          }
          /*
            Policy.makePaidUp overwrites the sum assured and there is no transition back: a
            paid-up policy can be surrendered or claimed on, but nothing restores the original
            cover or the billing schedule.
          */
          reversal="Nothing here restores the original cover. Full cover would mean a new application, underwritten again at today's age."
          confirmLabel="Make paid-up"
          busy={making.status === 'loading'}
          onConfirm={() => {
            void makePaidUp(policyNumber);
            setArmed(false);
          }}
          onCancel={() => setArmed(false)}
        />
      ) : (
        <Button size="sm" disabled={refused} onClick={() => setArmed(true)}>
          Make paid-up
        </Button>
      )}
    </div>
  );
}

function SurrenderAction({
  policy,
  quote,
  request,
  viewerSubject,
}: {
  policy: PolicyView;
  quote: SurrenderQuote | null;
  request: SurrenderRequestView | null;
  viewerSubject: string | undefined;
}) {
  const policyNumber = policy.policyNumber ?? '';
  const requestSurrender = usePolicyStore((s) => s.requestSurrender);
  const approveSurrender = usePolicyStore((s) => s.approveSurrender);
  const requesting = usePolicyStore(selectRequestingSurrender(policyNumber));
  const approving = usePolicyStore(selectApprovingSurrender(policyNumber));
  const [payeeRef, setPayeeRef] = useState('');
  const [armed, setArmed] = useState<'request' | 'approve' | null>(null);

  const awaitingApproval = request?.status === 'REQUESTED';
  const gates = awaitingApproval
    ? approveSurrenderGates(request, viewerSubject)
    : surrenderGates(policy, quote, request);
  const refused = gates.some((g) => !g.ok && g.hard);
  const amount = formatMoney(request?.quotedValue ?? quote?.quotedValue);

  // The money is requested, not paid: the payout returns through the payment rail, so the
  // request's own status is the only honest answer about where it got to.
  if (approving.status === 'success' && approving.data) {
    return (
      <Receipt
        heading="Surrender approved"
        lines={[
          { label: 'Policy', value: policyNumber },
          { label: 'Paying', value: formatMoney(approving.data.quotedValue) },
          { label: 'Payee', value: approving.data.payeeRef ?? '—' },
          { label: 'Approved by', value: approving.data.approvedBy ?? '—' },
        ]}
        note="Cover has stopped and the payout has been requested, not paid. This panel shows PAID once the payment provider confirms it."
      />
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <p className="text-xs font-medium">Surrender</p>
        {request?.status && <StatusBadge kind="surrenderRequest" value={request.status} />}
      </div>

      {request && !awaitingApproval && request.status !== 'FAILED' && (
        <p className="text-xs text-muted-foreground">
          {formatMoney(request.quotedValue)} to {request.payeeRef}, requested by{' '}
          {request.requestedBy}.
        </p>
      )}

      <GatePanel
        gates={gates}
        title={awaitingApproval ? 'Before approving this surrender' : 'Before surrendering this policy'}
      />

      {requesting.status === 'error' && requesting.error && <InlineError error={requesting.error} />}
      {approving.status === 'error' && approving.error && <InlineError error={approving.error} />}

      {awaitingApproval ? (
        armed === 'approve' ? (
          <ConfirmAct
            heading="Approve this surrender?"
            tone="danger"
            consequence={
              <>
                Pay <strong>{amount}</strong> to {request?.payeeRef} and stop cover on{' '}
                <strong>{policyNumber}</strong> as of today.
              </>
            }
            reversal="Cover stops immediately and nothing restores it — a failed payout leaves the policy surrendered and the payment to be retried, not the cover revived."
            confirmLabel={`Pay ${amount}`}
            busy={approving.status === 'loading'}
            onConfirm={() => {
              void approveSurrender(policyNumber, request?.surrenderRequestId ?? '');
              setArmed(null);
            }}
            onCancel={() => setArmed(null)}
          />
        ) : (
          <Button size="sm" disabled={refused} onClick={() => setArmed('approve')}>
            Approve surrender
          </Button>
        )
      ) : armed === 'request' ? (
        <ConfirmAct
          heading="Request this surrender?"
          consequence={
            <>
              Record a surrender of <strong>{policyNumber}</strong> for <strong>{amount}</strong> to{' '}
              {payeeRef}. Cover does <strong>not</strong> stop yet — a second person has to approve
              it.
            </>
          }
          reversal="A request that is never approved leaves the policy exactly as it is, still on cover and still billed."
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
            <Input
              inputSize="sm"
              placeholder="+255712345678"
              value={payeeRef}
              onChange={(e) => setPayeeRef(e.target.value)}
            />
          </FormField>
          <Button size="sm" disabled={refused || payeeRef.trim() === ''} onClick={() => setArmed('request')}>
            Request surrender
          </Button>
        </div>
      )}
    </div>
  );
}
