import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { FormField } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { UnsavedGuard } from '@/components/UnsavedGuard';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { useIamStore } from '@/store/iamStore';
import { blankNewRoleForm, newRoleFormSchema, toApiRequest, type NewRoleFormValues } from './newRoleForm';
import { PermissionPicker } from './PermissionPicker';

/**
 * A role of the administrator's own (2026-10-26), for a job the built-in roles do not fit. It is held by nobody until
 * given to someone on their page.
 */
export function NewRolePage() {
  const navigate = useNavigate();
  const catalogue = useIamStore((s) => s.catalogue);
  const loadCatalogue = useIamStore((s) => s.loadCatalogue);
  const creating = useIamStore((s) => s.creatingRole);
  const createRole = useIamStore((s) => s.createRole);
  const resetCreateRole = useIamStore((s) => s.resetCreateRole);

  useEffect(() => {
    void loadCatalogue();
    resetCreateRole();
  }, [loadCatalogue, resetCreateRole]);

  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<NewRoleFormValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(newRoleFormSchema), defaultValues: blankNewRoleForm() });

  async function onSubmit(values: NewRoleFormValues) {
    await createRole(toApiRequest(values));
    const result = useIamStore.getState().creatingRole;
    if (result.status === 'success' && result.data) {
      void useIamStore.getState().loadRoles();
      navigate(`../${result.data.roleId}`, { relative: 'path' });
    }
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Roles', to: '/staff/roles' }]}
        title="New role"
        description="A set of permissions for a job the built-in roles do not fit. Give it to people on their own pages."
      />
      <FormLayout formId="new-role-form" outlineLabel="Role sections">
        <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <UnsavedGuard when={isDirty && !isSubmitting} what="This role" />
          <div data-outline="The role" className="space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FormField label="Name" error={errors.name?.message}>
                <Input placeholder="Branch manager" {...register('name')} />
              </FormField>
              <FormField label="Code" hint="Fixed once made." error={errors.code?.message}>
                <Input className="font-mono uppercase" placeholder="BRANCH_MANAGER" {...register('code')} />
              </FormField>
            </div>
            <FormField label="What it is for (optional)" error={errors.description?.message}>
              <Textarea className="min-h-16 py-2" {...register('description')} />
            </FormField>
          </div>
          <div data-outline="Permissions" className="space-y-2">
            <p className="text-sm font-medium">Permissions</p>
            <Controller
              control={control}
              name="permissions"
              render={({ field }) => (
                <PermissionPicker catalogue={catalogue.data ?? []} selected={new Set(field.value)}
                  onChange={(next) => field.onChange([...next])} />
              )}
            />
          </div>
          {creating.status === 'error' && creating.error && <InlineError error={creating.error} />}
          <div className="flex gap-2">
            <Button type="submit" variant="primary" pending={creating.status === 'loading'}>Create role</Button>
            <Button asChild variant="ghost"><Link to="/staff/roles">Cancel</Link></Button>
          </div>
        </form>
      </FormLayout>
    </>
  );
}
