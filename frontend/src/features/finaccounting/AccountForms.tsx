import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import type { ChartOfAccountView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import {
  selectDeletingAccount,
  selectUpdatingAccount,
  useFinaccountingStore,
} from '@/store/finaccountingStore';
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
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/**
 * The three account forms the chart of accounts opens: create, rename, delete.
 *
 * Split out of ChartOfAccountsPage at 802 lines, where they were the last 219. Nothing about
 * them changed in the move -- same components, same comments, same behaviour -- and the render
 * tests written the task before exist to prove exactly that.
 *
 * They live together rather than one file each because they are one job seen three ways: all
 * three are opened from a row of the same register, all three write to the same store slice,
 * and a change to what an account IS touches all three at once.
 */

export function CreateAccountForm({ onDone }: { onDone: () => void }) {
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
    ...VALIDATE_ON_TOUCH,
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

        {creating.status === 'error' && creating.error && <InlineError error={creating.error} />}

        <div className="flex items-center gap-1.5">
          <Button type="submit" size="sm" variant="primary" pending={creating.status === 'loading'}>
            Create account
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onDone}>
            Cancel
          </Button>
        </div>
      </form>
    </Panel>
  );
}

export function UpdateAccountForm({
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
    ...VALIDATE_ON_TOUCH,
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

      {updating.status === 'error' && updating.error && <InlineError error={updating.error} />}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" pending={updating.status === 'loading'}>
          Rename
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

export function DeleteAccountForm({
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

      {deleting.status === 'error' && deleting.error && <InlineError error={deleting.error} />}

      <div className="flex items-center gap-1.5">
        <Button
          type="button"
          size="sm"
          variant="danger"
          pending={deleting.status === 'loading'}
          onClick={() => void onConfirm()}
        >
          Delete account
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </div>
  );
}
