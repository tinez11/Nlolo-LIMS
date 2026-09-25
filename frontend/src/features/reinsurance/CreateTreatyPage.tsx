import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft } from 'lucide-react';
import { useEffect, useState } from 'react';
import { type FieldErrors, useForm, Controller } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { TREATY_TYPES } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DatePicker } from '@/components/DatePicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { useReinsuranceStore } from '@/store/reinsuranceStore';
import {
  blankNonQuotaShareTreatyForm,
  blankQuotaShareTreatyForm,
  createTreatyFormSchema,
  toApiRequest,
  type CreateTreatyFormValues,
} from './createTreatyForm';
import { Input, Select } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';

/**
 * `POST /treaties` -- staff FINANCE_OFFICER/ADMIN only. `treatyType` switches
 * the whole form shape (only QUOTA_SHARE carries `cessionPercent`), so
 * `reset()`, not `setValue()`, changes branches -- the same idiom
 * RegisterClaimPage's claim-type switcher and ClaimSettlementPanel's
 * approve/reject toggle already established for a discriminated union used
 * directly as the form's top-level values.
 */
export function CreateTreatyPage() {
  const navigate = useNavigate();
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  const createTreaty = useReinsuranceStore((s) => s.createTreaty);
  const resetCreateTreaty = useReinsuranceStore((s) => s.resetCreateTreaty);
  const creating = useReinsuranceStore((s) => s.creating);

  useEffect(() => {
    resetCreateTreaty();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    watch,
    reset,
    control,
    formState: { errors },
  } = useForm<CreateTreatyFormValues>({
    resolver: zodResolver(createTreatyFormSchema),
    defaultValues: blankQuotaShareTreatyForm(),
  });

  // eslint-disable-next-line react-hooks/incompatible-library -- see RegisterClaimPage
  const treatyType = watch('treatyType');

  async function onSubmit(values: CreateTreatyFormValues) {
    await createTreaty(toApiRequest(values), attempt);
    const result = useReinsuranceStore.getState().creating;
    if (result.status === 'success' && result.data?.treatyId) {
      navigate(`../${result.data.treatyId}`, { relative: 'path' });
    }
  }

  return (
    <>
      <div className="px-6 pt-6">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link to=".." relative="path">
            <ArrowLeft />
            All treaties
          </Link>
        </Button>
      </div>

      <PageHeader title="New treaty" description="Staff/finance only." />

      <form className="max-w-xl space-y-4 px-6 pb-8" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
        <FormField label="Reinsurer name" error={errors.reinsurerName?.message}>
          <Input
            placeholder="Africa Re"
            {...register('reinsurerName')}
          />
        </FormField>

        <FormField label="Treaty type">
          <Select
            value={treatyType}
            onChange={(e) => {
              const next = e.target.value as CreateTreatyFormValues['treatyType'];
              reset(next === 'QUOTA_SHARE' ? blankQuotaShareTreatyForm() : blankNonQuotaShareTreatyForm(next));
            }}
          >
            {TREATY_TYPES.map((type) => (
              <option key={type} value={type}>
                {type.replace(/_/g, ' ')}
              </option>
            ))}
          </Select>
        </FormField>

        <div className="grid grid-cols-[1fr_auto] gap-2">
          <FormField label="Retention limit" error={errors.retentionLimitAmount?.message}>
            <Input
              placeholder="5000000.00"
              {...register('retentionLimitAmount')}
            />
          </FormField>
          <FormField label="Currency" error={errors.retentionLimitCurrency?.message}>
            <Input
              className="w-20 uppercase"
              {...register('retentionLimitCurrency')}
            />
          </FormField>
        </div>

        {treatyType === 'QUOTA_SHARE' && (
          <FormField label="Cession percent" error={fieldError(errors, 'cessionPercent')}>
            <div className="flex items-center gap-1">
              <Input
                placeholder="25.00"
                {...register('cessionPercent')}
              />
              <span className="text-xs text-muted-foreground">%</span>
            </div>
          </FormField>
        )}

        {treatyType !== 'QUOTA_SHARE' && (
          <p className="text-[11px] text-muted-foreground">
            {treatyType === 'SURPLUS'
              ? 'SURPLUS cedes by retention limit, not a percent.'
              : 'XOL cedes nothing at issuance -- it participates only in claim recovery.'}
          </p>
        )}

        <div className="grid grid-cols-2 gap-2">
          <FormField label="Effective from" error={errors.effectiveFrom?.message}>
            <Controller
              control={control}
              name="effectiveFrom"
              render={({ field }) => (
                <DatePicker
                  value={field.value || null}
                  onChange={(iso) => field.onChange(iso ?? '')}
                />
              )}
            />
          </FormField>
          <FormField label="Effective to (optional)" error={errors.effectiveTo?.message}>
            <Controller
              control={control}
              name="effectiveTo"
              render={({ field }) => (
                <DatePicker
                  value={field.value || null}
                  onChange={(iso) => field.onChange(iso ?? '')}
                />
              )}
            />
          </FormField>
        </div>

        {creating.status === 'error' && creating.error && (
          <InlineError error={creating.error} />
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" disabled={creating.status === 'loading'}>
            {creating.status === 'loading' ? 'Creating…' : 'Create treaty'}
          </Button>
          <Button asChild variant="ghost">
            <Link to=".." relative="path">
              Cancel
            </Link>
          </Button>
        </div>
      </form>
    </>
  );
}

// react-hook-form's FieldErrors does not narrow per-branch on a discriminated
// union used directly as TFieldValues -- same limitation ClaimSettlementPanel
// and CommissionPlanPanel's rule rows hit.
function fieldError(errors: FieldErrors<CreateTreatyFormValues>, field: string): string | undefined {
  const rec = errors as Record<string, { message?: string } | undefined>;
  return rec[field]?.message;
}
