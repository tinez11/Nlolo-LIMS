import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import type { CoveredLifeView } from '@/api/types';
import { addCoveredLife, getCoveredLives, promoteCoveredLife, removeCoveredLife, takeOverFuneralPolicy } from '@/api/funeral';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { FUNERAL_ROLE_LABELS, type FuneralRoleName } from '@/features/products/funeralSchema';
import { remember, remembered } from '@/lib/remembered';
import {
  awaitingTakeover,
  blankCoveredLife,
  coveredLifeFormSchema,
  identifyFormSchema,
  toAddCoveredLife,
  toIdentify,
  type CoveredLifeFormValues,
  type IdentifyFormValues,
} from './coveredLifeForm';

const END_REASON_LABELS: Record<string, string> = {
  DECEASED: 'Deceased',
  REMOVED: 'Removed',
  AGED_OUT: 'Aged out',
  MAIN_MEMBER_DIED: 'Main member died',
  FREE_COVER_ENDED: 'Free cover ended',
  POLICY_ENDED: 'Policy ended',
};

const tzs = (amount: number) => formatMoney({ amount: String(amount), currencyCode: 'TZS' });

function ageOf(dateOfBirth: string): number {
  const born = new Date(dateOfBirth);
  const now = new Date();
  let age = now.getFullYear() - born.getFullYear();
  if (now.getMonth() < born.getMonth() || (now.getMonth() === born.getMonth() && now.getDate() < born.getDate())) age--;
  return age;
}

/**
 * Every life on a funeral policy (family funeral cover): its role, benefit, premium, its own cover start and
 * waiting period, and how it ended. Staff add and remove lives (from the next premium date), promote a
 * name-only life to a client at claim, and complete a spouse's takeover after the main member's death.
 */
export function CoveredLivesPanel({ policyNumber, canChange }: { policyNumber: string; canChange: boolean }) {
  const [lives, setLives] = useState<CoveredLifeView[] | null>(() =>
    remembered<CoveredLifeView[]>(`covered-lives:${policyNumber}`),
  );
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const [removalReason, setRemovalReason] = useState('');
  const [promoting, setPromoting] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    getCoveredLives(policyNumber).then(
      (result) => { if (live) { setLives(remember(`covered-lives:${policyNumber}`, result)); setLoadError(null); } },
      (error: unknown) => { if (live) setLoadError(toApiError(error)); },
    );
    return () => { live = false; };
  }, [policyNumber, reload]);

  async function act(work: () => Promise<unknown>) {
    setActionError(null);
    try {
      await work();
      setReload((n) => n + 1);
      return true;
    } catch (error) {
      setActionError(toApiError(error));
      return false;
    }
  }

  if (loadError) return <ErrorPanel error={loadError} />;
  if (lives === null) return <LoadingBlock />;

  return (
    <div className="space-y-4">
      {actionError && <InlineError error={actionError} />}
      {canChange && awaitingTakeover(lives) && (
        <TakeoverForm onSubmit={(v) => act(() => takeOverFuneralPolicy(policyNumber, toIdentify(v)))} />
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">Covered lives</caption>
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="p-2">Role</th><th className="p-2">Name</th><th className="p-2">Age</th>
              <th className="p-2">Benefit</th><th className="p-2">Yearly premium</th><th className="p-2">Cover start</th>
              <th className="p-2">Waiting period ends</th><th className="p-2">Status</th>
              {canChange && <th className="p-2"><span className="sr-only">Actions</span></th>}
            </tr>
          </thead>
          <tbody>
            {lives.map((life) => (
              <tr key={life.coveredLifeId} className="border-t border-border align-top">
                <td className="p-2">{FUNERAL_ROLE_LABELS[life.role as FuneralRoleName]}</td>
                <td className="p-2">{life.fullName}{life.student ? ' (student)' : ''}</td>
                <td className="p-2">{ageOf(life.dateOfBirth)}</td>
                <td className="p-2">{tzs(life.benefit)}</td>
                <td className="p-2">{tzs(life.yearlyPremium)}</td>
                <td className="p-2">{formatDate(life.coverStart)}</td>
                <td className="p-2">{formatDate(life.waitingPeriodEnds)}</td>
                <td className="p-2">
                  {life.status === 'ACTIVE'
                    ? life.coverEnd ? `Covered to ${formatDate(life.coverEnd)}` : 'Covered'
                    : `${END_REASON_LABELS[life.endReason ?? ''] ?? life.endReason} ${formatDate(life.endedOn)}`}
                </td>
                {canChange && (
                  <td className="space-y-2 p-2">
                    {life.status === 'ACTIVE' && life.role !== 'MAIN_MEMBER' && !life.coverEnd && removing !== life.coveredLifeId && (
                      <Button type="button" variant="outline" size="sm" aria-label={`Remove life ${life.fullName}`}
                        onClick={() => { setRemoving(life.coveredLifeId); setRemovalReason(''); }}>
                        Remove life
                      </Button>
                    )}
                    {removing === life.coveredLifeId && (
                      <div className="space-y-1">
                        <FormField label="Reason for removal">
                          <Input inputSize="sm" value={removalReason} onChange={(e) => setRemovalReason(e.target.value)} />
                        </FormField>
                        <p className="text-xs text-subtle-foreground">Covered to the next premium date, then off the policy.</p>
                        <div className="flex gap-2">
                          <Button type="button" size="sm" onClick={async () => {
                            if (await act(() => removeCoveredLife(policyNumber, life.coveredLifeId, removalReason))) setRemoving(null);
                          }}>Confirm removal</Button>
                          <Button type="button" variant="ghost" size="sm" onClick={() => setRemoving(null)}>Cancel</Button>
                        </div>
                      </div>
                    )}
                    {!life.partyId && promoting !== life.coveredLifeId && (
                      <Button type="button" variant="ghost" size="sm" aria-label={`Promote ${life.fullName} to client`}
                        onClick={() => setPromoting(life.coveredLifeId)}>
                        Promote to client
                      </Button>
                    )}
                    {promoting === life.coveredLifeId && (
                      <IdentifyForm submitLabel="Promote to client" onCancel={() => setPromoting(null)}
                        onSubmit={async (v) => {
                          if (await act(() => promoteCoveredLife(policyNumber, life.coveredLifeId, toIdentify(v)))) setPromoting(null);
                        }} />
                    )}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {canChange && (adding
        ? <AddLifeForm onCancel={() => setAdding(false)} onSubmit={async (v) => {
            if (await act(() => addCoveredLife(policyNumber, toAddCoveredLife(v)))) setAdding(false);
          }} />
        : <Button type="button" variant="outline" size="sm" onClick={() => setAdding(true)}>Add life</Button>)}
    </div>
  );
}

function AddLifeForm({ onSubmit, onCancel }: { onSubmit: (v: CoveredLifeFormValues) => void; onCancel: () => void }) {
  const { register, control, handleSubmit, formState: { errors, isSubmitting } } = useForm<CoveredLifeFormValues>({
    resolver: zodResolver(coveredLifeFormSchema),
    defaultValues: blankCoveredLife(),
  });
  return (
    <form className="space-y-3 rounded-md border border-border p-3" onSubmit={handleSubmit(onSubmit)}>
      <p className="text-xs text-subtle-foreground">
        Covered from the next premium date, with its own waiting period. The premium rises from then.
      </p>
      <div className="grid grid-cols-2 gap-3">
        <FormField label="Role">
          <Select inputSize="sm" {...register('role')}>
            {(['SPOUSE', 'CHILD', 'PARENT', 'EXTENDED'] as const).map((r) => (
              <option key={r} value={r}>{FUNERAL_ROLE_LABELS[r]}</option>
            ))}
          </Select>
        </FormField>
        <FormField label="Full name" error={errors.fullName?.message}>
          <Input inputSize="sm" {...register('fullName')} />
        </FormField>
        <FormField label="Date of birth" error={errors.dateOfBirth?.message}>
          <Controller control={control} name="dateOfBirth"
            render={({ field }) => <DatePicker value={field.value} onChange={field.onChange} />} />
        </FormField>
        <FormField label="Sex">
          <Select inputSize="sm" {...register('sex')}>
            <option value="">Not recorded</option>
            <option value="FEMALE">Female</option>
            <option value="MALE">Male</option>
          </Select>
        </FormField>
        <FormField label="ID number (optional)">
          <Input inputSize="sm" {...register('idNumber')} />
        </FormField>
        <div className="pt-6"><CheckboxField label="Student (a child only)" {...register('student')} /></div>
      </div>
      <div className="flex gap-2">
        <Button type="submit" size="sm" pending={isSubmitting}>Add life</Button>
        <Button type="button" variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
      </div>
    </form>
  );
}

export function IdentifyForm({ submitLabel, onSubmit, onCancel }: {
  submitLabel: string; onSubmit: (v: IdentifyFormValues) => void; onCancel?: () => void;
}) {
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<IdentifyFormValues>({
    resolver: zodResolver(identifyFormSchema),
    defaultValues: { idType: '', idNumber: '', phoneNumber: '', sex: '' },
  });
  return (
    <form className="space-y-2" onSubmit={handleSubmit(onSubmit)}>
      <div className="grid grid-cols-2 gap-2">
        <FormField label="Identity document" error={errors.idType?.message}>
          <Select inputSize="sm" {...register('idType')}>
            <option value="">Choose…</option>
            <option value="NATIONAL_ID">National ID</option>
            <option value="PASSPORT">Passport</option>
            <option value="DRIVING_LICENCE">Driving licence</option>
            <option value="VOTER_ID">Voter ID</option>
          </Select>
        </FormField>
        <FormField label="Document number" error={errors.idNumber?.message}>
          <Input inputSize="sm" {...register('idNumber')} />
        </FormField>
        <FormField label="Phone number">
          <Input inputSize="sm" {...register('phoneNumber')} />
        </FormField>
        <FormField label="Sex">
          <Select inputSize="sm" {...register('sex')}>
            <option value="">Not recorded</option>
            <option value="FEMALE">Female</option>
            <option value="MALE">Male</option>
          </Select>
        </FormField>
      </div>
      <div className="flex gap-2">
        <Button type="submit" size="sm" pending={isSubmitting}>{submitLabel}</Button>
        {onCancel && <Button type="button" variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>}
      </div>
    </form>
  );
}

function TakeoverForm({ onSubmit }: { onSubmit: (v: IdentifyFormValues) => void }) {
  return (
    <div className="space-y-2 rounded-md border border-status-warning-fg/40 bg-status-warning-bg p-3">
      <p className="text-sm font-medium">The main member has died: the spouse takes over this policy</p>
      <p className="text-xs text-subtle-foreground">
        Record the spouse’s identity document. They become the policyholder and main member, priced as the main
        member from the next premium date.
      </p>
      <IdentifyForm submitLabel="Complete takeover" onSubmit={onSubmit} />
    </div>
  );
}
