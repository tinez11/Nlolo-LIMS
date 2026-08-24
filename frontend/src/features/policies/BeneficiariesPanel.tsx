import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import type { BeneficiaryInput } from '@/api/types';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { usePolicyStore, selectSavingBeneficiaries } from '@/store/policyStore';
import {
  beneficiariesFormSchema,
  blankBeneficiaryRow,
  toApiBeneficiaries,
  type BeneficiaryFormInput,
  type BeneficiaryFormValues,
} from './beneficiaryForm';
import { Field } from './Field';

/**
 * The one mutating form in this slice, deliberately placed on the full detail page
 * rather than the drawer -- "drawer previews, page acts": the preview is
 * dismissable by a stray backdrop click, so it must never host a state change.
 *
 * `PUT /policies/{n}/beneficiaries` REPLACES the whole set in one call, so this
 * edits the entire list at once rather than one row at a time; there is no
 * per-beneficiary PATCH on this platform.
 */
export function BeneficiariesPanel({
  policyNumber,
  beneficiaries,
}: {
  policyNumber: string;
  beneficiaries: BeneficiaryInput[];
}) {
  const [editing, setEditing] = useState(false);
  const resetSaveBeneficiaries = usePolicyStore((s) => s.resetSaveBeneficiaries);

  if (!editing) {
    return (
      <ReadView
        beneficiaries={beneficiaries}
        onEdit={() => {
          // Clears any error left over from a previous, unrelated attempt --
          // without this a stale rejection reappears the instant editing starts
          // again, before this attempt has done anything wrong.
          resetSaveBeneficiaries(policyNumber);
          setEditing(true);
        }}
      />
    );
  }

  return (
    <EditForm
      policyNumber={policyNumber}
      beneficiaries={beneficiaries}
      onCancel={() => setEditing(false)}
      onSaved={() => setEditing(false)}
    />
  );
}

function ReadView({
  beneficiaries,
  onEdit,
}: {
  beneficiaries: BeneficiaryInput[];
  onEdit: () => void;
}) {
  return (
    <div className="px-4 pb-4">
      {beneficiaries.length === 0 ? (
        <p className="pb-2 text-xs text-muted-foreground">None recorded.</p>
      ) : (
        <dl>
          {beneficiaries.map((b, index) => (
            <Field
              key={`${b.partyId ?? b.freeformDesignee ?? 'beneficiary'}-${index}`}
              label={b.type === 'FREEFORM' ? 'Freeform' : 'Party'}
              value={<span className="font-mono text-xs">{b.freeformDesignee ?? b.partyId ?? '—'}</span>}
              {...(typeof b.sharePercent === 'number' ? { note: `${b.sharePercent}% share` } : {})}
            />
          ))}
        </dl>
      )}
      <Button size="sm" variant="ghost" className="-ml-2 mt-1" onClick={onEdit}>
        Edit beneficiaries
      </Button>
    </div>
  );
}

function toFormValues(beneficiaries: BeneficiaryInput[]): BeneficiaryFormInput {
  return {
    beneficiaries: beneficiaries.map((b) => ({
      type: b.type,
      partyId: b.partyId ?? '',
      freeformDesignee: b.freeformDesignee ?? '',
      sharePercent: b.sharePercent,
      revocable: b.revocable,
    })),
  };
}

function EditForm({
  policyNumber,
  beneficiaries,
  onCancel,
  onSaved,
}: {
  policyNumber: string;
  beneficiaries: BeneficiaryInput[];
  onCancel: () => void;
  onSaved: () => void;
}) {
  const saveBeneficiaries = usePolicyStore((s) => s.saveBeneficiaries);
  const saving = usePolicyStore(selectSavingBeneficiaries(policyNumber));

  // Three generics: Input (raw, pre-coercion -- what register()/watch() see),
  // Context (unused), Output (post-coercion -- what handleSubmit's callback
  // receives). Without the third, sharePercent's `unknown` input type and `number`
  // output type disagree and the resolver fails to typecheck against useForm.
  const {
    control,
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<BeneficiaryFormInput, unknown, BeneficiaryFormValues>({
    resolver: zodResolver(beneficiariesFormSchema),
    defaultValues: toFormValues(beneficiaries),
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'beneficiaries' });

  // react-hook-form's watch() returns a live-subscribed value the React Compiler
  // cannot safely memoize (a known, inherent interaction, not a bug in this
  // component) -- disabling the compiler's warning here rather than the value
  // itself, which is correct and re-renders exactly when a watched field changes.
  // eslint-disable-next-line react-hooks/incompatible-library
  const rows = watch('beneficiaries');
  // `sharePercent` is `unknown` pre-coercion while the user is typing (z.coerce
  // accepts anything), so Number(...) here rather than trusting it is already a
  // number -- matches how the schema itself will coerce it on submit.
  const total = rows.reduce((sum, r) => {
    const value = Number(r.sharePercent);
    return sum + (Number.isFinite(value) ? value : 0);
  }, 0);
  const totalOk = Math.abs(total - 100) <= 0.005 || rows.length === 0;

  // A save that already succeeded closes the form itself, from an effect rather
  // than the submit handler -- the store's own request sequencing (see
  // createResourceSlice.track) is the single source of truth for "did this
  // succeed", so this reacts to that instead of trusting the submit promise
  // directly, which a superseded/discarded request would resolve without ever
  // actually applying.
  useEffect(() => {
    if (saving.status === 'success') onSaved();
    // onSaved intentionally omitted: it is a fresh closure each render (it flips
    // local `editing` state in the parent) and would otherwise re-run this effect
    // every render without ever changing `saving.status`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saving.status]);

  async function onSubmit(values: BeneficiaryFormValues) {
    await saveBeneficiaries(policyNumber, toApiBeneficiaries(values));
  }

  return (
    <form className="px-4 pb-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      <div className="space-y-3">
        {fields.map((field, index) => {
          const type = rows[index]?.type ?? 'PARTY';
          const rowError = errors.beneficiaries?.[index];
          return (
            <div key={field.id} className="rounded-md border border-border p-2.5">
              <div className="flex items-center gap-2">
                <select
                  className="h-8 rounded-md border border-input bg-surface px-2 text-xs"
                  {...register(`beneficiaries.${index}.type`)}
                >
                  <option value="PARTY">Party</option>
                  <option value="FREEFORM">Freeform</option>
                </select>

                {type === 'PARTY' ? (
                  <input
                    className="h-8 flex-1 rounded-md border border-input bg-surface px-2 font-mono text-xs"
                    placeholder="Party id (uuid)"
                    {...register(`beneficiaries.${index}.partyId`)}
                  />
                ) : (
                  <input
                    className="h-8 flex-1 rounded-md border border-input bg-surface px-2 text-xs"
                    placeholder={'Designee, e.g. "My Estate"'}
                    {...register(`beneficiaries.${index}.freeformDesignee`)}
                  />
                )}

                <div className="flex items-center gap-1">
                  <input
                    type="number"
                    min={0}
                    max={100}
                    step="0.01"
                    className="h-8 w-20 rounded-md border border-input bg-surface px-2 text-right text-xs"
                    {...register(`beneficiaries.${index}.sharePercent`)}
                  />
                  <span className="text-xs text-muted-foreground">%</span>
                </div>

                <Button
                  type="button"
                  size="icon"
                  variant="ghost"
                  aria-label="Remove beneficiary"
                  onClick={() => remove(index)}
                >
                  <X />
                </Button>
              </div>

              <label className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground">
                <input type="checkbox" {...register(`beneficiaries.${index}.revocable`)} />
                Revocable
              </label>

              {(rowError?.partyId ?? rowError?.freeformDesignee ?? rowError?.sharePercent) && (
                <p className="mt-1 text-[11px] text-status-danger-fg">
                  {rowError?.partyId?.message ??
                    rowError?.freeformDesignee?.message ??
                    rowError?.sharePercent?.message}
                </p>
              )}
            </div>
          );
        })}
      </div>

      <Button
        type="button"
        size="sm"
        variant="ghost"
        className="-ml-2 mt-2"
        onClick={() => append(blankBeneficiaryRow())}
      >
        <Plus />
        Add beneficiary
      </Button>

      <p
        className={cn(
          'mt-2 text-xs',
          totalOk ? 'text-muted-foreground' : 'text-status-danger-fg',
        )}
      >
        Total: {total}% {!totalOk && '— must sum to 100% (or be empty)'}
      </p>

      {errors.beneficiaries?.root?.message && (
        <p className="mt-1 text-[11px] text-status-danger-fg">{errors.beneficiaries.root.message}</p>
      )}

      {/* A 422 here is a whole-request business rule (exactly-one-of, sum-to-100) --
          BeneficiaryValidationException carries no per-field errors[] array, unlike a
          400 VALIDATION_ERROR elsewhere on this platform, so it renders as one banner
          rather than being bound to a specific input. */}
      {saving.status === 'error' && saving.error && (
        // role="alert" is a real fix, not just a test hook: a screen reader user
        // submitting this form needs the rejection announced immediately, the same
        // way ErrorPanel's role="alert" works elsewhere on this platform.
        <div
          role="alert"
          className="mt-2 rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg"
        >
          {saving.error.detail ?? saving.error.title}
          {saving.error.traceId && (
            <span className="ml-2 font-mono text-[10px] opacity-80">({saving.error.traceId})</span>
          )}
        </div>
      )}

      <div className="mt-3 flex items-center gap-2">
        <Button type="submit" size="sm" variant="primary" disabled={saving.status === 'loading'}>
          {saving.status === 'loading' ? 'Saving…' : 'Save'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onCancel} disabled={saving.status === 'loading'}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
