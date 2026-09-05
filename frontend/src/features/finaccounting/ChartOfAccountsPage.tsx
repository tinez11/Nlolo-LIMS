import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useSearchParams } from 'react-router-dom';
import type { AccountType, ChartOfAccountView } from '@/api/types';
import { FilterChip } from '@/components/FilterChip';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
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
 */
export function ChartOfAccountsPage() {
  const accounts = useFinaccountingStore((s) => s.chartOfAccounts);
  const loadChartOfAccounts = useFinaccountingStore((s) => s.loadChartOfAccounts);
  const [creatingOpen, setCreatingOpen] = useState(false);

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
        renderActions={(node) => <RowActions account={node} />}
      />
    ) : (
      <AccountTableView
        accounts={tableRows}
        sortKey={sortKey}
        sortDirection={sortDirection}
        onSort={onSort}
        renderActions={(account) => <RowActions account={account} />}
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Chart of accounts"
        description="accountType and normalBalance are always derived from the account code's own leading digit."
        actions={
          !creatingOpen && (
            <Button size="sm" variant="primary" onClick={() => setCreatingOpen(true)}>
              New account
            </Button>
          )
        }
      />

      <div className="px-6 pb-6 space-y-4">
        {creatingOpen && <CreateAccountForm onDone={() => setCreatingOpen(false)} />}

        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1" role="group" aria-label="View">
            <Button
              size="sm"
              variant={view === 'tree' ? 'primary' : 'ghost'}
              aria-pressed={view === 'tree'}
              onClick={() => selectView('tree')}
            >
              Tree
            </Button>
            <Button
              size="sm"
              variant={view === 'table' ? 'primary' : 'ghost'}
              aria-pressed={view === 'table'}
              onClick={() => selectView('table')}
            >
              Table
            </Button>
          </div>

          <Input
            inputSize="sm"
            className="w-56"
            aria-label="Search accounts"
            placeholder="Search code or name"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />

          <div className="flex flex-wrap items-center gap-1">
            {ACCOUNT_TYPES.map((type) => (
              <FilterChip
                key={type}
                label={type}
                active={filters.types.includes(type)}
                onClick={() => toggleType(type)}
              />
            ))}
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
          </div>
        </div>

        <div className="rounded-lg border border-border bg-surface">{renderBody()}</div>
      </div>
    </>
  );
}

/**
 * The per-row controls, shared by both views so a rename behaves identically in each.
 *
 * The forms render in a panel below the table/tree rather than inline, because a tree
 * row cannot host a form without breaking its own `role="treeitem"` semantics.
 */
function RowActions({ account }: { account: ChartOfAccountView }) {
  const [action, setAction] = useState<'edit' | 'delete' | null>(null);
  const setAccountStatus = useFinaccountingStore((s) => s.setAccountStatus);
  const settingStatus = useFinaccountingStore(selectSettingStatus(account.accountCode));
  const retiring = account.status === 'ACTIVE';

  return (
    <>
      {/* Hidden while a form is open, same idiom as InvoicesPanel's row actions -- and
          load-bearing here, because the rename form's submit button is also called
          "Rename": leaving both on screen gives one row two identically-named controls,
          which a screen reader cannot tell apart. */}
      {action === null && (
        <span className="flex items-center gap-1">
          <Button size="sm" variant="ghost" onClick={() => setAction('edit')}>
            Rename
          </Button>
          <Button
            size="sm"
            variant="ghost"
            disabled={settingStatus.status === 'loading'}
            onClick={() =>
              void setAccountStatus(account.accountCode, retiring ? 'INACTIVE' : 'ACTIVE')
            }
          >
            {retiring ? 'Retire' : 'Restore'}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setAction('delete')}>
            Delete
          </Button>
        </span>
      )}

      {action === 'edit' && (
        <UpdateAccountForm account={account} onDone={() => setAction(null)} />
      )}
      {action === 'delete' && (
        <DeleteAccountForm account={account} onDone={() => setAction(null)} />
      )}
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
    <form
      className="space-y-2 rounded-lg border border-border bg-surface p-3"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <div className="flex flex-wrap items-end gap-2">
        <FormField label="Account code" error={errors.accountCode?.message}>
          <Input
            inputSize="sm"
            className="w-24 font-mono"
            placeholder="1260"
            {...register('accountCode')}
          />
        </FormField>
        {/* Optional: blank creates a block root. The server enforces that the code sits
            inside the parent's block, which is not a rule this form can check. */}
        <FormField label="Parent account" error={errors.parentCode?.message}>
          <Input
            inputSize="sm"
            className="w-24 font-mono"
            placeholder="1200"
            {...register('parentCode')}
          />
        </FormField>
        <FormField label="Name" className="flex-1" error={errors.name?.message}>
          <Input inputSize="sm" placeholder="Sundry Receivables" {...register('name')} />
        </FormField>
      </div>

      <FormField label="Description" error={errors.description?.message}>
        <Input inputSize="sm" placeholder="Optional" {...register('description')} />
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
      className="mt-2 space-y-2 rounded-md border border-border p-2.5 text-left"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <FormField label="Name" error={errors.name?.message}>
        <Input inputSize="sm" {...register('name')} />
      </FormField>
      <FormField label="Description" error={errors.description?.message}>
        <Input inputSize="sm" {...register('description')} />
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
    <div className="mt-2 space-y-2 rounded-md border border-border p-2.5 text-left">
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
