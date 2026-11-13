import { useCallback, useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { getBaselineSignOff, signOffBaseline } from '@/api/finaccounting';
import type { BaselineSignOffView } from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { StaffName } from '@/components/StaffName';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { formatInstantDay } from '@/lib/dates';

type Signer = 'FINANCE' | 'ACTUARY';

const SIGNERS: { role: Signer; label: string; permission: string }[] = [
  { role: 'FINANCE', label: 'Finance', permission: 'ledger.baseline-sign-off' },
  { role: 'ACTUARY', label: 'Actuary', permission: 'ifrs17.actuary-sign-off' },
];

/**
 * The baseline the register started from (2026-11-12, finance findings 99 and 66). It applies from the first posting,
 * written by the platform, not by a person -- so finance signs it off by name, and an actuary may co-sign. Until
 * finance signs, the page says so.
 */
export function BaselineSignOff() {
  const viewer = readIdentity(useAuth().user?.access_token)?.subject ?? undefined;
  const [state, setState] = useState<BaselineSignOffView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const load = useCallback(() => {
    getBaselineSignOff().then(setState, (cause: ApiError) => setError(cause));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (state === null) return error ? <InlineError error={error} /> : null;
  const signed = state.finance != null;

  return (
    <section
      aria-label="Baseline sign-off"
      className={`space-y-3 rounded-xl border p-4 shadow-card ${signed ? 'border-border bg-surface' : 'border-status-warning-fg/40 bg-status-warning-bg'}`}
    >
      <div>
        <p className="text-sm font-medium">{signed ? 'Baseline signed off' : 'Baseline not signed off'}</p>
        <p className="text-xs text-muted-foreground">
          The platform's starting policies, version 1 onward, apply from the first posting. Finance signs them off; an
          actuary may co-sign.
        </p>
      </div>
      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {SIGNERS.map((s) => {
          const signature = s.role === 'FINANCE' ? state.finance : state.actuary;
          const other = s.role === 'FINANCE' ? state.actuary : state.finance;
          return (
            <div key={s.role}>
              <dt className="text-xs text-muted-foreground">{s.label}</dt>
              <dd className="text-sm">
                {signature ? (
                  <>
                    <StaffName subject={signature.signedBy} /> on {formatInstantDay(signature.signedAt)} · {signature.reference}
                  </>
                ) : can(s.permission) && !(other && other.signedBy === viewer) ? (
                  <SignForm signer={s.role} onSigned={setState} />
                ) : (
                  'Not signed'
                )}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}

function SignForm({ signer, onSigned }: { signer: Signer; onSigned: (next: BaselineSignOffView) => void }) {
  const [reference, setReference] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function sign() {
    setBusy(true);
    setError(null);
    try {
      onSigned(await signOffBaseline(signer, reference.trim()));
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-1 space-y-2">
      {error && <InlineError error={error} />}
      <FormField required label="Sign-off reference">
        <Input inputSize="sm" placeholder="Board minute 14/2026" value={reference} onChange={(e) => setReference(e.target.value)} />
      </FormField>
      <Button size="sm" disabled={reference.trim() === '' || busy} pending={busy} onClick={() => void sign()}>
        {signer === 'FINANCE' ? 'Sign off as finance' : 'Sign off as actuary'}
      </Button>
    </div>
  );
}
