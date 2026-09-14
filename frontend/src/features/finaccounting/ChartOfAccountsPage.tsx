import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, RotateCcw, Search } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useSearchParams } from 'react-router-dom';
import type { AccountType, ChartOfAccountView } from '@/api/types';
import { FilterChip } from '@/components/FilterChip';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { CountLine } from '@/components/StatCards';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectDeletingAccount,
  selectSettingStatus,
  selectUpdatingAccount,
  useFinaccountingStore,
} from '@/store/finaccountingStore';
import { AccountTableView } from './AccountTableView';
import { AccountTreeView } from './AccountTreeView';
import {
  accountTypePlural,
  buildAccountTree,
  filterAccounts,
  sortAccounts,
  type AccountFilters,
  type SortKey,
} from './accountTree';
import {
  blankCreateAccountForm,
  createAccountFormSchema,
  toApiRequest as toCreateApiRequest,
  type CreateAccountFormValues,
} from './createAccountForm';
import {
  blankUpdateAccountForm,
  toApiRequest as toUpdateApiRequest,
  updateAccountFormSchema,
  type UpdateAccountFormValues,
} from './updateAccountForm';

const ACCOUNT_TYPES: AccountType[] = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];

/** Which row, if any, has its rename or delete form open. */
type RowAction = { code: string; action: 'edit' | 'delete' };

/**
 * `GET /chart-of-accounts` -- a bare array, no pager, because a chart is bounded
 * reference data. The hierarchy is assembled client-side from that flat array
 * (`accountTree.ts`), which is what lets the Tree/Table toggle switch instantly and
 * keeps search and sort entirely off the network.
 *
 * Two views, because they answer different questions. The TREE is how an accountant
 * reads the structure -- what rolls up into what. The TABLE is how they work it --
 * search, filter, sort. Reachable only by FINANCE_OFFICER/ADMIN, gated one level up by
 * the Finance nav group itself (`AppShell`'s `canSeeFinance`); the backend remains the
 * authority.
 *
 * ## Why the open form is a page-level fact
 *
 * `RowActions` used to hold its own `useState`, so every row could have a form open at
 * once and each one rendered wherever the row happened to call it -- in the tree, at
 * the right edge of a 30px row; in the table, inside a ~190px actions cell. Which row
 * is being edited is one fact about the screen, so it lives here, and each view is then
 * free to give the form the width it needs (the table spans the row, the tree indents
 * under it). It also means opening a second form closes the first, which is what a
 * person doing this expects.
 */
export function ChartOfAccountsPage() {
  const accounts = useFinaccountingStore((s) => s.chartOfAccounts);
  const loadChartOfAccounts = useFinaccountingStore((s) => s.loadChartOfAccounts);
  const [creatingOpen, setCreatingOpen] = useState(false);
  const [rowAction, setRowAction] = useState<RowAction | null>(null);

  const [searchParams, setSearchParams] = useSearchParams();
  const view = searchParams.get('view') === 'table' ? 'table' : 'tree';

  // null until the first disclosure is touched -- see `expanded` below.
  const [userExpanded, setUserExpanded] = useState<Set<string> | null>(null);
  const [searchInput, setSearchInput] = useState('');
  const [filters, setFilters] = useState<AccountFilters>({
    search: '',
    types: [],
    statuses: [],
    postingOnly: false,
  });
  const [sortKey, setSortKey] = useState<SortKey>('accountCode');
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('asc');

  useEffect(() => {
    void loadChartOfAccounts();
  }, [loadChartOfAccounts]);

  // Balances come from their own endpoint, so the structural read stays cheap. Inception-to-date
  // for now: the chart has no period control, and showing a period-scoped figure without saying
  // which period would be a number nobody could reconcile.
  const trialBalance = useFinaccountingStore((s) => s.trialBalance);
  const loadTrialBalance = useFinaccountingStore((s) => s.loadTrialBalance);
  useEffect(() => {
    void loadTrialBalance();
  }, [loadTrialBalance]);

  const balancesByCode = useMemo(() => {
    const balances = trialBalance.data?.accounts ?? [];
    return new Map(balances.filter((a) => a.accountCode).map((a) => [a.accountCode, a]));
  }, [trialBalance.data]);

  /**
   * One account's rolled balance, linking through to the postings behind it.
   *
   * <p>The link is the point: an account and its postings were two screens in the Finance nav
   * with no way to reach each other, because `GET /gl-postings` could not filter by account.
   * A balance you cannot open is a number you have to take on trust.
   *
   * <p>Nothing is rendered while the balances are still loading, and a dash for an account with
   * no postings -- never a zero, which on a ledger reads as "counted, and it came to nothing"
   * rather than "not counted".
   */
  function renderBalance(node: { accountCode: string }) {
    const account = balancesByCode.get(node.accountCode);
    if (!account) return null;
    const hasMovement = account.debit?.amount !== '0' || account.credit?.amount !== '0';
    if (!hasMovement) return <span className="text-subtle-foreground">—</span>;

    const figure = account.balance ? formatMoney(account.balance) : '—';

    /*
     * ONLY A POSTABLE ACCOUNT'S BALANCE IS A LINK, and this was found by clicking one.
     *
     * A summary account's figure is ROLLED UP from its descendants; it has no postings of its
     * own, because the ledger refuses a leg naming it. `GET /gl-postings?accountCode=` matches
     * direct legs, so linking a parent produced a real balance opening onto "0 matching journal
     * entries" -- a number that looks wrong about itself.
     *
     * A parent's figure is therefore plain text. The postings are one level down, where the
     * link is. Making the filter roll up too -- "everything under Assets" -- is a genuinely
     * useful thing the backend does not do yet, and is not something to fake here by linking to
     * a query that answers a different question.
     */
    if (!account.postingAllowed) {
      return <span title="A summary account: its postings are on the accounts beneath it">{figure}</span>;
    }

    return (
      <Link
        to={`../gl-postings?accountCode=${encodeURIComponent(node.accountCode)}`}
        relative="path"
        className="underline-offset-2 hover:underline"
        title={`Postings behind ${node.accountCode}`}
      >
        {figure}
      </Link>
    );
  }

  // Debounced, matching this console's other list searches: a keystroke should not
  // re-filter 36 rows on every character.
  useEffect(() => {
    const timer = setTimeout(() => setFilters((f) => ({ ...f, search: searchInput })), 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  const rows = useMemo(() => accounts.data ?? [], [accounts.data]);

  const filtered = useMemo(() => filterAccounts(rows, filters), [rows, filters]);
  const tableRows = useMemo(
    () => sortAccounts(filtered, sortKey, sortDirection),
    [filtered, sortKey, sortDirection],
  );

  // The TREE is built from the unfiltered chart on purpose: hiding a parent would hide
  // every matching child with it, and a hierarchy with holes in it misleads about what
  // rolls up into what. A search in tree view instead OPENS the branches that contain a
  // match -- see `expanded` below.
  const tree = useMemo(() => buildAccountTree(rows), [rows]);

  /**
   * What is actually open, DERIVED rather than stored.
   *
   * `userExpanded` is null until someone touches a disclosure, so the default -- the
   * five block roots open, because a fully collapsed wall of five words is a useless
   * first screen -- needs no effect to install it. Searching then unions in every
   * ancestor of a match, so a hit three levels down is visible without the user
   * hunting for it, and reverts the moment the search is cleared.
   *
   * Deriving all of this is what keeps it out of a `useEffect`: setting state
   * synchronously in an effect triggers cascading renders and this console's lint
   * rejects it outright.
   */
  const defaultExpanded = useMemo(
    () => new Set(rows.filter((a) => !a.parentCode).map((a) => a.accountCode)),
    [rows],
  );

  const expanded = useMemo(() => {
    const base = userExpanded ?? defaultExpanded;
    if (filters.search.trim() === '') return base;
    const byCode = new Map(rows.map((a) => [a.accountCode, a]));
    const next = new Set(base);
    for (const match of filtered) {
      let cursor = match.parentCode;
      while (cursor && !next.has(cursor)) {
        next.add(cursor);
        cursor = byCode.get(cursor)?.parentCode;
      }
    }
    return next;
  }, [userExpanded, defaultExpanded, filters.search, filtered, rows]);

  function toggle(accountCode: string) {
    setUserExpanded((current) => {
      const next = new Set(current ?? defaultExpanded);
      if (next.has(accountCode)) next.delete(accountCode);
      else next.add(accountCode);
      return next;
    });
  }

  function selectView(next: 'tree' | 'table') {
    setSearchParams(
      (params) => {
        params.set('view', next);
        return params;
      },
      { replace: true },
    );
  }

  function onSort(key: SortKey) {
    if (key === sortKey) {
      setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDirection('asc');
    }
  }

  function toggleType(type: AccountType) {
    setFilters((f) => ({
      ...f,
      types: f.types.includes(type) ? f.types.filter((t) => t !== type) : [...f.types, type],
    }));
  }

  // Reads the uncommitted `searchInput` rather than `filters.search`, so the reset
  // appears on the first keystroke instead of 300ms into it.
  const filtersActive =
    searchInput.trim() !== '' ||
    filters.types.length > 0 ||
    filters.statuses.length > 0 ||
    filters.postingOnly;

  function clearFilters() {
    setSearchInput('');
    setFilters({ search: '', types: [], statuses: [], postingOnly: false });
  }

  const count = {
    label: 'accounts',
    // The tree renders the whole chart whatever the filters say -- it opens branches
    // rather than pruning them -- so only the table's count moves with a filter.
    // Reporting a filtered figure beside an unfiltered tree would be a lie about
    // what is on screen.
    value: accounts.data === null ? null : view === 'table' ? tableRows.length : rows.length,
    pending: isInitialLoad(accounts),
    hint: view === 'table' && filtersActive ? 'matching this filter' : 'in this chart',
  };

  /** The buttons on a row, or the reserved slot they leave behind while its form is open. */
  function renderActions(account: ChartOfAccountView) {
    return (
      <AccountRowActions
        account={account}
        open={rowAction?.code === account.accountCode}
        onOpen={(action) => setRowAction({ code: account.accountCode, action })}
      />
    );
  }

  function renderForm(account: ChartOfAccountView) {
    if (rowAction?.code !== account.accountCode) return null;
    const close = () => setRowAction(null);
    return rowAction.action === 'edit' ? (
      <UpdateAccountForm account={account} onDone={close} />
    ) : (
      <DeleteAccountForm account={account} onDone={close} />
    );
  }

  function renderBody() {
    if (isInitialLoad(accounts)) return <LoadingBlock />;
    if (accounts.status === 'error' && accounts.error && accounts.data === null) {
      return <ErrorPanel error={accounts.error} onRetry={() => void loadChartOfAccounts()} />;
    }
    if (rows.length === 0) {
      return <EmptyState title="No accounts" description="Nothing is seeded in this tenant yet." />;
    }
    // Says which of the two it is: an empty result after filtering is not the same fact
    // as a tenant with no chart, and telling a user the latter would be wrong.
    if (filtered.length === 0) {
      return (
        <EmptyState
          title="No matching accounts"
          description="No account matches the current search and filters."
        />
      );
    }
    return view === 'tree' ? (
      <AccountTreeView
        nodes={tree}
        expanded={expanded}
        onToggle={toggle}
        renderActions={renderActions}
        renderForm={renderForm}
        renderBalance={renderBalance}
      />
    ) : (
      <AccountTableView
        accounts={tableRows}
        sortKey={sortKey}
        sortDirection={sortDirection}
        onSort={onSort}
        renderActions={renderActions}
        renderForm={renderForm}
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Chart of accounts"
        // Kept under about 150 characters on purpose. `PageHeader` is one wrapping
        // flex row, so a description that consumes the full column pushes the
        // primary action onto its own line, left-aligned, unlike every other screen
        // in the console -- which is exactly what the first draft of this sentence
        // did at 1440px. The code rule it used to carry lives in the New account
        // panel now, where it is actionable.
        description="The ledger structure every posting books against. Type and normal balance are derived from an account's code, never entered."
        count={<CountLine {...count} />}
        actions={
          !creatingOpen && (
            <Button size="sm" variant="primary" onClick={() => setCreatingOpen(true)}>
              <Plus />
              New account
            </Button>
          )
        }
      />

      <div className="space-y-4 px-6 pb-6">
        {/*
          THE ONE ASSERTION A LEDGER MUST ALWAYS BE ABLE TO MAKE.

          Until the balances endpoint existed nothing on this platform could sum a posting, so
          "do the books balance" was unanswerable from the console. `balanced` is the SERVER's
          own verdict, not a comparison of two decimal strings done here -- money arithmetic
          stays off the client, and a totals line that could be silently wrong is precisely why.

          Inception-to-date, and it says so: a balance with no period beside it is a number
          nobody can reconcile against anything.
        */}
        {trialBalance.data && (
          <div className="flex flex-wrap items-center gap-x-6 gap-y-1 rounded-lg border border-border bg-surface px-4 py-2.5 text-xs">
            <span className="font-medium">Trial balance</span>
            <span className="text-muted-foreground">
              inception to date
            </span>
            <span className="ml-auto flex flex-wrap items-center gap-x-6 gap-y-1 tabular-nums">
              <span>
                <span className="text-muted-foreground">Debits </span>
                {trialBalance.data.totalDebit ? formatMoney(trialBalance.data.totalDebit) : '—'}
              </span>
              <span>
                <span className="text-muted-foreground">Credits </span>
                {trialBalance.data.totalCredit ? formatMoney(trialBalance.data.totalCredit) : '—'}
              </span>
              <span
                className={
                  trialBalance.data.balanced ? 'text-status-success-fg' : 'text-status-danger-fg'
                }
              >
                {trialBalance.data.balanced ? 'In balance' : 'OUT OF BALANCE'}
              </span>
            </span>
          </div>
        )}

        {creatingOpen && <CreateAccountForm onDone={() => setCreatingOpen(false)} />}

        <div className="rounded-lg border border-border bg-surface">
          {/* The toolbar belongs to the register it drives, so it sits inside the
              panel as a ruled strip -- the same shape as every other list screen's
              filter bar. It used to float above the panel as a bare row, which is
              why the view toggle, the search box and eight chips read as three
              unrelated things sharing a gap. */}
          <div className="border-b border-border px-3 py-2.5">
            <div className="flex flex-wrap items-center gap-2">
              <ViewToggle view={view} onSelect={selectView} />

              <div className="relative ml-auto">
                <Search
                  className="pointer-events-none absolute top-1/2 left-2 h-3.5 w-3.5 -translate-y-1/2 text-subtle-foreground"
                  aria-hidden="true"
                />
                <Input
                  inputSize="sm"
                  className="w-56 pl-7"
                  aria-label="Search accounts"
                  placeholder="Search code or name"
                  value={searchInput}
                  onChange={(e) => setSearchInput(e.target.value)}
                />
              </div>
            </div>

            <div className="mt-2 flex flex-wrap items-center gap-1">
              {ACCOUNT_TYPES.map((type) => (
                <FilterChip
                  key={type}
                  label={accountTypePlural(type)}
                  active={filters.types.includes(type)}
                  onClick={() => toggleType(type)}
                />
              ))}

              {/* The five blocks narrow to a part of the chart; these two ask a
                  different question of all of it, and the rule says so. */}
              <span className="mx-1.5 h-4 w-px bg-border" aria-hidden="true" />

              <FilterChip
                label="Postable only"
                active={filters.postingOnly}
                onClick={() => setFilters((f) => ({ ...f, postingOnly: !f.postingOnly }))}
              />
              <FilterChip
                label="Retired"
                active={filters.statuses.includes('INACTIVE')}
                onClick={() =>
                  setFilters((f) => ({
                    ...f,
                    statuses: f.statuses.includes('INACTIVE') ? [] : ['INACTIVE'],
                  }))
                }
              />

              {/* Only once there is something to clear: a permanently-lit reset is
                  one more control to read past on a screen that already has eight. */}
              {filtersActive && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="ml-auto text-muted-foreground"
                  onClick={clearFilters}
                >
                  <RotateCcw />
                  Clear filters
                </Button>
              )}
            </div>
          </div>

          {renderBody()}
        </div>
      </div>
    </>
  );
}

/**
 * Tree or Table, as a segmented control rather than two buttons.
 *
 * It was a `primary` and a `ghost` button, which put solid Ledger Ink on whichever
 * view was already showing -- so the screen carried two black buttons and the one
 * that meant "do something here" was the quieter of the pair. DESIGN.md reserves
 * Ledger Ink for the single action a screen exists to perform, which on this screen
 * is New account. Selection is achromatic instead, in the same Selected-ground and
 * Rule-Strong ring the filter chips already use for "this one is on".
 */
function ViewToggle({
  view,
  onSelect,
}: {
  view: 'tree' | 'table';
  onSelect: (next: 'tree' | 'table') => void;
}) {
  return (
    <div
      className="flex items-center gap-0.5 rounded-md border border-border bg-surface-muted p-0.5"
      role="group"
      aria-label="View"
    >
      {(['tree', 'table'] as const).map((value) => (
        <button
          key={value}
          type="button"
          aria-pressed={view === value}
          onClick={() => onSelect(value)}
          className={cn(
            'rounded-sm px-2.5 py-1 text-xs transition-colors',
            view === value
              ? 'bg-surface font-medium text-foreground ring-1 ring-border-strong ring-inset'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {value === 'tree' ? 'Tree' : 'Table'}
        </button>
      ))}
    </div>
  );
}

/**
 * The per-row controls, shared by both views so a rename behaves identically in each.
 *
 * **Quiet until the row is.** Thirty-six rows times three ghost buttons was a hundred
 * and eight controls competing with the structure they act on, and a chart of accounts
 * is read far more often than it is edited. They fade in on hover and on
 * `focus-within`, so a keyboard user tabbing through gets them without a mouse, and
 * `opacity` rather than conditional rendering keeps the row's height and its columns
 * fixed whether they show or not. They stay lit while a retire or restore is in flight,
 * because a control you are waiting on must not disappear under the pointer.
 *
 * While this row's form is open the buttons are gone and the slot stays: the form's
 * submit is also called "Rename", so leaving both on screen gives one row two
 * identically-named controls that a screen reader cannot tell apart -- and dropping
 * the slot entirely would shunt every column left of it.
 */
function AccountRowActions({
  account,
  open,
  onOpen,
}: {
  account: ChartOfAccountView;
  open: boolean;
  onOpen: (action: 'edit' | 'delete') => void;
}) {
  const setAccountStatus = useFinaccountingStore((s) => s.setAccountStatus);
  const settingStatus = useFinaccountingStore(selectSettingStatus(account.accountCode));
  const retiring = account.status === 'ACTIVE';
  const busy = settingStatus.status === 'loading';

  if (open) return <span className="w-56 shrink-0" aria-hidden="true" />;

  return (
    <>
      <span
        className={cn(
          'flex w-56 shrink-0 items-center justify-end gap-1 transition-opacity',
          busy ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
        )}
      >
        <Button size="sm" variant="ghost" onClick={() => onOpen('edit')}>
          Rename
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          onClick={() =>
            void setAccountStatus(account.accountCode, retiring ? 'INACTIVE' : 'ACTIVE')
          }
        >
          {retiring ? 'Retire' : 'Restore'}
        </Button>
        <Button size="sm" variant="ghost" onClick={() => onOpen('delete')}>
          Delete
        </Button>
      </span>

      {settingStatus.status === 'error' && settingStatus.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {settingStatus.error.detail ?? settingStatus.error.title}
        </p>
      )}
    </>
  );
}

function CreateAccountForm({ onDone }: { onDone: () => void }) {
  const createAccount = useFinaccountingStore((s) => s.createAccount);
  const resetCreateAccount = useFinaccountingStore((s) => s.resetCreateAccount);
  const creating = useFinaccountingStore((s) => s.creating);

  useEffect(() => {
    resetCreateAccount();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateAccountFormValues>({
    resolver: zodResolver(createAccountFormSchema),
    defaultValues: blankCreateAccountForm(),
  });

  async function onSubmit(values: CreateAccountFormValues) {
    await createAccount(toCreateApiRequest(values));
    if (useFinaccountingStore.getState().creating.status === 'success') onDone();
  }

  return (
    // The two rules this form gets rejected by are stated here rather than in the page
    // description, where they were written in the wire's own words ("accountType and
    // normalBalance are always derived from...") for an audience that never sees the
    // wire. They are the whole reason a code is refused, so they belong beside the
    // field that carries it -- and at a readable measure rather than stretched across
    // the full 1600px panel, which is what `Panel`'s own subtitle would have done.
    <Panel title="New account" subtitle="Type and normal balance come from the code itself.">
      {/* Bounded, because a form is read left to right: an unbounded name field was
          1230px and the description box 1570px, which is a worse target than a
          narrower one and a much worse read. */}
      <form
        className="max-w-3xl space-y-3 p-4"
        onSubmit={(e) => void handleSubmit(onSubmit)(e)}
      >
        <p className="max-w-prose text-xs text-muted-foreground">
          Four digits, and the first one sets the type: 1 asset, 2 liability, 3 equity, 4 income,
          5 expense. The parent must already exist, and the code has to sit inside its block —
          1210 can hang off 1200, 2110 cannot.
        </p>

        <div className="flex flex-wrap items-start gap-3">
          <FormField label="Account code" error={errors.accountCode?.message}>
            <Input
              inputSize="sm"
              className="w-28 font-mono"
              placeholder="1260"
              {...register('accountCode')}
            />
          </FormField>
          {/* Optional: blank creates a block root. The server enforces that the code sits
              inside the parent's block, which is not a rule this form can check. */}
          <FormField label="Parent account" error={errors.parentCode?.message}>
            <Input
              inputSize="sm"
              className="w-28 font-mono"
              placeholder="1200"
              {...register('parentCode')}
            />
          </FormField>
          <FormField label="Name" className="min-w-56 max-w-md flex-1" error={errors.name?.message}>
            <Input inputSize="sm" placeholder="Sundry Receivables" {...register('name')} />
          </FormField>
        </div>

        {/* A textarea, because the field accepts 2000 characters and now renders in
            the table view: it was a 32px single-line box for the only prose on the
            screen that says what an account is for. */}
        <FormField label="Description" error={errors.description?.message}>
          <Textarea
            inputSize="sm"
            className="min-h-16"
            placeholder="What this account is for. Optional."
            {...register('description')}
          />
        </FormField>

        {creating.status === 'error' && creating.error && (
          <p role="alert" className="text-[11px] text-status-danger-fg">
            {creating.error.detail ?? creating.error.title}
          </p>
        )}

        <div className="flex items-center gap-1.5">
          <Button type="submit" size="sm" variant="primary" disabled={creating.status === 'loading'}>
            {creating.status === 'loading' ? 'Creating…' : 'Create account'}
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onDone}>
            Cancel
          </Button>
        </div>
      </form>
    </Panel>
  );
}

function UpdateAccountForm({
  account,
  onDone,
}: {
  account: ChartOfAccountView;
  onDone: () => void;
}) {
  const updateAccount = useFinaccountingStore((s) => s.updateAccount);
  const resetUpdateAccount = useFinaccountingStore((s) => s.resetUpdateAccount);
  const updating = useFinaccountingStore(selectUpdatingAccount(account.accountCode));

  useEffect(() => {
    resetUpdateAccount(account.accountCode);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account.accountCode]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<UpdateAccountFormValues>({
    resolver: zodResolver(updateAccountFormSchema),
    defaultValues: blankUpdateAccountForm(account.name ?? '', account.description),
  });

  async function onSubmit(values: UpdateAccountFormValues) {
    await updateAccount(account.accountCode, toUpdateApiRequest(values));
    if (useFinaccountingStore.getState().updating[account.accountCode]?.status === 'success') {
      onDone();
    }
  }

  return (
    <form
      className="max-w-2xl space-y-3 rounded-md border border-border bg-surface p-3 text-left"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <FormField label="Name" error={errors.name?.message}>
        <Input inputSize="sm" {...register('name')} />
      </FormField>
      <FormField label="Description" error={errors.description?.message}>
        <Textarea inputSize="sm" className="min-h-16" {...register('description')} />
      </FormField>

      {updating.status === 'error' && updating.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {updating.error.detail ?? updating.error.title}
        </p>
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" disabled={updating.status === 'loading'}>
          {updating.status === 'loading' ? 'Renaming…' : 'Rename'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function DeleteAccountForm({
  account,
  onDone,
}: {
  account: ChartOfAccountView;
  onDone: () => void;
}) {
  const deleteAccount = useFinaccountingStore((s) => s.deleteAccount);
  const resetDeleteAccount = useFinaccountingStore((s) => s.resetDeleteAccount);
  const deleting = useFinaccountingStore(selectDeletingAccount(account.accountCode));

  useEffect(() => {
    resetDeleteAccount(account.accountCode);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account.accountCode]);

  async function onConfirm() {
    await deleteAccount(account.accountCode);
    // A 409 (in use, or has children) leaves the row in place with the error shown
    // below, not dismissed -- only a real success closes this confirmation.
    if (useFinaccountingStore.getState().deleting[account.accountCode]?.status === 'success') {
      onDone();
    }
  }

  return (
    <div className="max-w-2xl space-y-2 rounded-md border border-border bg-surface p-3 text-left">
      <p className="text-xs text-muted-foreground">
        Delete <span className="font-mono">{account.accountCode}</span> &ldquo;{account.name}
        &rdquo;? This cannot be undone. An account that has ever been posted to cannot be deleted —
        retire it instead.
      </p>

      {deleting.status === 'error' && deleting.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {deleting.error.detail ?? deleting.error.title}
        </p>
      )}

      <div className="flex items-center gap-1.5">
        <Button
          type="button"
          size="sm"
          variant="danger"
          disabled={deleting.status === 'loading'}
          onClick={() => void onConfirm()}
        >
          {deleting.status === 'loading' ? 'Deleting…' : 'Delete account'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </div>
  );
}
