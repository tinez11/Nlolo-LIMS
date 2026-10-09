import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import type { FundPriceView, FundView, PriceAdjustmentView } from '@/api/types';
import { canAuthorProducts, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { approvePriceGates, decideAdjustmentGates } from '@/gates/unitLinkedGates';
import { formatDate, formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useUnitLinkedStore } from '@/store/unitLinkedStore';
import {
  ASSET_CLASSES,
  ASSET_CLASS_LABELS,
  correctionSchema,
  createFundSchema,
  movePercent,
  proposePriceSchema,
  type CorrectionValues,
  type CreateFundValues,
  type ProposePriceValues,
} from './fundForms';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/** The alert threshold the server applies (refdata UL_PRICE_MOVE_ALERT_PERCENT); the server's own figure decides. */
const MOVE_ALERT_PERCENT = 10;

/**
 * The fund register (product step 6). One person proposes each day's price and a second approves it,
 * after the fund's cut-off -- an approval prices every order waiting for that date. A missing price is
 * never filled from an older one: orders wait, and this screen says how many. An approved price that was
 * wrong is corrected, never edited; where the correction moves money already paid out, the difference
 * lands in the adjustment queue below for a second person to settle or waive.
 */
export function FundsPage() {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const viewerSubject = identity?.subject ?? undefined;
  const isAdmin = canAuthorProducts(identity);
  const funds = useUnitLinkedStore((s) => s.funds);
  const loadFunds = useUnitLinkedStore((s) => s.loadFunds);
  const loadAdjustments = useUnitLinkedStore((s) => s.loadAdjustments);
  const loadReconciliation = useUnitLinkedStore((s) => s.loadReconciliation);

  useEffect(() => {
    void loadFunds();
    void loadAdjustments();
    void loadReconciliation();
  }, [loadFunds, loadAdjustments, loadReconciliation]);

  function renderFunds() {
    if (isInitialLoad(funds)) return <LoadingBlock />;
    if (funds.status === 'error' && funds.error && funds.data === null) {
      return <ErrorPanel error={funds.error} onRetry={() => void loadFunds()} />;
    }
    const rows = funds.data ?? [];
    if (rows.length === 0) {
      return <EmptyState title="No fund" description="A unit-linked product can offer only funds in this register." />;
    }
    return (
      <div className="space-y-3" role="list" aria-label="Funds">
        {rows.map((fund) => (
          <FundCard key={fund.fundId} fund={fund} viewerSubject={viewerSubject} isAdmin={isAdmin} />
        ))}
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title="Funds"
        description="The fund register and its daily prices. One person proposes a price, a second approves it after the cut-off."
      />
      <div className="space-y-4 px-6 pb-8">
        {renderFunds()}
        <CreateFundForm />
        <AdjustmentQueue viewerSubject={viewerSubject} />
        <ReconciliationPanel />
      </div>
    </>
  );
}

function FundCard({ fund, viewerSubject, isAdmin }: { fund: FundView; viewerSubject: string | undefined; isAdmin: boolean }) {
  const [open, setOpen] = useState(false);
  const loadFund = useUnitLinkedStore((s) => s.loadFund);
  const closeFund = useUnitLinkedStore((s) => s.closeFund);
  const acting = useUnitLinkedStore((s) => s.acting[`fund.${fund.code}`]);

  useEffect(() => {
    if (open) void loadFund(fund.code);
  }, [open, fund.code, loadFund]);

  return (
    <section role="listitem" aria-label={`Fund ${fund.code}`} className="rounded-lg border border-border bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-sm font-medium">
            <span className="font-mono">{fund.code}</span> · {fund.name} <StatusBadge kind="fund" value={fund.status} />
          </p>
          <p className="text-xs text-muted-foreground">
            {ASSET_CLASS_LABELS[fund.assetClass]} · {fund.currency} · management charge {fund.annualManagementChargePercent}% a year,
            in the price · cut-off {fund.cutOffTime.slice(0, 5)} EAT
          </p>
        </div>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
            {open ? 'Hide prices' : 'Prices'}
          </Button>
          {isAdmin && fund.status === 'OPEN' && (
            <Button size="sm" variant="ghost" disabled={acting?.status === 'loading'} onClick={() => void closeFund(fund.code)}>
              Close to new money
            </Button>
          )}
        </div>
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {open && <FundPrices fund={fund} viewerSubject={viewerSubject} />}
    </section>
  );
}

function FundPrices({ fund, viewerSubject }: { fund: FundView; viewerSubject: string | undefined }) {
  const prices = useUnitLinkedStore((s) => s.prices[fund.code]);
  const waiting = useUnitLinkedStore((s) => s.waiting[fund.code]);
  const loadFund = useUnitLinkedStore((s) => s.loadFund);

  if (!prices || isInitialLoad(prices)) return <LoadingBlock />;
  if (prices.status === 'error' && prices.error && prices.data === null) {
    return <ErrorPanel error={prices.error} onRetry={() => void loadFund(fund.code)} />;
  }
  const rows = prices.data ?? [];
  const lastApproved = rows.find((p) => p.status === 'APPROVED');
  const waitingRows = waiting?.data ?? [];

  return (
    <div className="mt-3 space-y-3">
      {waitingRows.length > 0 ? (
        <p className="text-xs" aria-label={`Waiting orders for ${fund.code}`}>
          Waiting for a price:{' '}
          {waitingRows.map((w) => `${w.orders} order${w.orders === 1 ? '' : 's'} for ${formatDate(w.boundDate)}`).join(', ')}
        </p>
      ) : (
        <p className="text-xs text-muted-foreground">No order is waiting for a price.</p>
      )}
      {rows.length === 0 ? (
        <EmptyState title="No price yet" description="Nothing is bought or sold in this fund until its first price is approved." />
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-sm" aria-label={`Prices of ${fund.code}`}>
            <thead className="text-left text-xs text-muted-foreground">
              <tr>
                <th className="px-3 py-2 font-medium">Valuation date</th>
                <th className="px-3 py-2 font-medium">Price</th>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2 font-medium">Proposed / approved</th>
                <th className="px-3 py-2 font-medium">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.map((price) => (
                <PriceRow key={price.priceId} price={price} fund={fund} viewerSubject={viewerSubject} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {fund.status === 'OPEN' && <ProposePriceForm fund={fund} lastApproved={lastApproved} />}
    </div>
  );
}

function PriceRow({ price, fund, viewerSubject }: { price: FundPriceView; fund: FundView; viewerSubject: string | undefined }) {
  const [correcting, setCorrecting] = useState(false);
  const approve = useUnitLinkedStore((s) => s.approvePrice);
  const withdraw = useUnitLinkedStore((s) => s.withdrawPrice);
  const acting = useUnitLinkedStore((s) => s.acting[price.priceId]);
  const gates = approvePriceGates(price, fund, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);
  const busy = acting?.status === 'loading';

  return (
    <>
      <tr>
        <td className="px-3 py-2">{formatDate(price.valuationDate)}</td>
        <td className="px-3 py-2 font-mono">{price.price}</td>
        <td className="px-3 py-2">
          <StatusBadge kind="fundPrice" value={price.status} />
          {price.supersedesPriceId && <span className="ml-1 text-xs text-muted-foreground">correction</span>}
        </td>
        <td className="px-3 py-2 text-xs text-muted-foreground">
          {price.proposedBy}
          {price.approvedBy ? ` · ${price.approvedBy}, ${formatInstant(price.approvedAt)}` : ''}
          {price.moveReason ? <span className="block">Move: {price.moveReason}</span> : null}
        </td>
        <td className="px-3 py-2">
          {price.status === 'PROPOSED' && (
            <div className="flex gap-2">
              <Button size="sm" disabled={refused || busy} onClick={() => void approve(price)}>
                Approve
              </Button>
              <Button size="sm" variant="ghost" disabled={busy} onClick={() => void withdraw(price)}>
                Withdraw
              </Button>
            </div>
          )}
          {price.status === 'APPROVED' && (
            <Button size="sm" variant="ghost" aria-expanded={correcting} onClick={() => setCorrecting((v) => !v)}>
              Correct
            </Button>
          )}
        </td>
      </tr>
      {(price.status === 'PROPOSED' || acting?.status === 'error') && (
        <tr>
          <td colSpan={5} className="px-3 pb-2">
            {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
            {price.status === 'PROPOSED' && <GatePanel gates={gates} title="Before approving" />}
          </td>
        </tr>
      )}
      {correcting && price.status === 'APPROVED' && (
        <tr>
          <td colSpan={5} className="px-3 pb-3">
            <CorrectionForm price={price} onDone={() => setCorrecting(false)} />
          </td>
        </tr>
      )}
    </>
  );
}

function ProposePriceForm({ fund, lastApproved }: { fund: FundView; lastApproved: FundPriceView | undefined }) {
  const propose = useUnitLinkedStore((s) => s.proposePrice);
  const acting = useUnitLinkedStore((s) => s.acting[`price.propose.${fund.code}`]);
  const form = useForm<ProposePriceValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(proposePriceSchema),
    defaultValues: { valuationDate: '', price: '', moveReason: '' },
  });
  const typed = useWatch({ control: form.control, name: 'price' }) ?? '';
  const move = movePercent(typed, lastApproved?.price);
  const bigMove = move !== null && Math.abs(move) > MOVE_ALERT_PERCENT;

  return (
    <form
      aria-label={`Propose a price for ${fund.code}`}
      className="space-y-2 rounded-md border border-border p-3"
      onSubmit={form.handleSubmit((v) =>
        void propose({
          fundCode: fund.code,
          valuationDate: v.valuationDate,
          price: v.price,
          moveReason: v.moveReason === '' ? null : v.moveReason,
        }).then(() => {
          if (useUnitLinkedStore.getState().acting[`price.propose.${fund.code}`]?.status === 'success') form.reset();
        }),
      )}
    >
      <p className="text-xs font-medium">Propose a price</p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <FormField label="Valuation date" error={form.formState.errors.valuationDate?.message}>
          <Controller
            control={form.control}
            name="valuationDate"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
        <FormField label={`Price (${fund.currency} per unit)`} error={form.formState.errors.price?.message}>
          <Input inputMode="decimal" placeholder="1.000000" {...form.register('price')} />
        </FormField>
        <FormField
          label={bigMove ? 'Why it moved (required)' : 'Why it moved (optional)'}
          error={form.formState.errors.moveReason?.message}
        >
          <Input {...form.register('moveReason')} />
        </FormField>
      </div>
      {move !== null && (
        <p className={bigMove ? 'text-xs text-status-warning-fg' : 'text-xs text-muted-foreground'}>
          {move >= 0 ? '+' : ''}
          {move.toFixed(2)}% against the last approved price {lastApproved?.price}
          {bigMove ? ` — more than ${MOVE_ALERT_PERCENT}%, so the server asks why.` : '.'}
        </p>
      )}
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Propose price
      </Button>
    </form>
  );
}

function CorrectionForm({ price, onDone }: { price: FundPriceView; onDone: () => void }) {
  const correct = useUnitLinkedStore((s) => s.proposeCorrection);
  const acting = useUnitLinkedStore((s) => s.acting[`correct.${price.priceId}`]);
  const form = useForm<CorrectionValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(correctionSchema),
    defaultValues: { price: '', reason: '' },
  });
  return (
    <form
      aria-label={`Correct the price for ${price.valuationDate}`}
      className="space-y-2 rounded-md border border-border p-3"
      onSubmit={form.handleSubmit((v) =>
        void correct(price, v.price, v.reason).then(() => {
          if (useUnitLinkedStore.getState().acting[`correct.${price.priceId}`]?.status === 'success') onDone();
        }),
      )}
    >
      <p className="text-xs text-muted-foreground">
        A second person approves the correction. Everything this price priced is then re-priced; a payout already made
        becomes an adjustment, never a silent change.
      </p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <FormField label="Corrected price" error={form.formState.errors.price?.message}>
          <Input inputMode="decimal" {...form.register('price')} />
        </FormField>
        <FormField label="Reason" error={form.formState.errors.reason?.message}>
          <Input {...form.register('reason')} />
        </FormField>
      </div>
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Propose correction
      </Button>
    </form>
  );
}

function CreateFundForm() {
  const create = useUnitLinkedStore((s) => s.createFund);
  const acting = useUnitLinkedStore((s) => s.acting['fund.create']);
  const form = useForm<CreateFundValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(createFundSchema),
    defaultValues: {
      code: '',
      name: '',
      currency: 'TZS',
      assetClass: undefined as unknown as CreateFundValues['assetClass'],
      annualManagementChargePercent: '',
      cutOffTime: '16:00',
    },
  });
  const errors = form.formState.errors;
  return (
    <form
      aria-label="Add a fund"
      className="space-y-3 rounded-lg border border-border bg-surface p-4"
      onSubmit={form.handleSubmit((v) =>
        void create({ ...v, cutOffTime: `${v.cutOffTime}:00` }).then(() => {
          if (useUnitLinkedStore.getState().acting['fund.create']?.status === 'success') form.reset();
        }),
      )}
    >
      <p className="text-xs font-medium">Add a fund</p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <FormField label="Code" error={errors.code?.message}>
          <Input placeholder="EQ-GROWTH" {...form.register('code')} />
        </FormField>
        <FormField label="Name" error={errors.name?.message}>
          <Input placeholder="Equity growth fund" {...form.register('name')} />
        </FormField>
        <FormField label="Currency" error={errors.currency?.message}>
          <Input {...form.register('currency')} />
        </FormField>
        <FormField label="Asset class" error={errors.assetClass?.message}>
          <Select {...form.register('assetClass')}>
            <option value="">Choose…</option>
            {ASSET_CLASSES.map((c) => (
              <option key={c} value={c}>
                {ASSET_CLASS_LABELS[c]}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="Management charge (% a year)" error={errors.annualManagementChargePercent?.message}>
          <Input inputMode="decimal" placeholder="1.5" {...form.register('annualManagementChargePercent')} />
        </FormField>
        <FormField label="Daily cut-off (EAT)" error={errors.cutOffTime?.message}>
          <Input placeholder="16:00" {...form.register('cutOffTime')} />
        </FormField>
      </div>
      <p className="text-xs text-subtle-foreground">
        The management charge is built into the price; it is recorded here for disclosure, never deducted again.
      </p>
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Add fund
      </Button>
    </form>
  );
}

const DIRECTION_LABEL: Record<string, string> = {
  OWED_TO_CUSTOMER: 'Owed to the customer',
  OWED_BY_CUSTOMER: 'Owed by the customer',
};

function AdjustmentQueue({ viewerSubject }: { viewerSubject: string | undefined }) {
  const adjustments = useUnitLinkedStore((s) => s.adjustments);
  const loadAdjustments = useUnitLinkedStore((s) => s.loadAdjustments);

  let body;
  if (isInitialLoad(adjustments)) body = <LoadingBlock />;
  else if (adjustments.status === 'error' && adjustments.error && adjustments.data === null) {
    body = <ErrorPanel error={adjustments.error} onRetry={() => void loadAdjustments()} />;
  } else if ((adjustments.data ?? []).length === 0) {
    body = <EmptyState title="No adjustment" description="No price correction has moved money already paid out." />;
  } else {
    body = (
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Price adjustments">
        {(adjustments.data ?? []).map((a) => (
          <AdjustmentRow key={a.adjustmentId} adjustment={a} viewerSubject={viewerSubject} />
        ))}
      </div>
    );
  }
  return (
    <section aria-label="Price-correction adjustments" className="space-y-2 rounded-lg border border-border bg-surface p-4">
      <p className="text-sm font-medium">Price-correction adjustments</p>
      <p className="text-xs text-muted-foreground">
        A correction that moves money already paid out is never taken back silently: it waits here to be paid, recorded as
        collected, or waived with a reason, by someone other than whoever approved the correction.
      </p>
      {body}
    </section>
  );
}

function AdjustmentRow({ adjustment, viewerSubject }: { adjustment: PriceAdjustmentView; viewerSubject: string | undefined }) {
  const settle = useUnitLinkedStore((s) => s.settleAdjustment);
  const waive = useUnitLinkedStore((s) => s.waiveAdjustment);
  const acting = useUnitLinkedStore((s) => s.acting[adjustment.adjustmentId]);
  const [payee, setPayee] = useState('');
  const [reason, setReason] = useState('');
  const gates = decideAdjustmentGates(adjustment, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);
  const busy = acting?.status === 'loading';
  const owedToCustomer = adjustment.direction === 'OWED_TO_CUSTOMER';

  return (
    <div role="listitem" className="space-y-2 px-4 py-2.5">
      <p className="text-sm">
        <Link className="font-mono text-xs underline" to={`/staff/policies/${encodeURIComponent(adjustment.policyNumber)}`}>
          {adjustment.policyNumber}
        </Link>{' '}
        · {DIRECTION_LABEL[adjustment.direction]} {adjustment.amount}{' '}
        <StatusBadge kind="priceAdjustment" value={adjustment.status} />
      </p>
      {adjustment.decidedBy && (
        <p className="text-xs text-muted-foreground">
          {adjustment.status === 'WAIVED' ? 'Waived' : 'Settled'} by {adjustment.decidedBy}, {formatInstant(adjustment.decidedAt)}
          {adjustment.reason ? ` — ${adjustment.reason}` : ''}
        </p>
      )}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {adjustment.status === 'OPEN' && (
        <>
          <GatePanel gates={gates} title="Before deciding" />
          <div className="flex flex-wrap items-end gap-2">
            <FormField label={owedToCustomer ? 'Pay to (mobile money or account)' : 'Collected under reference'}>
              <Input inputSize="sm" value={payee} onChange={(e) => setPayee(e.target.value)} />
            </FormField>
            <Button size="sm" disabled={refused || busy || payee.trim() === ''} onClick={() => void settle(adjustment.adjustmentId, payee.trim())}>
              {owedToCustomer ? 'Pay' : 'Record as collected'}
            </Button>
            <FormField label="Waiver reason">
              <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
            </FormField>
            <Button
              size="sm"
              variant="ghost"
              disabled={refused || busy || reason.trim() === ''}
              onClick={() => void waive(adjustment.adjustmentId, reason.trim())}
            >
              Waive
            </Button>
          </div>
        </>
      )}
    </div>
  );
}

function ReconciliationPanel() {
  const rec = useUnitLinkedStore((s) => s.reconciliation);
  const load = useUnitLinkedStore((s) => s.loadReconciliation);
  if (isInitialLoad(rec)) return <LoadingBlock />;
  if (rec.status === 'error' && rec.error && rec.data === null) {
    return <ErrorPanel error={rec.error} onRetry={() => void load()} />;
  }
  const data = rec.data;
  if (!data) return null;
  const currency = data.funds[0]?.currency ?? 'TZS';
  const reconciled = Number(data.difference) === 0;
  return (
    <section aria-label="Unit-linked reconciliation" className="space-y-2 rounded-lg border border-border bg-surface p-4">
      <p className="text-sm font-medium">Reconciliation</p>
      <p className={reconciled ? 'text-xs text-status-success-fg' : 'text-xs text-status-danger-fg'}>
        Units at their latest prices {formatMoney({ amount: data.totalValue, currencyCode: currency })} · ledger 2150{' '}
        {formatMoney({ amount: data.ledgerBalance2150, currencyCode: currency })} ·{' '}
        {reconciled ? 'reconciled' : `difference ${formatMoney({ amount: data.difference, currencyCode: currency })}`}
      </p>
      {data.funds.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-xs" aria-label="Units in issue by fund">
            <thead className="text-left text-muted-foreground">
              <tr>
                <th className="p-1">Fund</th>
                <th className="p-1">Units in issue</th>
                <th className="p-1">Price</th>
                <th className="p-1">Value</th>
              </tr>
            </thead>
            <tbody>
              {data.funds.map((f) => (
                <tr key={f.fundCode}>
                  <td className="p-1 font-mono">{f.fundCode}</td>
                  <td className="p-1 font-mono">{f.unitsInIssue}</td>
                  <td className="p-1 font-mono">
                    {f.price ?? '—'}
                    {f.priceDate ? ` (${formatDate(f.priceDate)})` : ''}
                  </td>
                  <td className="p-1">{formatMoney({ amount: f.value, currencyCode: f.currency })}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
