import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import {
  createAccountCharge,
  getChargeSetting,
  listAccountCharges,
  reinstateAccountCharge,
  setChargeSetting,
  withdrawAccountCharge,
  type AccountChargeView,
  type ChargeAmountType,
  type ChargeWhen,
} from '@/api/accountCharges';
import { readIdentity, staffRoles } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { CHARGE_WHEN, PERCENT_OF, chargeSize } from './chargeText';

const WHENS = Object.keys(CHARGE_WHEN) as ChargeWhen[];

/**
 * Account charges (2026-10-09, product V32): the charges staff choose from when opening a savings case or issuing its
 * policy -- each flat or a percentage, and taken at a moment of its own. Finance officers and admins keep the list and
 * the organisation's rule on the minimum balance; everyone on staff can read it.
 */
export function AccountChargesPage() {
  const roles = staffRoles(readIdentity(useAuth().user?.access_token));
  const canEdit = roles.FINANCE_OFFICER || roles.ADMIN;
  const [charges, setCharges] = useState<AccountChargeView[] | null>(null);
  const [belowMinimum, setBelowMinimum] = useState<boolean | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    Promise.all([listAccountCharges(), getChargeSetting()]).then(
      ([c, s]) => { if (live) { setCharges(c); setBelowMinimum(s.mayGoBelowMinimum); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  async function run(key: string, action: () => Promise<unknown>) {
    setBusy(key);
    setActionError(null);
    try {
      await action();
      setReload((n) => n + 1);
      return true;
    } catch (e) {
      setActionError(toApiError(e));
      return false;
    } finally {
      setBusy(null);
    }
  }

  const header = (
    <PageHeader title="Account charges"
      description="Charges for savings accounts. Choose them on a savings case or when issuing its policy; a policy with none chosen keeps its product's own charges." />
  );
  if (error) return <>{header}<div className="px-4 pt-4 sm:px-6"><ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} /></div></>;
  if (!charges || belowMinimum === null) return <LoadingBlock label="Loading charges" />;

  return (
    <>
      {header}
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        {actionError && <InlineError error={actionError} lead="Not saved" />}
        <Panel title="Minimum balance" subtitle="Applies to every savings account in the organisation">
          <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
            <span>
              {belowMinimum
                ? 'Charges may take an account below its product’s minimum balance.'
                : 'Charges stop at the product’s minimum balance, as withdrawals do.'}
            </span>
            {canEdit && (
              <Button size="sm" pending={busy === 'setting'}
                onClick={() => void run('setting', () => setChargeSetting(!belowMinimum))}>
                {belowMinimum ? 'Stop charges at the minimum' : 'Allow charges below the minimum'}
              </Button>
            )}
          </div>
        </Panel>

        {canEdit && <NewCharge onCreated={() => setReload((n) => n + 1)} />}

        <Panel title="Charges">
          {charges.length === 0 ? (
            <EmptyState title="No charges yet" description="Savings policies are charged by their product's own charges." />
          ) : (
            <ul className="divide-y divide-border">
              {charges.map((c) => (
                <li key={c.chargeId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">
                      {c.name}{!c.active && <span className="ml-2 text-xs font-normal text-muted-foreground">(withdrawn)</span>}
                    </span>
                    <span className="block text-xs text-muted-foreground">{CHARGE_WHEN[c.when]} · {chargeSize(c)}</span>
                    {c.description && <span className="block text-xs text-subtle-foreground">{c.description}</span>}
                  </span>
                  {canEdit && (c.active ? (
                    <Button size="sm" variant="ghost" pending={busy === c.chargeId}
                      onClick={() => void run(c.chargeId, () => withdrawAccountCharge(c.chargeId))}>Withdraw</Button>
                  ) : (
                    <Button size="sm" variant="ghost" pending={busy === c.chargeId}
                      onClick={() => void run(c.chargeId, () => reinstateAccountCharge(c.chargeId))}>Offer again</Button>
                  ))}
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </>
  );
}

function NewCharge({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState('');
  const [when, setWhen] = useState<ChargeWhen>('DEPOSIT');
  const [amountType, setAmountType] = useState<ChargeAmountType>('PERCENT');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('TZS');
  const [description, setDescription] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const value = Number(amount);
  const valid = name.trim() !== '' && amount.trim() !== '' && value > 0 && (amountType === 'FLAT' || value <= 100)
    && currency.trim().length === 3;

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await createAccountCharge({ name: name.trim(), description: description.trim() || null, when, amountType, amount: value,
        currency: currency.trim().toUpperCase() });
      setName(''); setAmount(''); setDescription('');
      onCreated();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Panel title="New charge">
      <div className="space-y-3 px-4 py-3">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="Name">
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Withdrawal fee" />
          </FormField>
          <FormField label="When it is taken">
            <Select value={when} onChange={(e) => setWhen(e.target.value as ChargeWhen)}>
              {WHENS.map((w) => <option key={w} value={w}>{CHARGE_WHEN[w]}</option>)}
            </Select>
          </FormField>
          <FormField label="Type">
            <Select value={amountType} onChange={(e) => setAmountType(e.target.value as ChargeAmountType)}>
              <option value="PERCENT">Percentage</option>
              <option value="FLAT">Flat amount</option>
            </Select>
          </FormField>
          <FormField label={amountType === 'PERCENT' ? 'Percentage' : 'Amount'}
            hint={amountType === 'PERCENT' ? `Percent ${PERCENT_OF[when]}` : `In ${currency || 'the currency below'}`}>
            <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </FormField>
          <FormField label="Currency">
            <Input value={currency} maxLength={3} onChange={(e) => setCurrency(e.target.value)} />
          </FormField>
          <FormField label="Description (optional)">
            <Input value={description} onChange={(e) => setDescription(e.target.value)} />
          </FormField>
        </div>
        {error && <InlineError error={error} lead="The charge was not created" />}
        <Button variant="primary" disabled={!valid} pending={saving} onClick={() => void save()}>Create charge</Button>
      </div>
    </Panel>
  );
}
