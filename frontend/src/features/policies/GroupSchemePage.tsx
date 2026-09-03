import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Plus, UserPlus } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch, Controller } from 'react-hook-form';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { DEFAULT_MEMBER_PAGE_SIZE } from '@/api/policies';
import type { GroupSchemeView, MemberStatus, PolicyMemberView } from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { PartyPicker } from '@/components/PartyPicker';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatDate, formatMonths } from '@/lib/dates';
import { formatMoney, NO_VALUE } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectAddingMember,
  selectMembers,
  selectScheme,
  usePolicyStore,
} from '@/store/policyStore';
import {
  blankMemberForm,
  memberFormSchema,
  toApiRequest,
  type MemberFormValues,
} from './addMemberForm';
import { previewBenefit, type SchemeBasis } from './groupBenefitPreview';

/**
 * A group scheme: one master policy, many insured lives.
 *
 * Its own screen rather than a panel on the policy page, because the two answer
 * different questions. `GET /policies/{n}` says "one contract, 500 lives, sum
 * assured X" — true, and useless to somebody administering the schedule. The link
 * between them runs both ways, so neither is a dead end.
 *
 * The member table is one of only four genuinely paged endpoints on the platform,
 * so it gets a real pager; the filter and page live in the URL, which is what
 * makes a filtered view shareable and the back button correct.
 */
export function GroupSchemePage() {
  const { policyNumber = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const [addOpen, setAddOpen] = useState(false);

  const statusParam = params.get('status');
  const status: MemberStatus | undefined =
    statusParam === 'ACTIVE' || statusParam === 'EXITED' ? statusParam : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const scheme = usePolicyStore(selectScheme(policyNumber));
  const members = usePolicyStore(selectMembers(policyNumber));
  const loadScheme = usePolicyStore((s) => s.loadScheme);
  const loadMembers = usePolicyStore((s) => s.loadMembers);

  useEffect(() => {
    if (!policyNumber) return;
    void loadScheme(policyNumber);
  }, [policyNumber, loadScheme]);

  useEffect(() => {
    if (!policyNumber) return;
    void loadMembers(policyNumber, {
      ...(status ? { status } : {}),
      page,
      pageSize: DEFAULT_MEMBER_PAGE_SIZE,
    });
  }, [policyNumber, loadMembers, status, page]);

  function update(next: { status?: MemberStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      // A new filter invalidates the current page offset.
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const data = scheme.data;

  if (isInitialLoad(scheme)) {
    return <LoadingBlock label={`Loading ${policyNumber}`} />;
  }

  if (scheme.data === null && scheme.status === 'error' && scheme.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={scheme.error} onRetry={() => void loadScheme(policyNumber)} />
      </div>
    );
  }

  // Counts only, per StatCards' own rule: the platform has no analytics endpoint,
  // and the total covered is money rather than a count, so it belongs in the
  // summary panel beside the basis that produced it.
  const stats: Stat[] = [
    {
      label: 'Members',
      value: data?.activeMemberCount ?? null,
      pending: isInitialLoad(scheme),
      hint: 'lives currently covered',
    },
    {
      label: 'Awaiting evidence',
      value: data?.membersRequiringEvidence ?? null,
      pending: isInitialLoad(scheme),
      hint:
        (data?.membersRequiringEvidence ?? 0) > 0
          ? 'over the free cover limit'
          : 'nobody is over the limit',
    },
  ];

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title={policyNumber}
        description={
          data?.policyholderPartyId ? (
            <>
              Group scheme held by{' '}
              <Link to={`/staff/parties/${data.policyholderPartyId}`} className="underline">
                <PartyName partyId={data.policyholderPartyId} />
              </Link>
            </>
          ) : (
            'Group scheme'
          )
        }
        actions={
          <>
            {data?.status && <StatusBadge kind="policy" value={data.status} />}
            <Button size="sm" variant="primary" onClick={() => setAddOpen(true)}>
              <UserPlus />
              Add member
            </Button>
          </>
        }
      />

      <StatCards stats={stats} />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          {addOpen && data && (
            <Panel title="Add a member" subtitle="Values them against the scheme and tests the free cover limit">
              <AddMemberForm scheme={data} onDone={() => setAddOpen(false)} />
            </Panel>
          )}

          <section className="rounded-lg border border-border bg-surface">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold">Members</h2>
                <p className="text-xs text-muted-foreground">
                  Each row shows the benefit in force for that person today.
                </p>
              </div>
              <div className="flex items-center gap-1">
                <FilterChip active={status === undefined} onClick={() => update({ status: undefined })}>
                  All
                </FilterChip>
                <FilterChip active={status === 'ACTIVE'} onClick={() => update({ status: 'ACTIVE' })}>
                  Active
                </FilterChip>
                {/* An exited member is never deleted: a claim can arrive after
                    somebody leaves the employer, so "were they covered on the date
                    of event" has to stay answerable. */}
                <FilterChip active={status === 'EXITED'} onClick={() => update({ status: 'EXITED' })}>
                  Left
                </FilterChip>
              </div>
            </div>
            {renderMembers()}
          </section>
        </div>

        <div className="space-y-5">
          <Panel title="Scheme">
            {data && (
              <dl className="px-4 pb-2">
                <Field
                  label="Total sum insured"
                  value={formatMoney(data.totalCovered)}
                  emphasis
                  note="The total of what every active member is covered for — not a figure anyone types."
                />
                <Field label="Benefit basis" value={describeBasis(data)} />
                <Field
                  label="Free cover limit"
                  value={data.fcl ? formatMoney(data.fcl) : 'No limit'}
                  // Absent is not zero, and the difference is the whole rule: no
                  // limit means nobody needs evidence, a limit of zero would mean
                  // everybody does. Said in words so the two can never be read alike.
                  note={
                    data.fcl
                      ? 'Cover above this needs medical evidence.'
                      : 'Every member is covered in full with no evidence.'
                  }
                />
                <Field label="Risk commenced" value={formatDate(data.commencementDate)} />
                <Field
                  label="Term"
                  value={data.policyTermMonths ? formatMonths(data.policyTermMonths) : 'Annually renewable'}
                />
                <Field
                  label="Policyholder"
                  value={
                    data.policyholderPartyId ? (
                      <Link to={`/staff/parties/${data.policyholderPartyId}`} className="underline">
                        <PartyName partyId={data.policyholderPartyId} />
                      </Link>
                    ) : (
                      NO_VALUE
                    )
                  }
                  note="The employer owns the contract. The lives are the schedule, not this person."
                />
              </dl>
            )}
          </Panel>

          {data?.benefitBasis === 'GRADED' && (
            <Panel title="Grades" subtitle="What each staff category is worth">
              {data.grades && data.grades.length > 0 ? (
                <dl className="px-4 pb-2">
                  {data.grades.map((grade) => (
                    <Field
                      key={grade.gradeCode}
                      label={grade.gradeCode ?? NO_VALUE}
                      value={formatMoney(grade.benefit)}
                    />
                  ))}
                </dl>
              ) : (
                <p className="px-4 pb-4 text-xs text-muted-foreground">
                  This scheme is graded but carries no grade table.
                </p>
              )}
            </Panel>
          )}

          <Panel title="Contract">
            <div className="px-4 pb-4 pt-1">
              <p className="text-xs text-muted-foreground">
                Premium, invoices, loans and lifecycle actions live on the policy record.
              </p>
              <Button asChild size="sm" variant="ghost" className="mt-2 -ml-2">
                <Link to={`/staff/policies/${encodeURIComponent(policyNumber)}`}>
                  Open the policy
                </Link>
              </Button>
            </div>
          </Panel>
        </div>
      </div>
    </>
  );

  function renderMembers() {
    if (isInitialLoad(members)) return <TableSkeleton columns={6} />;

    if (members.status === 'error' && members.error && members.data === null) {
      return (
        <ErrorPanel
          error={members.error}
          onRetry={() => void loadMembers(policyNumber, { ...(status ? { status } : {}), page })}
        />
      );
    }

    const rows = members.data?.items ?? [];
    if (rows.length === 0 && members.status === 'success') {
      return (
        <EmptyState
          title={status === 'EXITED' ? 'Nobody has left this scheme' : 'No members'}
          description={
            status === 'EXITED'
              ? 'Members who leave stay on the roll here, because a claim can arrive after they go.'
              : 'A scheme is issued with its opening schedule, so this should not be empty.'
          }
          {...(status ? { action: <Button size="sm" onClick={() => update({ status: undefined })}>Show all</Button> } : {})}
        />
      );
    }

    return (
      <>
        <DataTable
          columns={memberColumns}
          rows={rows}
          rowKey={(m) => m.policyMemberId ?? `${m.memberPartyId}`}
          caption="Members of this group scheme"
        />
        {members.data && (
          <Pager
            page={members.data.page}
            busy={members.status === 'loading'}
            onPageChange={(next) => update({ page: next })}
          />
        )}
      </>
    );
  }
}

const memberColumns: Column<PolicyMemberView>[] = [
  {
    key: 'member',
    header: 'Member',
    render: (m) =>
      m.memberPartyId ? (
        <Link to={`/staff/parties/${m.memberPartyId}`} className="font-medium underline">
          <PartyName partyId={m.memberPartyId} />
        </Link>
      ) : (
        NO_VALUE
      ),
  },
  {
    key: 'covered',
    header: 'Covered for',
    align: 'right',
    // The figure a claim pays, so it leads. `benefit` sits underneath it only when
    // the two differ -- on most rows they are equal, and repeating the same number
    // twice would teach people to stop reading the column.
    render: (m) =>
      m.covered ? (
        <span>
          <span className="font-medium">{formatMoney(m.covered)}</span>
          {m.benefit && m.benefit.amount !== m.covered.amount && (
            <span className="block text-[11px] text-subtle-foreground">
              of {formatMoney(m.benefit)}
            </span>
          )}
        </span>
      ) : (
        // Null money is a member whose cover has not started as at today. A zero
        // here would read as "insured for nothing", which is a different fact.
        <span className="text-subtle-foreground">Not yet in force</span>
      ),
  },
  {
    key: 'underwriting',
    header: 'Underwriting',
    render: (m) => <StatusBadge kind="memberUnderwriting" value={m.underwritingStatus} />,
  },
  {
    key: 'gradeOrSalary',
    header: 'Grade / salary',
    align: 'right',
    secondary: true,
    render: (m) =>
      m.gradeCode ? (
        <span>{m.gradeCode}</span>
      ) : m.salary ? (
        <span className="text-muted-foreground">{formatMoney(m.salary)}</span>
      ) : (
        <span className="text-subtle-foreground">{NO_VALUE}</span>
      ),
  },
  {
    key: 'joinedOn',
    header: 'Joined',
    secondary: true,
    render: (m) => <span className="text-muted-foreground">{formatDate(m.joinedOn)}</span>,
  },
  {
    key: 'status',
    header: 'Status',
    secondary: true,
    render: (m) => <StatusBadge kind="member" value={m.status} />,
  },
];

/** A one-line reading of how this scheme values anybody. */
function describeBasis(scheme: GroupSchemeView): string {
  switch (scheme.benefitBasis) {
    case 'FLAT':
      return `Flat — ${formatMoney(scheme.flatBenefit)} each`;
    case 'SALARY_MULTIPLE':
      return scheme.salaryMultiple ? `${scheme.salaryMultiple}× salary` : 'Salary multiple';
    case 'GRADED':
      return `Graded — ${scheme.grades?.length ?? 0} bands`;
    default:
      return NO_VALUE;
  }
}

function BackLink() {
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2">
      <Link to="/staff/policies">
        <ArrowLeft />
        All policies
      </Link>
    </Button>
  );
}

function FilterChip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-full border px-2.5 py-1 text-xs transition-colors',
        active
          ? 'border-border-strong bg-selected font-medium'
          : 'border-border text-muted-foreground hover:border-border-strong',
      )}
    >
      {children}
    </button>
  );
}

/**
 * Adding one life.
 *
 * The form renders only the field this scheme's basis calls for — a salary box on
 * a flat scheme is an invitation to fill in something that will be rejected. The
 * schema still validates the rejected cases, because the rule belongs to the
 * contract rather than to which inputs happen to be on screen.
 *
 * The preview underneath is the reason this is a form and not a single picker: on
 * a scheme with a free cover limit, whether this person needs medical evidence is
 * decided by what is typed here, and finding that out after saving is finding out
 * too late to ask them about it.
 */
function AddMemberForm({ scheme, onDone }: { scheme: GroupSchemeView; onDone: () => void }) {
  const policyNumber = scheme.policyNumber ?? '';
  const addSchemeMember = usePolicyStore((s) => s.addSchemeMember);
  const resetAddSchemeMember = usePolicyStore((s) => s.resetAddSchemeMember);
  const adding = usePolicyStore(selectAddingMember(policyNumber));

  const gradeCodes = useMemo(
    () => (scheme.grades ?? []).map((g) => g.gradeCode).filter((c): c is string => Boolean(c)),
    [scheme.grades],
  );

  // This resource is keyed by policy number and outlives the form's mount, so an
  // old failure would otherwise resurface the moment the form is reopened, before
  // the user has done anything wrong this time. Same fix as SuspendForm's.
  useEffect(() => {
    resetAddSchemeMember(policyNumber);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber]);

  const {
    control,
    register,
    handleSubmit,

    reset,
    formState: { errors },
  } = useForm<MemberFormValues>({
    resolver: zodResolver(
      memberFormSchema({
        benefitBasis: scheme.benefitBasis ?? 'FLAT',
        gradeCodes,
        commencementDate: scheme.commencementDate ?? null,
      }),
    ),
    defaultValues: blankMemberForm(),
  });

  // useWatch, not watch(): the subscribed form the React Compiler can reason
  // about, following PublishVersionForm. These two drive the live free-cover
  // preview below, so they genuinely have to re-render on every keystroke.
  const watchedSalary = useWatch({ control, name: 'salaryAmount' });
  const watchedGrade = useWatch({ control, name: 'gradeCode' });

  const basis: SchemeBasis = {
    benefitBasis: scheme.benefitBasis ?? 'FLAT',
    currency: scheme.totalCovered?.currencyCode ?? 'TZS',
    flatBenefitAmount: scheme.flatBenefit?.amount ?? null,
    salaryMultiple: scheme.salaryMultiple ?? null,
    fclAmount: scheme.fcl?.amount ?? null,
    gradeBenefits: Object.fromEntries(
      (scheme.grades ?? [])
        .filter((g) => g.gradeCode && g.benefit)
        .map((g) => [g.gradeCode as string, g.benefit?.amount as string]),
    ),
  };
  const preview = previewBenefit(basis, {
    salaryAmount: watchedSalary,
    gradeCode: watchedGrade,
  });

  async function onSubmit(values: MemberFormValues) {
    await addSchemeMember(policyNumber, toApiRequest(values));
    if (usePolicyStore.getState().addingMember[policyNumber]?.status === 'success') {
      reset(blankMemberForm());
      onDone();
    }
  }

  return (
    <form className="space-y-3 px-4 pb-4 pt-3" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block">
          <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Person</span>
          <Controller
            control={control}
            name="memberPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search employees by name…"
              />
            )}
          />
          <FieldError message={errors.memberPartyId?.message} />
        </label>

        {scheme.benefitBasis === 'SALARY_MULTIPLE' && (
          <label className="block">
            <span className="mb-1 block text-[11px] font-medium text-muted-foreground">
              Annual salary
            </span>
            <input
              inputMode="decimal"
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              placeholder="20000000.00"
              {...register('salaryAmount')}
            />
            <FieldError message={errors.salaryAmount?.message} />
          </label>
        )}

        {scheme.benefitBasis === 'GRADED' && (
          <label className="block">
            <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Grade</span>
            <select
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              {...register('gradeCode')}
            >
              <option value="">Choose a grade…</option>
              {gradeCodes.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
            <FieldError message={errors.gradeCode?.message} />
          </label>
        )}

        <label className="block">
          <span className="mb-1 block text-[11px] font-medium text-muted-foreground">
            Cover starts
          </span>
          <input
            type="date"
            className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
            {...register('joinedOn')}
          />
          <p className="mt-1 text-[11px] text-subtle-foreground">
            Leave blank for today. Backdating is fine; a future date is not.
          </p>
          <FieldError message={errors.joinedOn?.message} />
        </label>
      </div>

      {preview && (
        <div
          className={cn(
            'rounded-md border px-3 py-2 text-xs',
            preview.status === 'EVIDENCE_REQUIRED'
              ? 'border-status-pending-fg/30 bg-status-pending-bg text-status-pending-fg'
              : 'border-border bg-hover text-muted-foreground',
          )}
        >
          {preview.status === 'WITHIN_FCL' ? (
            <>
              Covered for <strong>{formatMoney(preview.covered)}</strong> from day one.
            </>
          ) : (
            <>
              Covered for <strong>{formatMoney(preview.covered)}</strong> immediately — the free
              cover limit. The remaining {formatMoney(preview.excess)} of their{' '}
              {formatMoney(preview.benefit)} benefit needs medical evidence before it applies.
            </>
          )}
        </div>
      )}

      {adding.status === 'error' && adding.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {adding.error.detail ?? adding.error.title}
        </p>
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" disabled={adding.status === 'loading'}>
          <Plus />
          {adding.status === 'loading' ? 'Adding…' : 'Add member'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function FieldError({ message }: { message: string | undefined }) {
  if (!message) return null;
  return <p className="mt-1 text-[11px] text-status-danger-fg">{message}</p>;
}

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}
