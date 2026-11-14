import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import {
  addShareholder,
  approveShareIssue,
  getCapital,
  listShareBankAccounts,
  listShareClasses,
  listShareholders,
  listShareIssues,
  proposeShareIssue,
  rejectShareIssue,
  setMinimumPaidUp,
  setShareNominal,
  updateShareholder,
  withdrawShareIssue,
} from '@/api/finaccounting';
import type {
  BankAccountView,
  CapitalCheck,
  CapitalView,
  ShareClassView,
  ShareholderInput,
  ShareholderView,
  ShareIssueView,
} from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DataTable, type Column } from '@/components/DataTable';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { formatDate, todayIso } from '@/lib/dates';
import { barredAsOwn, SIGNING_OWN } from '@/lib/ownWork';
import { monthName } from './monthEndSteps';
import { checkLabel, issueSplit, ownershipShare, tzs } from './shareRegister';

interface Register {
  capital: CapitalView;
  shareholders: ShareholderView[];
  classes: ShareClassView[];
  issues: ShareIssueView[];
  banks: BankAccountView[];
}

type Form = { kind: 'issue' } | { kind: 'shareholder'; editing: ShareholderView | null } | null;

/**
 * Who owns the company (2026-11-14, shareholders register). The paid-up capital is the lit figure; the three checks
 * say whether the register agrees with the books, whether Tanzanians hold at least one-third, and whether capital
 * reaches the minimum finance entered. An issue of shares posts nothing until a second person approves it.
 */
export function ShareholdersPage() {
  const [register, setRegister] = useState<Register | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [form, setForm] = useState<Form>(null);
  const manage = can('ledger.manage');

  const load = useCallback(() => {
    Promise.all([getCapital(), listShareholders(), listShareClasses(), listShareIssues(), listShareBankAccounts()]).then(
      ([capital, shareholders, classes, issues, banks]) => {
        setRegister({ capital, shareholders, classes, issues, banks });
        setError(null);
      },
      (cause: ApiError) => setError(cause),
    );
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function done() {
    setForm(null);
    load();
  }

  if (register === null) {
    return (
      <div className="space-y-4">
        <PageHeader title="Shareholders" />
        {error ? <ErrorPanel error={error} onRetry={load} /> : <LoadingBlock />}
      </div>
    );
  }

  const ordinary = register.classes.find((c) => c.classCode === 'ORDINARY') ?? null;
  const waiting = register.issues.filter((i) => i.status === 'PROPOSED');
  const decided = register.issues.filter((i) => i.status !== 'PROPOSED');

  return (
    <div className="space-y-4">
      <PageHeader
        title="Shareholders"
        actions={
          manage && (
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setForm({ kind: 'shareholder', editing: null })}>
                Add shareholder
              </Button>
              <Button
                variant="primary"
                disabled={ordinary === null || register.shareholders.length === 0}
                onClick={() => setForm({ kind: 'issue' })}
              >
                Record an issue
              </Button>
            </div>
          )
        }
      />
      {error && <ErrorPanel error={error} onRetry={load} />}

      {ordinary === null && manage && <NominalPrompt onSaved={load} />}

      {form?.kind === 'shareholder' && (
        <ShareholderForm editing={form.editing} onSaved={done} onCancel={() => setForm(null)} />
      )}
      {form?.kind === 'issue' && (
        <IssueForm register={register} onSaved={done} onCancel={() => setForm(null)} />
      )}

      <CapitalPanel capital={register.capital} onChanged={load} />

      {waiting.length > 0 && (
        <Panel title="Waiting for approval" emphasis>
          <ul className="divide-y divide-border">
            {waiting.map((i) => (
              <li key={i.issueId}>
                <IssueDecision issue={i} onDecided={load} />
              </li>
            ))}
          </ul>
        </Panel>
      )}

      <Panel title="Register">
        {register.shareholders.length === 0 ? (
          <div className="px-4 pb-4">
            <EmptyState title="No shareholders yet" description="Add the company's shareholders, then record their shares." />
          </div>
        ) : (
          <DataTable
            caption="Shareholders"
            rows={register.shareholders}
            rowKey={(s) => s.shareholderId}
            columns={shareholderColumns(register.capital.issuedShares, manage, (s) =>
              setForm({ kind: 'shareholder', editing: s }),
            )}
          />
        )}
      </Panel>

      {decided.length > 0 && (
        <Panel title="Issues decided">
          <DataTable caption="Issues decided" rows={decided} rowKey={(i) => i.issueId} columns={ISSUE_COLUMNS} />
        </Panel>
      )}
    </div>
  );
}

// ---- capital ------------------------------------------------------------------------------------------------------

function CapitalPanel({ capital, onChanged }: { capital: CapitalView; onChanged: () => void }) {
  const [editing, setEditing] = useState(false);
  const checks: { label: string; check: CapitalCheck; prefix: string }[] = [
    { label: 'Register agrees with the books', check: capital.books, prefix: 'Difference ' },
    { label: 'Tanzanian ownership, at least one-third', check: capital.tanzanian, prefix: 'Held ' },
    { label: 'Minimum paid-up capital', check: capital.minimum, prefix: 'Short by TZS ' },
  ];
  return (
    <Panel title="Capital">
      <div className="space-y-3 px-4 pb-4">
        <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-xl bg-hero p-5 sm:grid-cols-3">
          <div className="flex min-w-0 flex-col gap-1">
            <dt className="text-sm text-muted-foreground">Paid-up capital</dt>
            <dd className="min-w-0 whitespace-nowrap text-display">{tzs(capital.paidUpCapital)}</dd>
          </div>
          <div className="flex min-w-0 flex-col gap-1">
            <dt className="text-sm text-muted-foreground">Shares issued</dt>
            <dd className="min-w-0 whitespace-nowrap text-display">{capital.issuedShares.toLocaleString('en-US')}</dd>
          </div>
          <div className="flex min-w-0 flex-col gap-1">
            <dt className="text-sm text-muted-foreground">Minimum paid-up capital</dt>
            <dd className="min-w-0 whitespace-nowrap text-display">
              {capital.minimumPaidUp == null ? 'Not set' : tzs(capital.minimumPaidUp)}
            </dd>
          </div>
        </dl>
        <ul className="divide-y divide-border rounded-lg border border-border">
          {checks.map(({ label, check, prefix }) => (
            <li key={label} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-3 py-2 text-sm">
              <span>{label}</span>
              <span className="flex items-center gap-3">
                {check.figure && (
                  <span className="tabular-nums text-muted-foreground">{prefix}{check.figure}</span>
                )}
                <StatusBadge kind="capitalCheck" value={check.state} label={checkLabel(check.state)} />
              </span>
            </li>
          ))}
        </ul>
        {can('ledger.manage') &&
          (editing ? (
            <MinimumForm
              current={capital.minimumPaidUp ?? null}
              onSaved={() => {
                setEditing(false);
                onChanged();
              }}
              onCancel={() => setEditing(false)}
            />
          ) : (
            <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
              {capital.minimumPaidUp == null ? 'Set the minimum' : 'Change the minimum'}
            </Button>
          ))}
      </div>
    </Panel>
  );
}

function MinimumForm({ current, onSaved, onCancel }: { current: number | null; onSaved: () => void; onCancel: () => void }) {
  const [value, setValue] = useState(current == null ? '' : String(current));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const amount = value.trim() === '' ? null : Number(value);
  const invalid = amount !== null && !(amount > 0);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await setMinimumPaidUp(amount);
      onSaved();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-wrap items-end gap-2">
      <FormField label="Minimum paid-up capital (TZS)" hint="TIRA's figure. Leave empty for Not set.">
        <Input inputSize="sm" inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} />
      </FormField>
      <Button size="sm" disabled={invalid || busy} pending={busy} onClick={() => void save()}>
        Save
      </Button>
      <Button size="sm" variant="ghost" onClick={onCancel}>
        Cancel
      </Button>
      {error && <InlineError error={error} />}
    </div>
  );
}

function NominalPrompt({ onSaved }: { onSaved: () => void }) {
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const amount = Number(value);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await setShareNominal('ORDINARY', amount);
      onSaved();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Set the nominal value of ordinary shares" emphasis>
      <div className="flex flex-wrap items-end gap-2 px-4 pb-4">
        <FormField label="Nominal value per share (TZS)" hint="Fixed once shares are issued.">
          <Input inputSize="sm" inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} />
        </FormField>
        <Button size="sm" disabled={!(amount > 0) || busy} pending={busy} onClick={() => void save()}>
          Save
        </Button>
        {error && <InlineError error={error} />}
      </div>
    </Panel>
  );
}

// ---- shareholders -------------------------------------------------------------------------------------------------

function shareholderColumns(
  issued: number,
  manage: boolean,
  onEdit: (s: ShareholderView) => void,
): Column<ShareholderView>[] {
  const columns: Column<ShareholderView>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (s) => (
        <span className="flex flex-col">
          <span className="font-medium">{s.name}</span>
          <span className="text-xs text-muted-foreground">
            {s.kind === 'COMPANY' ? 'Company' : 'Person'} · {s.tanzanian ? 'Tanzanian' : 'Foreign'}
          </span>
        </span>
      ),
    },
    { key: 'id', header: 'ID or registration', render: (s) => s.idNumber, nowrap: true, secondary: true },
    { key: 'shares', header: 'Shares', render: (s) => s.shares.toLocaleString('en-US'), align: 'right' },
    { key: 'share', header: 'Of issued', render: (s) => ownershipShare(s.shares, issued), align: 'right' },
    { key: 'paid', header: 'Paid', render: (s) => tzs(s.paid), align: 'right' },
  ];
  if (manage) {
    columns.push({
      key: 'edit',
      header: <span className="sr-only">Edit</span>,
      render: (s) => (
        <Button size="sm" variant="ghost" onClick={() => onEdit(s)} aria-label={`Edit ${s.name}`}>
          Edit
        </Button>
      ),
      align: 'right',
    });
  }
  return columns;
}

function ShareholderForm({
  editing,
  onSaved,
  onCancel,
}: {
  editing: ShareholderView | null;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [input, setInput] = useState<ShareholderInput>(() => ({
    name: editing?.name ?? '',
    kind: editing?.kind ?? 'PERSON',
    tanzanian: editing?.tanzanian ?? true,
    idNumber: editing?.idNumber ?? '',
    phone: editing?.phone ?? null,
    address: editing?.address ?? null,
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const ready = input.name.trim() !== '' && input.idNumber.trim() !== '';

  async function save() {
    setBusy(true);
    setError(null);
    try {
      if (editing) await updateShareholder(editing.shareholderId, input);
      else await addShareholder(input);
      onSaved();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title={editing ? `Edit ${editing.name}` : 'Add a shareholder'} emphasis>
      <div className="grid gap-3 px-4 pb-4 sm:grid-cols-2">
        <FormField required label="Name">
          <Input value={input.name} onChange={(e) => setInput({ ...input, name: e.target.value })} />
        </FormField>
        <FormField required label="ID or company registration number">
          <Input value={input.idNumber} onChange={(e) => setInput({ ...input, idNumber: e.target.value })} />
        </FormField>
        <FormField required label="Person or company">
          <Select value={input.kind} onChange={(e) => setInput({ ...input, kind: e.target.value as ShareholderInput['kind'] })}>
            <option value="PERSON">Person</option>
            <option value="COMPANY">Company</option>
          </Select>
        </FormField>
        <FormField required label="Tanzanian or foreign">
          <Select
            value={input.tanzanian ? 'yes' : 'no'}
            onChange={(e) => setInput({ ...input, tanzanian: e.target.value === 'yes' })}
          >
            <option value="yes">Tanzanian</option>
            <option value="no">Foreign</option>
          </Select>
        </FormField>
        <FormField label="Phone">
          <Input value={input.phone ?? ''} onChange={(e) => setInput({ ...input, phone: e.target.value || null })} />
        </FormField>
        <FormField label="Address">
          <Input value={input.address ?? ''} onChange={(e) => setInput({ ...input, address: e.target.value || null })} />
        </FormField>
        <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
          <Button variant="primary" disabled={!ready || busy} pending={busy} onClick={() => void save()}>
            {editing ? 'Save changes' : 'Add shareholder'}
          </Button>
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          {error && <InlineError error={error} />}
        </div>
      </div>
    </Panel>
  );
}

// ---- issues -------------------------------------------------------------------------------------------------------

function IssueForm({ register, onSaved, onCancel }: { register: Register; onSaved: () => void; onCancel: () => void }) {
  const [shareholderId, setShareholderId] = useState(register.shareholders[0]?.shareholderId ?? '');
  const [classCode, setClassCode] = useState<ShareClassView['classCode']>(register.classes[0]?.classCode ?? 'ORDINARY');
  const [shares, setShares] = useState('');
  const [price, setPrice] = useState('');
  const [paid, setPaid] = useState('');
  const [receivedOn, setReceivedOn] = useState<string | null>(todayIso());
  const [bank, setBank] = useState(register.banks[0]?.code ?? '');
  const [reference, setReference] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const nominal = register.classes.find((c) => c.classCode === classCode)?.nominalValue ?? 0;
  const count = Number(shares);
  const perShare = Number(price);
  const split = count > 0 && perShare > 0 ? issueSplit(count, nominal, perShare) : null;
  const amount = paid.trim() === '' ? (split?.total ?? 0) : Number(paid);
  const ready =
    shareholderId !== '' && Number.isInteger(count) && count > 0 && perShare > 0 && amount > 0 && receivedOn !== null
    && bank !== '' && reference.trim() !== '';

  async function save() {
    if (receivedOn === null) return;
    setBusy(true);
    setError(null);
    try {
      await proposeShareIssue({
        shareholderId,
        classCode,
        shares: count,
        pricePerShare: perShare,
        amountPaid: amount,
        receivedOn,
        bankAccount: bank,
        reference: reference.trim(),
      });
      onSaved();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Record an issue of shares" subtitle="Nothing posts until a second person approves it." emphasis>
      <div className="grid gap-3 px-4 pb-4 sm:grid-cols-2">
        <FormField required label="Shareholder">
          <Select value={shareholderId} onChange={(e) => setShareholderId(e.target.value)}>
            {register.shareholders.map((s) => (
              <option key={s.shareholderId} value={s.shareholderId}>
                {s.name}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField required label="Class">
          <Select value={classCode} onChange={(e) => setClassCode(e.target.value as ShareClassView['classCode'])}>
            {register.classes.map((c) => (
              <option key={c.classCode} value={c.classCode}>
                {c.classCode === 'ORDINARY' ? 'Ordinary' : 'Preference'} · nominal {tzs(c.nominalValue)}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField required label="Number of shares">
          <Input inputMode="numeric" value={shares} onChange={(e) => setShares(e.target.value)} />
        </FormField>
        <FormField required label="Price per share (TZS)" hint={`At least the nominal value, ${tzs(nominal)}.`}>
          <Input inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} />
        </FormField>
        <FormField required label="Amount paid (TZS)" hint="Shares × price, paid in full.">
          <Input
            inputMode="decimal"
            value={paid}
            placeholder={split ? split.total.toFixed(2) : ''}
            onChange={(e) => setPaid(e.target.value)}
          />
        </FormField>
        <FormField required label="Date the money was received">
          <DatePicker value={receivedOn} onChange={setReceivedOn} disabled={{ after: new Date() }} />
        </FormField>
        <FormField required label="Paid into">
          <Select value={bank} onChange={(e) => setBank(e.target.value)}>
            {register.banks.map((b) => (
              <option key={b.code} value={b.code}>
                {b.code} {b.name}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField required label="Board resolution or allotment">
          <Input value={reference} maxLength={200} onChange={(e) => setReference(e.target.value)} />
        </FormField>
        <p className="text-sm tabular-nums sm:col-span-2" aria-live="polite">
          {split ? (
            <>
              <span className="text-muted-foreground">Share capital </span>
              {tzs(split.capital)}
              <span className="text-muted-foreground"> · Share premium </span>
              {tzs(split.premium)}
            </>
          ) : (
            <span className="text-muted-foreground">Enter the shares and the price to see the split.</span>
          )}
        </p>
        <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
          <Button variant="primary" disabled={!ready || busy} pending={busy} onClick={() => void save()}>
            Record issue
          </Button>
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          {error && <InlineError error={error} />}
        </div>
      </div>
    </Panel>
  );
}

/** One waiting issue: approve or reject by a second person; the recorder may withdraw it. */
function IssueDecision({ issue, onDecided }: { issue: ShareIssueView; onDecided: () => void }) {
  const viewer = readIdentity(useAuth().user?.access_token)?.subject ?? undefined;
  const [rejecting, setRejecting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const mine = viewer != null && viewer === issue.proposedBy;
  const barred = barredAsOwn(viewer, issue.proposedBy, 'ledger');

  async function act(work: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await work();
      onDecided();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2 px-4 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <p className="text-sm">
          <span className="font-medium">{issue.shares.toLocaleString('en-US')}</span>{' '}
          {issue.classCode === 'ORDINARY' ? 'ordinary' : 'preference'} shares to{' '}
          <span className="font-medium">{issue.shareholderName}</span> at {tzs(issue.pricePerShare)}
        </p>
        <p className="text-sm font-medium tabular-nums">{tzs(issue.amountPaid)}</p>
      </div>
      <p className="text-xs text-muted-foreground">
        Received {formatDate(issue.receivedOn)} into {issue.bankAccount} · {issue.reference} · capital{' '}
        {tzs(issue.shareCapital)}, premium {tzs(issue.sharePremium)} · recorded by <StaffName subject={issue.proposedBy} />
      </p>
      {error && <InlineError error={error} />}
      {confirming && (
        <ConfirmAct
          heading="Post this issue of shares?"
          consequence={
            <>
              Debit {issue.bankAccount} {tzs(issue.amountPaid)}; credit share capital {tzs(issue.shareCapital)}
              {issue.sharePremium > 0 && <> and share premium {tzs(issue.sharePremium)}</>}, in{' '}
              {monthName(issue.receivedOn.slice(0, 7))}.
            </>
          }
          reversal="A posted journal is never changed; a mistake is corrected with a manual journal."
          confirmLabel="Post to the ledger"
          busy={busy}
          onConfirm={() => void act(() => approveShareIssue(issue.issueId))}
          onCancel={() => setConfirming(false)}
        />
      )}
      <div className="flex flex-wrap items-center gap-2">
        {can('ledger.approve') && !rejecting && !confirming && (
          <>
            {barred && <span className="text-xs text-muted-foreground">You recorded it, so someone else must approve it.</span>}
            {mine && !barred && <span className="text-xs text-muted-foreground">{SIGNING_OWN}</span>}
            <Button size="sm" variant="primary" disabled={barred || busy} onClick={() => setConfirming(true)}>
              Approve and post
            </Button>
            {!mine && (
              <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRejecting(true)}>
                Reject
              </Button>
            )}
          </>
        )}
        {mine && can('ledger.manage') && !rejecting && !confirming && (
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => void act(() => withdrawShareIssue(issue.issueId))}>
            Withdraw
          </Button>
        )}
        {rejecting && (
          <div className="flex w-full flex-wrap items-end gap-2">
            <FormField required label="Why is it rejected?">
              <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
            </FormField>
            <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
              onClick={() => void act(() => rejectShareIssue(issue.issueId, reason.trim()))}>
              Reject issue
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>
              Cancel
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}

const ISSUE_COLUMNS: Column<ShareIssueView>[] = [
  { key: 'received', header: 'Received', render: (i) => formatDate(i.receivedOn), nowrap: true },
  { key: 'holder', header: 'Shareholder', render: (i) => i.shareholderName },
  { key: 'shares', header: 'Shares', render: (i) => i.shares.toLocaleString('en-US'), align: 'right' },
  { key: 'amount', header: 'Amount', render: (i) => tzs(i.amountPaid), align: 'right' },
  {
    key: 'status',
    header: 'Status',
    render: (i) => (
      <span className="flex flex-col items-start gap-0.5">
        <StatusBadge kind="shareIssue" value={i.status} />
        {i.decisionReason && <span className="text-xs text-muted-foreground">{i.decisionReason}</span>}
      </span>
    ),
  },
  {
    key: 'journal',
    header: 'Journal',
    render: (i) =>
      i.journalEntryId ? (
        <Link className="text-sm underline-offset-2 hover:underline" to={`/staff/gl-postings/${i.journalEntryId}`}>
          View
        </Link>
      ) : (
        <span className="text-subtle-foreground">—</span>
      ),
    secondary: true,
  },
];
