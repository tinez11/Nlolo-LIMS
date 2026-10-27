import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { BranchSelect } from '@/components/BranchSelect';
import { FormField } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { UnsavedGuard } from '@/components/UnsavedGuard';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useIamStore } from '@/store/iamStore';
import { blankNewUserForm, newUserFormSchema, suggestedUsername, toApiRequest, type NewUserFormValues } from './newUserForm';

/**
 * Adding a staff member (2026-10-26): their login is made in the staff realm and the roles chosen here are theirs
 * from their first sign-in. Their first password follows the portal invite's rule -- an email link if they have an
 * address, otherwise a one-time password shown once on their page. Single permissions are added there afterwards.
 */
export function NewUserPage() {
  const navigate = useNavigate();
  const roles = useIamStore((s) => s.roles);
  const loadRoles = useIamStore((s) => s.loadRoles);
  const creating = useIamStore((s) => s.creatingUser);
  const createUser = useIamStore((s) => s.createUser);
  const resetCreateUser = useIamStore((s) => s.resetCreateUser);

  useEffect(() => {
    void loadRoles();
    resetCreateUser();
  }, [loadRoles, resetCreateUser]);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    getValues,
    formState: { errors, isDirty, isSubmitting, dirtyFields },
  } = useForm<NewUserFormValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(newUserFormSchema), defaultValues: blankNewUserForm() });

  // The login follows the name until somebody types one of their own.
  const suggest = () => {
    if (!dirtyFields.username) {
      setValue('username', suggestedUsername(getValues('firstName'), getValues('lastName')));
    }
  };

  async function onSubmit(values: NewUserFormValues) {
    await createUser(toApiRequest(values));
    const result = useIamStore.getState().creatingUser;
    if (result.status === 'success' && result.data) {
      navigate(`../${result.data.user.userId}`, { relative: 'path' });
    }
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Users', to: '/staff/users' }]}
        title="Add a user"
        description="With an email they are sent a link to set their password; without one, you are given a one-time password to hand over."
      />
      <FormLayout formId="new-user-form" outlineLabel="User sections">
        <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <UnsavedGuard when={isDirty && !isSubmitting} what="This user" />
          <div data-outline="Who they are" className="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FormField label="First name" error={errors.firstName?.message}>
                <Input {...register('firstName', { onChange: suggest })} />
              </FormField>
              <FormField label="Last name" error={errors.lastName?.message}>
                <Input {...register('lastName', { onChange: suggest })} />
              </FormField>
            </div>
            <FormField label="Login name" hint="What they type to sign in." error={errors.username?.message}>
              <Input className="font-mono" {...register('username')} />
            </FormField>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FormField label="Email (optional)" error={errors.email?.message}>
                <Input type="email" {...register('email')} />
              </FormField>
              <FormField label="Phone (optional)" error={errors.phone?.message}>
                <Input inputMode="tel" {...register('phone')} />
              </FormField>
            </div>
            <FormField label="Home branch (optional)" error={errors.homeBranch?.message}>
              <Controller
                control={control}
                name="homeBranch"
                render={({ field }) => <BranchSelect allowNone noneLabel="None" value={field.value} onChange={field.onChange} />}
              />
            </FormField>
          </div>

          <div data-outline="Roles" className="space-y-2">
            <p className="text-sm font-medium">Roles</p>
            {isInitialLoad(roles) && <LoadingBlock label="Loading roles" />}
            {roles.status === 'error' && roles.error && <InlineError error={roles.error} />}
            <Controller
              control={control}
              name="roleCodes"
              render={({ field }) => (
                <div className="grid grid-cols-1 gap-1 sm:grid-cols-2">
                  {(roles.data ?? []).filter((r) => r.active).map((r) => (
                    <CheckboxField
                      key={r.roleId}
                      label={r.name}
                      checked={field.value.includes(r.code)}
                      onChange={(e) => field.onChange(e.target.checked
                        ? [...field.value, r.code]
                        : field.value.filter((c) => c !== r.code))}
                    />
                  ))}
                </div>
              )}
            />
          </div>

          {creating.status === 'error' && creating.error && <InlineError error={creating.error} />}

          <div className="flex gap-2">
            <Button type="submit" variant="primary" pending={creating.status === 'loading'}>
              Add user
            </Button>
            <Button asChild variant="ghost">
              <Link to="/staff/users">Cancel</Link>
            </Button>
          </div>
        </form>
      </FormLayout>
    </>
  );
}
