import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { useManualJournalsStore } from '@/store/manualJournalsStore';
import { useReferenceCodes } from '@/store/refdataStore';
import {
  blankJournal,
  blankLine,
  fromJournal,
  fromTemplate,
  manualJournalSchema,
  toInput,
  totals,
  type ManualJournalValues,
} from './manualJournalForm';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { UnsavedGuard } from '@/components/UnsavedGuard';

const thisMonth = () => new Date().toISOString().slice(0, 7);
const money = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Preparing a manual journal (IFRS 17 I4): from one of the guide's templates (Part 4), a saved one, or blank. The
 * draft is saved, documents and a lines file are added on its page, and it is submitted from there.
 */
export function ManualJournalEditorPage() {
  const { id } = useParams();
  const current = useManualJournalsStore((s) => s.current);
  const load = useManualJournalsStore((s) => s.load);

  useEffect(() => {
    if (id) void load(id);
  }, [id, load]);

  if (!id) return <Editor initial={blankJournal(thisMonth())} />;
  if (current.status === 'error' && current.error && current.data?.id !== id) {
    return <ErrorPanel error={current.error} onRetry={() => void load(id)} />;
  }
  if (!current.data || current.data.id !== id) return <LoadingBlock />;
  return <Editor key={id} id={id} initial={fromJournal(current.data)} />;
}

function Editor({ id, initial }: { id?: string; initial: ManualJournalValues }) {
  const navigate = useNavigate();
  const save = useManualJournalsStore((s) => s.save);
  const acting = useManualJournalsStore((s) => s.acting.save);
  const templates = useManualJournalsStore((s) => s.templates);
  const loadTemplates = useManualJournalsStore((s) => s.loadTemplates);
  const reasons = useReferenceCodes('JOURNAL_REASON');
  const branches = useReferenceCodes('BRANCH');
  const [templateId, setTemplateId] = useState('');

  useEffect(() => {
    void loadTemplates();
  }, [loadTemplates]);

  const form = useForm<ManualJournalValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(manualJournalSchema), defaultValues: initial });
  const lines = useFieldArray({ control: form.control, name: 'lines' });
  const watched = useWatch({ control: form.control, name: 'lines' });
  const { debit, credit } = totals(watched ?? []);
  const offered = useMemo(() => (templates.data ?? []).filter((t) => t.postedBy == null), [templates.data]);

  function applyTemplate(value: string) {
    setTemplateId(value);
    const t = offered.find((x) => x.id === value);
    if (t) form.reset(fromTemplate(t, form.getValues('period')));
  }

  const errors = form.formState.errors;
  return (
    <>
      <PageHeader
        title={id ? 'Edit manual journal' : 'New manual journal'}
        description="Accounts and amounts, why, and when. Only manual (MAN) or mixed (BOTH) accounts can be used."
      />
      <form
        className="space-y-4 px-6 pb-6"
        aria-label="Manual journal"
        onSubmit={form.handleSubmit(async (v) => {
          const saved = await save(id ?? null, toInput(v));
          if (saved) navigate(id ? `../../${saved.id}` : `../${saved.id}`, { relative: 'path' });
        })}
      >
        <UnsavedGuard
          when={form.formState.isDirty && !form.formState.isSubmitting}
          what="This journal"
        />
        {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
        {!id && (
          <div className="max-w-xl">
            <FormField label="Start from a template" hint="The guide's manual entries (Part 4) and your saved templates.">
              <Select value={templateId} onChange={(e) => applyTemplate(e.target.value)}>
                <option value="">Blank journal</option>
                {offered.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.source === 'GUIDE' ? `${t.id} — ${t.title}` : `Saved: ${t.title}`}
                  </option>
                ))}
              </Select>
            </FormField>
          </div>
        )}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="Title" error={errors.title?.message}>
            <Input {...form.register('title')} />
          </FormField>
          <FormField label="Period" hint="YYYY-MM; an open or closing period" error={errors.period?.message}>
            <Input {...form.register('period')} />
          </FormField>
          <FormField label="Reason" hint="Why this journal is needed" error={errors.reason?.message}>
            <Input {...form.register('reason')} />
          </FormField>
          <FormField label="Reason code" hint="Required when a line is on a mixed (BOTH) account">
            <Select {...form.register('reasonCode')}>
              <option value="">None</option>
              {reasons.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.label}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Reverse automatically on" hint="An accrual: the platform reverses it on this date. Blank: never.">
            <Input type="date" {...form.register('autoReverseOn')} />
          </FormField>
        </div>

        <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Lines">
          <p className="text-xs font-medium">Lines</p>
          <div className="overflow-x-auto">
            <table className="w-full text-sm" aria-label="Journal lines">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="w-20 py-1 pr-2 font-normal">Side</th>
                  <th className="w-28 py-1 pr-2 font-normal">Account</th>
                  <th className="w-40 py-1 pr-2 font-normal">Amount</th>
                  <th className="py-1 pr-2 font-normal">Description</th>
                  <th className="w-36 py-1 pr-2 font-normal">Branch</th>
                  <th className="w-28 py-1 pr-2 font-normal">Fund</th>
                  <th className="w-10 py-1 font-normal" />
                </tr>
              </thead>
              <tbody>
                {lines.fields.map((field, i) => (
                  <tr key={field.id} className="border-t border-border align-top">
                    <td className="py-1 pr-2">
                      <Select inputSize="sm" aria-label={`Line ${i + 1} side`} {...form.register(`lines.${i}.side`)}>
                        <option value="DR">Dr</option>
                        <option value="CR">Cr</option>
                      </Select>
                    </td>
                    <td className="py-1 pr-2">
                      <Input inputSize="sm" aria-label={`Line ${i + 1} account`} {...form.register(`lines.${i}.accountCode`)} />
                      {errors.lines?.[i]?.accountCode && (
                        <p className="text-xs text-status-danger-fg">{errors.lines[i]?.accountCode?.message}</p>
                      )}
                    </td>
                    <td className="py-1 pr-2">
                      <Input inputSize="sm" inputMode="decimal" aria-label={`Line ${i + 1} amount`} {...form.register(`lines.${i}.amount`)} />
                      {errors.lines?.[i]?.amount && (
                        <p className="text-xs text-status-danger-fg">{errors.lines[i]?.amount?.message}</p>
                      )}
                    </td>
                    <td className="py-1 pr-2">
                      <Input inputSize="sm" aria-label={`Line ${i + 1} description`} {...form.register(`lines.${i}.description`)} />
                    </td>
                    <td className="py-1 pr-2">
                      <Select inputSize="sm" aria-label={`Line ${i + 1} branch`} {...form.register(`lines.${i}.branch`)}>
                        <option value="">—</option>
                        {branches.map((b) => (
                          <option key={b.code} value={b.code}>
                            {b.code}
                          </option>
                        ))}
                      </Select>
                    </td>
                    <td className="py-1 pr-2">
                      <Input inputSize="sm" aria-label={`Line ${i + 1} fund`} {...form.register(`lines.${i}.fund`)} />
                    </td>
                    <td className="py-1">
                      <Button type="button" size="sm" variant="ghost" aria-label={`Remove line ${i + 1}`}
                        onClick={() => lines.remove(i)} disabled={lines.fields.length <= 2}>
                        ×
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t border-border text-xs">
                  <td colSpan={2} className="py-1 pr-2 text-muted-foreground">Totals</td>
                  <td className="py-1 pr-2 tabular-nums" colSpan={5}>
                    Dr {money(debit)} · Cr {money(credit)}
                    {Math.round(debit * 100) !== Math.round(credit * 100) && (
                      <span className="ml-2 text-status-danger-fg">out by {money(Math.abs(debit - credit))}</span>
                    )}
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
          {errors.lines?.message && <p className="text-xs text-status-danger-fg">{errors.lines.message}</p>}
          {errors.lines?.root?.message && <p className="text-xs text-status-danger-fg">{errors.lines.root.message}</p>}
          <Button type="button" size="sm" variant="outline" onClick={() => lines.append(blankLine())}>
            Add line
          </Button>
        </section>

        <div className="flex gap-2">
          <Button type="submit" variant="primary" disabled={acting?.status === 'loading'}>
            Save draft
          </Button>
          <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
            Cancel
          </Button>
        </div>
      </form>
    </>
  );
}

