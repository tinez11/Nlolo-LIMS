import { Upload, UserMinus, UserPlus } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import {
  addGroupFuneralFamily,
  addGroupFuneralLife,
  downloadGroupScheduleTemplate,
  groupFuneralMemberLeaves,
  joinGroupFuneralFamilies,
  listGroupFuneralFamilies,
  removeGroupFuneralLife,
} from '@/api/groupFuneral';
import { getPolicy } from '@/api/policies';
import type { GroupFuneralFamilyView, GroupFuneralJoiningReport, PolicyView } from '@/api/types';
import { toApiError, type ApiError } from '@/lib/apiError';
import { asSex } from './groupFuneral';
import { canUnderwriteGroupSchemes, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { FUNERAL_ROLE_LABELS } from '@/features/products/funeralSchema';
import { formatDate, todayIso } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { formatMoney } from '@/lib/money';
import { DependantFields, FamilyEditor } from './FamilyEditor';
import { blankDependant, blankFamily, incompleteFamilies, toLives, type DependantRow, type FamilyRow } from './groupFuneral';

/**
 * A group funeral scheme's families (2026-10-07): each main member -- what the association's bill
 * counts -- with every life of their family, and the acts on them: a family joins (typed or by the
 * association's file), a life joins or comes off a family, a member leaves. Every change is the
 * server's to check against the plan; this shows its answer.
 */
export function GroupFuneralFamiliesPanel({ policyNumber, onChanged }: { policyNumber: string; onChanged: () => void }) {
  const canUnderwrite = canUnderwriteGroupSchemes(readIdentity(useAuth().user?.access_token));
  const [families, setFamilies] = useState<GroupFuneralFamilyView[] | null>(null);
  const [policy, setPolicy] = useState<PolicyView | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [version, setVersion] = useState(0);
  const [adding, setAdding] = useState<FamilyRow | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [report, setReport] = useState<GroupFuneralJoiningReport | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    Promise.all([listGroupFuneralFamilies(policyNumber), getPolicy(policyNumber)]).then(([f, p]) => {
      if (!live) return;
      setFamilies(f);
      setPolicy(p);
      setLoadError(null);
    }, (e: unknown) => { if (live) setLoadError(toApiError(e)); });
    return () => { live = false; };
  }, [policyNumber, version]);

  /** Runs one change; on success reloads the families and the scheme, on refusal shows the server's words. */
  async function act(change: () => Promise<unknown>): Promise<boolean> {
    setError(null);
    try {
      await change();
      setVersion((v) => v + 1);
      onChanged();
      return true;
    } catch (e) {
      setError(toApiError(e));
      return false;
    }
  }

  async function joinFile(file: File) {
    setError(null);
    try {
      const result = await joinGroupFuneralFamilies(policyNumber, file);
      setReport(result);
      setVersion((v) => v + 1);
      onChanged();
    } catch (e) {
      setError(toApiError(e));
    }
  }

  const active = (families ?? []).filter((f) => f.status === 'ACTIVE').length;
  const inForce = policy?.status === 'ACTIVE' || policy?.status === 'REINSTATED';
  // A yearly plan (2026-10-08): one bill for the year, and the members and their families fixed while it is in force.
  const yearly = policy?.premiumFrequency === 'ANNUALLY';
  const mayChange = inForce && !yearly;

  return (
    <Panel title="Members and their families"
      subtitle={policy ? `The bill: ${active} member${active === 1 ? '' : 's'}, ${formatMoney(policy.premium)} ${yearly ? 'a year' : 'a month'}`
        : 'Each family under its main member; the bill counts members, not lives.'}>
      {yearly && inForce && (
        <p className="border-b border-border px-4 py-2.5 text-xs text-muted-foreground" role="status">
          A yearly plan: the association is billed once for the year, and these members and their families are fixed
          while the policy is in force. A death claim still ends that life.
        </p>
      )}
      {canUnderwrite && !(yearly && inForce) && (
        <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2.5">
          <Button size="sm" variant="primary" disabled={!inForce || adding !== null}
            onClick={() => setAdding(blankFamily((families ?? []).map((f) => ({ ...blankFamily([]), reference: f.memberReference ?? '' }))))}>
            <UserPlus />
            Add member
          </Button>
          <input ref={fileInput} type="file" accept=".csv,text/csv" className="hidden" aria-label="Joining file"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void joinFile(f); e.target.value = ''; }} />
          <Button size="sm" variant="ghost" disabled={!inForce} onClick={() => fileInput.current?.click()}>
            <Upload />
            Join by file
          </Button>
          <Button size="sm" variant="ghost"
            onClick={async () => saveBlob(await downloadGroupScheduleTemplate(), 'group-funeral-schedule-template.csv')}>
            Download template
          </Button>
          {!inForce && policy && (
            <span className="text-xs text-muted-foreground">Families join once the association&apos;s first premium has cleared.</span>
          )}
        </div>
      )}

      {error && <div className="px-4 pt-3"><InlineError error={error} /></div>}

      {report && (
        <div className="space-y-1 px-4 pt-3 text-xs" aria-label="Joining report">
          <p><strong>{report.joined.length}</strong> famil{report.joined.length === 1 ? 'y' : 'ies'} joined
            {report.joined.length > 0 && `: ${report.joined.map((j) => `${j.memberReference} ${j.mainMemberName}`).join(', ')}`}.</p>
          {report.refused.map((r) => (
            <p key={r.memberReference} className="text-status-danger-fg">
              {r.memberReference} refused — {r.problems.join('; ')}
            </p>
          ))}
          {report.fileProblems.map((p) => <p key={p} className="text-status-danger-fg">{p}</p>)}
        </div>
      )}

      {adding && (
        <div className="space-y-2 px-4 pt-3">
          <FamilyEditor family={adding} onChange={setAdding} />
          <JoinFamilyActions family={adding} onCancel={() => setAdding(null)}
            onJoin={async (joinedOn) => {
              if (await act(() => addGroupFuneralFamily(policyNumber, toLives([adding]), joinedOn || null))) setAdding(null);
            }} />
        </div>
      )}

      {loadError ? (
        <div className="p-4"><ErrorPanel error={loadError} onRetry={() => setVersion((v) => v + 1)} /></div>
      ) : families === null ? (
        <TableSkeleton rows={3} />
      ) : families.length === 0 ? (
        <EmptyState title="No members yet" description="Families join when the association's first premium clears." />
      ) : (
        <div className="divide-y divide-border">
          {families.map((family) => (
            <FamilyBlock key={family.policyMemberId} family={family} policyNumber={policyNumber} currency={policy?.sumAssured?.currencyCode ?? 'TZS'}
              canUnderwrite={canUnderwrite} inForce={mayChange} act={act} />
          ))}
        </div>
      )}
    </Panel>
  );
}

function JoinFamilyActions({ family, onJoin, onCancel }: {
  family: FamilyRow; onJoin: (joinedOn: string) => Promise<void>; onCancel: () => void;
}) {
  const [joinedOn, setJoinedOn] = useState('');
  const missing = incompleteFamilies([family]);
  return (
    <div className="flex flex-wrap items-end gap-2">
      <FormField label="Joins on (blank: today)">
        <DatePicker value={joinedOn || null} onChange={(iso) => setJoinedOn(iso ?? '')} />
      </FormField>
      <Button size="sm" variant="primary" disabled={missing.length > 0} onClick={() => void onJoin(joinedOn)}>
        Join the family
      </Button>
      <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
      {missing.length > 0 && <span className="text-xs text-muted-foreground">{missing[0]}</span>}
    </div>
  );
}

function FamilyBlock({ family, policyNumber, currency, canUnderwrite, inForce, act }: {
  family: GroupFuneralFamilyView;
  policyNumber: string;
  currency: string;
  canUnderwrite: boolean;
  inForce: boolean;
  act: (change: () => Promise<unknown>) => Promise<boolean>;
}) {
  const [newLife, setNewLife] = useState<DependantRow | null>(null);
  const [leaving, setLeaving] = useState<string | null>(null);
  const memberActive = family.status === 'ACTIVE';

  return (
    <div className="px-4 py-3" aria-label={`Member ${family.memberReference}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <span className="font-medium">{family.memberReference} · {family.mainMemberName}</span>
          <span className="ml-2 text-xs text-muted-foreground">
            joined {formatDate(family.joinedOn)}
            {family.leftOn && ` · covered to ${formatDate(family.leftOn)}`}
            {family.beneficiaryName && ` · beneficiary ${family.beneficiaryName}${family.beneficiaryRelationship
              ? ` (${family.beneficiaryRelationship})` : ''}`}
          </span>
        </div>
        <div className="flex items-center gap-2 text-xs">
          <span>Family cover <strong>{formatMoney({ amount: String(family.familyCover), currencyCode: currency })}</strong></span>
          <StatusBadge kind="member" value={family.status} />
        </div>
      </div>

      <table className="mt-2 w-full text-xs">
        <thead className="text-left text-subtle-foreground">
          <tr><th className="py-1">Life</th><th>Role</th><th>Born</th><th>Benefit</th><th>Waiting ends</th><th>Cover</th><th /></tr>
        </thead>
        <tbody>
          {family.lives.map((life) => (
            <tr key={life.coveredLifeId} className="border-t border-border">
              <td className="py-1">{life.fullName}{life.student ? ' (student)' : ''}</td>
              <td>{FUNERAL_ROLE_LABELS[life.role]}</td>
              <td>{formatDate(life.dateOfBirth)}</td>
              <td>{formatMoney({ amount: String(life.benefit), currencyCode: currency })}</td>
              <td>{life.waitingPeriodEnds ? formatDate(life.waitingPeriodEnds) : '—'}</td>
              <td>
                {life.status === 'ENDED'
                  ? `Ended ${formatDate(life.endedOn)} (${(life.endReason ?? '').toLowerCase().replaceAll('_', ' ')})`
                  : life.coverEnd ? `To ${formatDate(life.coverEnd)}` : 'Covered'}
              </td>
              <td className="text-right">
                {inForce && life.role !== 'MAIN_MEMBER' && life.status === 'ACTIVE' && !life.coverEnd && (
                  <Button size="sm" variant="ghost" aria-label={`Remove ${life.fullName}`}
                    onClick={() => void act(() => removeGroupFuneralLife(policyNumber, life.coveredLifeId, ''))}>
                    Remove
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {memberActive && inForce && (
        <div className="mt-1 flex flex-wrap items-end gap-2">
          {newLife ? (
            <>
              <div className="min-w-[36rem] flex-1"><DependantFields dependant={newLife} onChange={(c) => setNewLife({ ...newLife, ...c })} /></div>
              <Button size="sm" variant="primary" disabled={!newLife.fullName.trim() || !newLife.dateOfBirth}
                onClick={async () => {
                  const ok = await act(() => addGroupFuneralLife(policyNumber, family.policyMemberId, {
                    role: newLife.role, fullName: newLife.fullName.trim(), dateOfBirth: newLife.dateOfBirth,
                    sex: asSex(newLife.sex), student: newLife.student,
                  }));
                  if (ok) setNewLife(null);
                }}>
                Add to family
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setNewLife(null)}>Cancel</Button>
            </>
          ) : (
            <Button size="sm" variant="ghost" className="-ml-2" onClick={() => setNewLife(blankDependant())}>
              <UserPlus />
              Add family member
            </Button>
          )}
          {canUnderwrite && (leaving === null ? (
            <Button size="sm" variant="ghost" onClick={() => setLeaving(todayIso())}>
              <UserMinus />
              Member leaves
            </Button>
          ) : (
            <>
              <FormField label="Leaves on (covered to that month's end)">
                <DatePicker value={leaving || null} onChange={(iso) => setLeaving(iso ?? '')} />
              </FormField>
              <Button size="sm" variant="primary" disabled={!leaving}
                onClick={async () => {
                  if (await act(() => groupFuneralMemberLeaves(policyNumber, family.policyMemberId, leaving))) setLeaving(null);
                }}>
                Confirm leaving
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setLeaving(null)}>Cancel</Button>
            </>
          ))}
        </div>
      )}
    </div>
  );
}
