import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { ChartOfAccountView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectDeletingAccount,
  selectRenamingAccount,
  useFinaccountingStore,
} from '@/store/finaccountingStore';
import {
  blankCreateAccountForm,
  createAccountFormSchema,
  toApiRequest as toCreateApiRequest,
  type CreateAccountFormValues,
} from './createAccountForm';
import {
  blankRenameAccountForm,
  renameAccountFormSchema,
  toApiRequest as toRenameApiRequest,
  type RenameAccountFormValues,
} from './renameAccountForm';
import { Input } from '@/components/ui/input';

/**
 * `GET /chart-of-accounts` -- a bare array, no pager. Rows are either
 * PLACEHOLDER (seeded by `ChartOfAccountSeeder`) or created through the
 * `POST` below; nothing distinguishes the two once created. Reachable only
 * by FINANCE_OFFICER/ADMIN -- gated one level up, by the Finance nav group
 * itself (`AppShell`'s `canSeeFinance`), the same convenience-gate idiom
 * used everywhere else on this console; the backend remains the authority.
 */
export function ChartOfAccountsPage() {
  const accounts = useFinaccountingStore((s) => s.chartOfAccounts);
  const loadChartOfAccounts = useFinaccountingStore((s) => s.loadChartOfAccounts);
  const [creatingOpen, setCreatingOpen] = useState(false);

  useEffect(() => {
    void loadChartOfAccounts();
  }, [loadChartOfAccounts]);

  function renderBody() {
    if (isInitialLoad(accounts)) return <LoadingBlock />;
    if (accounts.status === 'error' && accounts.error && accounts.data === null) {
      return <ErrorPanel error={accounts.error} onRetry={() => void loadChartOfAccounts()} />;
    }
    const rows = accounts.data ?? [];
    if (rows.length === 0 && !creatingOpen) {
      return (
        <EmptyState title="No accounts" description="Nothing is seeded in this tenant yet." />
      );
    }
    return (
      <div className="divide-y divide-border">
        {rows.map((account) => (
          <AccountRow key={account.accountCode} account={account} />
        ))}
      </div>
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
        <div className="rounded-lg border border-border bg-surface">{renderBody()}</div>
      </div>
    </>
  );
}

function AccountRow({ account }: { account: ChartOfAccountView }) {
  const [action, setAction] = useState<'rename' | 'delete' | null>(null);

  return (
    <div className="px-4 py-3">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <span className="font-mono text-xs text-muted-foreground">{account.accountCode}</span>
          <span className="ml-2 text-sm font-medium">{account.name}</span>
        </div>
        <div className="flex shrink-0 items-center gap-3 text-xs text-muted-foreground">
          <span>{account.accountType}</span>
          <span>{account.normalBalance}</span>
        </div>
      </div>

      {/* Hidden while a form is open, same idiom as InvoicesPanel's row actions. */}
      {action === null && (
        <div className="mt-1.5 flex items-center gap-1.5">
          <Button size="sm" variant="ghost" className="-ml-2" onClick={() => setAction('rename')}>
            Rename
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setAction('delete')}>
            Delete
          </Button>
        </div>
      )}

      {action === 'rename' && (
        <RenameAccountForm account={account} onDone={() => setAction(null)} />
      )}
      {action === 'delete' && (
        <DeleteAccountForm account={account} onDone={() => setAction(null)} />
      )}
    </div>
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
      <div className="flex items-end gap-2">
        <label className="block">
          <span className="mb-1 block text-[11px] font-medium text-muted-foreground">
            Account code
          </span>
          <Input
            inputSize="sm" className="w-24 font-mono"
            placeholder="1900"
            {...register('accountCode')}
          />
        </label>
        <label className="block flex-1">
          <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Name</span>
          <Input
            inputSize="sm"
            placeholder="Petty cash"
            {...register('name')}
          />
        </label>
      </div>
      {errors.accountCode?.message && (
        <p className="text-[11px] text-status-danger-fg">{errors.accountCode.message}</p>
      )}
      {errors.name?.message && (
        <p className="text-[11px] text-status-danger-fg">{errors.name.message}</p>
      )}

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

function RenameAccountForm({
  account,
  onDone,
}: {
  account: ChartOfAccountView;
  onDone: () => void;
}) {
  const renameAccount = useFinaccountingStore((s) => s.renameAccount);
  const resetRenameAccount = useFinaccountingStore((s) => s.resetRenameAccount);
  const renaming = useFinaccountingStore(selectRenamingAccount(account.accountCode));

  useEffect(() => {
    resetRenameAccount(account.accountCode);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account.accountCode]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RenameAccountFormValues>({
    resolver: zodResolver(renameAccountFormSchema),
    defaultValues: blankRenameAccountForm(account.name),
  });

  async function onSubmit(values: RenameAccountFormValues) {
    await renameAccount(account.accountCode, toRenameApiRequest(values));
    if (
      useFinaccountingStore.getState().renaming[account.accountCode]?.status === 'success'
    ) {
      onDone();
    }
  }

  return (
    <form
      className="mt-2 space-y-2 rounded-md border border-border p-2.5"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <label className="block">
        <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Name</span>
        <Input
          inputSize="sm"
          {...register('name')}
        />
        {errors.name?.message && (
          <p className="mt-1 text-[11px] text-status-danger-fg">{errors.name.message}</p>
        )}
      </label>

      {renaming.status === 'error' && renaming.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {renaming.error.detail ?? renaming.error.title}
        </p>
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" disabled={renaming.status === 'loading'}>
          {renaming.status === 'loading' ? 'Renaming…' : 'Rename'}
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
    // A 409 (account in use) leaves the row in place with the error shown below,
    // not dismissed -- only a real success closes this confirmation.
    if (
      useFinaccountingStore.getState().deleting[account.accountCode]?.status === 'success'
    ) {
      onDone();
    }
  }

  return (
    <div className="mt-2 space-y-2 rounded-md border border-border p-2.5">
      <p className="text-xs text-muted-foreground">
        Delete <span className="font-mono">{account.accountCode}</span> &ldquo;{account.name}
        &rdquo;? This cannot be undone.
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
