import { useEffect, useState } from 'react';
import {
  closeAgentLogin,
  getAgentLogin,
  giveAgentLogin,
  sendAgentPassword,
  type AgentLoginIssued,
  type AgentLoginView,
} from '@/api/distribution';
import { can } from '@/auth/access';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { OneTimePassword } from '@/components/OneTimePassword';
import { StaffName } from '@/components/StaffName';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';

const STATUS_LABEL: Record<string, string> = {
  NONE: 'No login yet — this agent cannot sign in',
  OPEN: 'Can sign in to the agent console',
  CLOSED: 'Closed — cannot sign in',
  SUSPENDED: 'Closed while the agent is suspended — opens again on reactivation',
};

const DELIVERY_LABEL: Record<string, string> = {
  EMAIL_LINK: 'Emailed as a set-your-password link',
  SMS_PASSWORD: 'One-time password sent by text',
  TEMPORARY_PASSWORD: 'One-time password given by staff',
};

/**
 * An agent's login to the agent console, on the agent's page (2026-10-28, user decision) -- the client record's Portal
 * access, for agents. Until now an agent onboarded in the register had no way to sign in. The platform creates the login
 * itself, so nobody types the agent's id into Keycloak: with an email the agent gets a set-your-password link; otherwise
 * a one-time password is texted to their phone and never shown here. Only when it cannot be texted is it shown ONCE, to
 * hand over -- it is kept nowhere, and a new password makes a new one.
 */
export function AgentLoginPanel({ agentId }: { agentId: string }) {
  const canManage = can('agents.manage');
  const [login, setLogin] = useState<AgentLoginView | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const [outcome, setOutcome] = useState<
    { password: string | null; smsSentTo: string | null; notice: string | null } | null
  >(null);
  const [confirmClose, setConfirmClose] = useState(false);

  useEffect(() => {
    let live = true;
    getAgentLogin(agentId).then(
      (l) => { if (live) { setLogin(l); setLoadError(null); } },
      (e: unknown) => { if (live) setLoadError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [agentId, reload]);

  async function act(work: () => Promise<AgentLoginIssued | AgentLoginView>) {
    setPending(true);
    setError(null);
    setOutcome(null);
    try {
      const result = await work();
      if ('login' in result) {
        setOutcome({
          password: result.temporaryPassword ?? null,
          smsSentTo: result.smsSentTo ?? null,
          notice: result.notice ?? null,
        });
      }
      setConfirmClose(false);
      setReload((n) => n + 1);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  if (loadError) return <div className="p-4"><ErrorPanel error={loadError} onRetry={() => setReload((n) => n + 1)} /></div>;
  if (!login) return <LoadingBlock label="Loading the agent's login" />;

  const open = login.status === 'OPEN';
  return (
    <div className="space-y-3 px-4 pb-4">
      <dl>
        <Field label="Status" value={STATUS_LABEL[login.status] ?? login.status} />
        {login.username && <Field label="Signs in as" value={login.username} />}
        {login.createdAt && (
          <Field
            label="Given"
            value={<>{formatInstant(login.createdAt)}{login.createdBy && <> by <StaffName subject={login.createdBy} /></>}</>}
          />
        )}
        {login.delivery && login.passwordSentAt && (
          <Field
            label="Last password"
            value={`${DELIVERY_LABEL[login.delivery] ?? login.delivery}, ${formatInstant(login.passwordSentAt)}`}
          />
        )}
        {login.closedAt && login.status !== 'OPEN' && (
          <Field
            label="Closed"
            value={<>{formatInstant(login.closedAt)}{login.closedBy && <> by <StaffName subject={login.closedBy} /></>}</>}
          />
        )}
      </dl>

      {outcome?.smsSentTo && (
        <p role="status" className="text-sm">One-time password sent by text to {outcome.smsSentTo}.</p>
      )}
      {outcome && !outcome.password && !outcome.smsSentTo && (
        <p role="status" className="text-sm">A set-your-password link is on its way to the agent&rsquo;s email.</p>
      )}
      {outcome?.password && (
        <OneTimePassword password={outcome.password} notice={outcome.notice}>
          Give it to the agent in person or by phone. They sign in as {login.username} and must choose their own password
          straight away. It is not kept anywhere: if it is lost, send a new password to make a new one.
        </OneTimePassword>
      )}

      {error && <InlineError error={error} />}

      {canManage && (
        <div className="flex flex-wrap gap-2">
          {(login.status === 'NONE' || login.status === 'CLOSED') && (
            <Button size="sm" variant="primary" pending={pending} onClick={() => void act(() => giveAgentLogin(agentId))}>
              {login.status === 'CLOSED' ? 'Open the login again' : 'Give a login'}
            </Button>
          )}
          {open && (
            <Button size="sm" variant="ghost" pending={pending} onClick={() => void act(() => sendAgentPassword(agentId))}>
              Send a new password
            </Button>
          )}
          {open && !confirmClose && (
            <Button size="sm" variant="ghost" onClick={() => setConfirmClose(true)}>Close the login</Button>
          )}
          {open && confirmClose && (
            <>
              <Button size="sm" variant="danger" pending={pending} onClick={() => void act(() => closeAgentLogin(agentId))}>
                Confirm: close and sign them out
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setConfirmClose(false)}>Cancel</Button>
            </>
          )}
        </div>
      )}
      {!canManage && login.status === 'NONE' && (
        <p className="text-xs text-muted-foreground">Someone who manages agents can give this agent a login.</p>
      )}
    </div>
  );
}
