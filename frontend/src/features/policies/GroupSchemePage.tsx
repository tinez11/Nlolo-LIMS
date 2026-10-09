import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, Upload, UserPlus } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch, Controller } from 'react-hook-form';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { DEFAULT_MEMBER_PAGE_SIZE } from '@/api/policies';
import type { BenefitBasis, GroupSchemeView, MemberStatus, PolicyMemberView } from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { InlineError } from '@/components/InlineError';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { PartyPicker } from '@/components/PartyPicker';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock, TableSkeleton } from '@/components/states';
import { FormField } from '@/components/FormField';
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
import { exitSummary } from './memberStanding';
import { GroupFuneralFamiliesPanel } from './GroupFuneralFamiliesPanel';
import { listGroupFuneralFamilies } from '@/api/groupFuneral';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';
import { FilterChip } from '@/components/FilterChip';
import { Input, Select } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

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
  // Searching the roll by member name. A schedule can hold hundreds of lives, so
  // "is this person covered" is not a question anyone can answer by paging -- and the
  // filter is server-side, with its own total, because filtering one page of 500 would
  // report the wrong count under the right-looking rows.
  const q = params.get('q') ?? '';
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
      ...(q ? { q } : {}),
      page,
      pageSize: DEFAULT_MEMBER_PAGE_SIZE,
    });
  }, [policyNumber, loadMembers, status, q, page]);

  function update(next: { status?: MemberStatus | undefined; q?: string; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      // A new filter invalidates the current page offset.
      merged.delete('page');
    }
    if ('q' in next) {
      if (next.q) merged.set('q', next.q);
      else merged.delete('q');
      // Same reason: page 3 of the previous result is an empty table for a search
      // that actually matched.
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const data = scheme.data;
  // A group funeral scheme's lives (audit 2026-10-07): one member can be eight lives, so "Members" alone
  // understated who is covered. Reloaded whenever the member count moves.
  const funeralScheme = data?.benefitBasis === 'FUNERAL_PLAN';
  const [funeralLives, setFuneralLives] = useState<number | null>(null);
  useEffect(() => {
    if (!funeralScheme || !policyNumber) return undefined;
    let live = true;
    listGroupFuneralFamilies(policyNumber).then(
      (families) => {
        if (live) setFuneralLives(families.filter((f) => f.status === 'ACTIVE')
          .reduce((n, f) => n + f.lives.filter((l) => l.status === 'ACTIVE').length, 0));
      },
      () => undefined,
    );
    return () => { live = false; };
  }, [funeralScheme, policyNumber, data?.activeMemberCount, data?.totalCovered?.amount]);

  if (isInitialLoad(scheme)) {
    return <LoadingBlock label={`Loading ${policyNumber}`} />;
  }

  if (scheme.data === null && scheme.status === 'error' && scheme.error) {
    return (
      <>
        {/* The bar renders on the error path too, so a record that fails to load keeps its
            heading and its way out instead of leaving a bare panel. */}
        <PageHeader breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]} title="Scheme" />
        <div className="px-6 pt-6">
          <ErrorPanel error={scheme.error} onRetry={() => void loadScheme(policyNumber)} />
        </div>
      </>
    );
  }

  // Counts only, per StatCards' own rule: the platform has no analytics endpoint,
  // and the total covered is money rather than a count, so it belongs in the
  // summary panel beside the basis that produced it.
  const stats: Stat[] = funeralScheme ? [
    {
      label: 'Members',
      value: data?.activeMemberCount ?? null,
      pending: isInitialLoad(scheme),
      hint: 'what the bill counts',
    },
    {
      label: 'Lives',
      value: funeralLives,
      pending: funeralLives === null,
      hint: 'members and their families',
    },
  ] : [
    {
      label: 'Members',
      value: data?.activeMemberCount ?? null,
      pending: isInitialLoad(scheme),
      hint: 'people currently covered',
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
      <PageHeader
        breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
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
            {/* A credit-life scheme gets a LINK here, not the add-member form, and the form is
                not merely inappropriate on it -- it cannot work. It collects a party, a grade and
                a salary; a credit-life member is a loan, needs terms this form has no fields for,
                and `chk_policy_member_loan_complete` refuses the row without them. Offering it
                was the more expensive half of a real confusion: somebody on the roll looking for
                the CSV upload found one primary button, and it was the wrong one. */}
            {data?.benefitBasis === 'FUNERAL_PLAN' ? null : data?.benefitBasis === 'AMORTISING_LOAN' ? (
              <Button asChild size="sm" variant="primary">
                <Link to={`/staff/credit-life-schemes/${encodeURIComponent(policyNumber)}`}>
                  <Upload />
                  Upload a file
                </Link>
              </Button>
            ) : (
              <Button size="sm" variant="primary" onClick={() => setAddOpen(true)}>
                <UserPlus />
                Add member
              </Button>
            )}
          </>
        }
      />

      <StatCards stats={stats} />

      {/* The add-member panel leads but takes no `emphasis`: this is the one screen
          that keeps a real stat row, and a 1rem panel heading beside a 1.5rem stat
          figure and a 1.25rem page title would be a third size above body -- the
          Two-Peaks Rule, broken by the fix for something else. */}
      <DetailLayout record={renderRecord()}>
        {addOpen && data && (
          <Panel title="Add a member" subtitle="Values them against the scheme and tests the free cover limit">
            <AddMemberForm scheme={data} onDone={() => setAddOpen(false)} />
          </Panel>
        )}

        {/*
          `Panel`, not a section hand-styled to look like one. This reimplemented the frame
          exactly -- same border, same ruled header, same `text-sm font-semibold` heading -- so
          it looked right and inherited nothing: not the scroll margin that makes a jumped-to
          section land below the sticky bars, not `emphasis`, and not any later change to what a
          panel is.

          The toolbar moves INSIDE the panel as a ruled strip, which is what the chart of
          accounts already does and says why: "the toolbar belongs to the register it drives".
          It was in the header beside the title, which made the heading share a row with seven
          controls and wrap before any of them did.
        */}
        {/* A group funeral scheme (2026-10-07): families under their main members, and the acts on them. */}
        {data?.benefitBasis === 'FUNERAL_PLAN' ? (
          <GroupFuneralFamiliesPanel policyNumber={policyNumber} onChanged={() => void loadScheme(policyNumber)} />
        ) : (
        <Panel
          title="Members"
          subtitle={
            data?.benefitBasis === 'AMORTISING_LOAN'
              ? 'Each row shows the cover this borrower was enrolled at. A claim pays what they still owed on the day.'
              : 'Each row shows the benefit in force for that person today.'
          }
        >
          {/* The toolbar, as a ruled strip below the panel heading. The two sentences the
              subtitle now carries were written here for a reason worth keeping: an employer
              member's stored cover IS their cover today, while a credit-life member's is
              their cover on the day they were enrolled -- the amount insured is the loan
              balance, it falls every month, and the declining figure is recomputed at the
              date of event when a claim is registered. It is never stored, because a row per
              repayment would be tens of thousands of rows, and saying "today" over a figure
              that is cover at inception would overstate every borrower who has repaid. */}
          <div className="flex flex-wrap items-center gap-1 gap-y-2 border-b border-border px-4 py-2.5">
              <FilterChip
                label="All"
                active={status === undefined}
                onClick={() => update({ status: undefined })}
              />
              <FilterChip
                label="Active"
                active={status === 'ACTIVE'}
                onClick={() => update({ status: 'ACTIVE' })}
              />
              {/* An exited member is never deleted: a claim can arrive after
                  somebody leaves the employer, so "were they covered on the date
                  of event" has to stay answerable. */}
              <FilterChip
                label="Left"
                active={status === 'EXITED'}
                onClick={() => update({ status: 'EXITED' })}
              />
              {/* Searching a roll of hundreds is the only way to answer "is this
                  person covered" without paging it by eye. Beside the status chips
                  because the two compose -- "left, called Juma" is a real question a
                  claim assessor asks. */}
              <form className="ml-auto"
                onSubmit={(e) => {
                  e.preventDefault();
                  const value = new FormData(e.currentTarget).get('q');
                  update({ q: typeof value === 'string' ? value.trim() : '' });
                }}
              >
                <Input
                  name="q"
                  defaultValue={q}
                  placeholder="Search by member name"
                  aria-label="Search members by name"
                  inputSize="sm"
                  className="w-52 px-2.5 text-sm"
                />
              </form>
          </div>
          {renderMembers()}
        </Panel>
        )}
      </DetailLayout>
    </>
  );

  function renderRecord() {
    return (
      <>
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

        {/* THE WAY BACK, and it was missing. The credit-life page links here -- "the member
            roll" -- and this page linked only to the policy record, so following that link was a
            one-way door: the roll is where you check whether a named borrower is covered, and the
            monthly files are where every act on this product happens. Somebody who came here to
            look someone up and then wanted to send the next file found a page with no upload on
            it and nothing saying where the upload was.

            Only on a credit-life scheme, because an employer scheme has no monthly files: its
            members are added one at a time on this very page. */}
        {data?.benefitBasis === 'AMORTISING_LOAN' && (
          <Panel title="Monthly files">
            <div className="px-4 pb-4 pt-1">
              <p className="text-xs text-muted-foreground">
                Borrowers join and loans end by file, not one at a time. Uploading the lender&rsquo;s
                schedule, reading what was refused and accepting it all happen there.
              </p>
              <Button asChild size="sm" variant="ghost" className="mt-2 -ml-2">
                <Link to={`/staff/credit-life-schemes/${encodeURIComponent(policyNumber)}`}>
                  Open the monthly files
                </Link>
              </Button>
            </div>
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
      </>
    );
  }

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
          // A search is now the most likely reason this table is empty, and "No members"
          // in front of a 500-life scheme because a name did not match would be flatly
          // untrue -- the roll is not empty, this search of it is.
          title={
            q
              ? `No member matching "${q}"`
              : status === 'EXITED'
                ? 'Nobody has left this scheme'
                : 'No members'
          }
          description={
            q
              ? 'The search matches a member’s name. Someone covered under a different scheme will not appear here.'
              : status === 'EXITED'
                ? 'Members who leave stay on the roll here, because a claim can arrive after they go.'
                : 'A scheme is issued with its opening schedule, so this should not be empty.'
          }
          {...(status || q
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ status: undefined, q: '' })}>
                    Show all
                  </Button>
                ),
              }
            : {})}
        />
      );
    }

    return (
      <>
        <DataTable
          columns={memberColumnsFor(data?.benefitBasis)}
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

/**
 * The roll's columns depend on what kind of scheme it is, because two of them are dead weight on
 * the other kind.
 *
 * A credit-life member has no grade and no salary -- those are how an EMPLOYER scheme decides
 * what a life is worth, and this product decides it from a loan schedule instead. An employer
 * member has no member reference, because the insurer only mints one for a borrower a lender will
 * quote back on a later file.
 *
 * Rendering both on both left seven columns where six fit, one of them permanently empty. That is
 * not a tidiness point: a roll of several hundred borrowers is read by scanning, and a column of
 * em dashes costs width that the names and references need.
 */
const memberColumnsFor = (basis: BenefitBasis | undefined): Column<PolicyMemberView>[] => {
  const creditLife = basis === 'AMORTISING_LOAN';
  return baseMemberColumns.filter((c) =>
    c.key === 'memberReference' || c.key === 'arrivedOn'
      ? creditLife
      : c.key === 'gradeOrSalary'
        ? !creditLife
        : true,
  );
};

const baseMemberColumns: Column<PolicyMemberView>[] = [
  {
    key: 'member',
    header: 'Member',
    // A FREEFORM member has no party row to look a name up from, and this column used to render
    // an em dash for one -- which meant a credit-life scheme's roll named nobody on it, on the
    // one screen whose whole purpose is answering "is this person covered". The name is on the
    // member row itself; it was simply not on the wire until PolicyMemberView's schema grew the
    // field. A party member still links to their record, because there is one to link to.
    render: (m) =>
      m.memberPartyId ? (
        <Link to={`/staff/parties/${m.memberPartyId}`} className="font-medium underline">
          <PartyName partyId={m.memberPartyId} />
        </Link>
      ) : m.memberName ? (
        <span className="font-medium">{m.memberName}</span>
      ) : (
        NO_VALUE
      ),
  },
  {
    // The reference the INSURER minted, and the only handle the lender has on this borrower:
    // they quote it back on every later file, and an exits file names who is leaving by it.
    // Rendered only when present, so an employer scheme's roll does not grow an empty column.
    key: 'memberReference',
    header: 'Reference',
    render: (m) =>
      m.memberReference ? (
        // nowrap: a reference is one token and breaks after every hyphen if it is allowed to,
        // which turned CL-AF8D3D3C-000002 into three stacked lines and took the width away from
        // the names beside it. It is ~18 characters at this size; it always fits on one.
        <span className="whitespace-nowrap font-mono text-xs">{m.memberReference}</span>
      ) : (
        <span className="text-subtle-foreground">{NO_VALUE}</span>
      ),
  },
  {
    /*
     * WHICH FILE PUT THIS BORROWER ON COVER.
     *
     * The column exists because of a question the roll could not answer: "three names, and I
     * uploaded two." The third had been typed into the set-up form, and nothing here told it
     * apart from the two that arrived on a file. Schemes no longer open with a typed borrower,
     * but the ones already created did, and the operational form of the question outlives the
     * fix anyway -- in a dispute a lender asks which file you covered somebody on.
     *
     * A member with no file is not missing data. They were on the opening schedule, so the cell
     * says so rather than showing an em dash that reads as a gap.
     */
    key: 'arrivedOn',
    header: 'Came in on',
    secondary: true,
    render: (m) =>
      m.arrivedOnFileName ? (
        <span className="block max-w-[14rem] truncate" title={m.arrivedOnFileName}>
          {m.arrivedOnFileName}
        </span>
      ) : (
        <span className="text-subtle-foreground">Opening schedule</span>
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
          <span className="whitespace-nowrap font-medium">{formatMoney(m.covered)}</span>
          {m.benefit && m.benefit.amount !== m.covered.amount && (
            <span className="block text-xs text-subtle-foreground">
              of {formatMoney(m.benefit)}
            </span>
          )}
          {/* A death is reported and not yet paid: still on cover, but this figure is what the
              claim will pay, not live cover on a loan still running. */}
          {m.openDeathClaimId && (
            <span className="block text-xs text-status-warning-fg">
              Claim pending — cover ends on settlement
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
    /*
      Said in full, because ACTIVE / EXITED alone hid the two things that matter most on a
      credit-life book. A borrower whose death was reported and not yet paid read "ACTIVE" like
      any live loan -- it now names the claim and links to it. And EXITED said nothing of why:
      a repaid loan (a refund owed) and a paid death claim looked the same.
    */
    render: (m) =>
      m.openDeathClaimId ? (
        <Link
          to={`/staff/claims/${m.openDeathClaimId}`}
          className="inline-flex flex-col gap-0.5 hover:underline"
        >
          <StatusBadge kind="member" value="DEATH_CLAIM_IN_PROGRESS" />
          <span className="text-xs text-muted-foreground">View the claim</span>
        </Link>
      ) : m.status === 'EXITED' ? (
        <span className="inline-flex flex-col gap-0.5">
          <StatusBadge kind="member" value={m.status} />
          {exitSummary(m) && (
            <span className="text-xs text-muted-foreground">{exitSummary(m)}</span>
          )}
        </span>
      ) : (
        <StatusBadge kind="member" value={m.status} />
      ),
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
    case 'AMORTISING_LOAN':
      // Credit life. There is no figure to quote for the scheme as a whole, because every
      // borrower is worth a different declining number on a different day -- which is exactly
      // what this line has to say. Until it was added, the fourth basis fell through to the
      // default and a live credit-life scheme reported its benefit basis as an em dash.
      return 'Outstanding loan balance';
    case 'FUNERAL_PLAN':
      return 'Funeral plan — each life covered for its role';
    default:
      return NO_VALUE;
  }
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
    ...VALIDATE_ON_TOUCH,
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
        <FormField label="Person" error={errors.memberPartyId?.message}>
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
        </FormField>

        {scheme.benefitBasis === 'SALARY_MULTIPLE' && (
          <FormField label="Annual salary" error={errors.salaryAmount?.message}>
            <Input
              inputMode="decimal"
              inputSize="sm"
              placeholder="20000000.00"
              {...register('salaryAmount')}
            />
          </FormField>
        )}

        {scheme.benefitBasis === 'GRADED' && (
          <FormField label="Grade" error={errors.gradeCode?.message}>
            <Select inputSize="sm" {...register('gradeCode')}>
              <option value="">Choose a grade…</option>
              {gradeCodes.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </Select>
          </FormField>
        )}

        <FormField label="Cover starts" error={errors.joinedOn?.message}>
          <Input type="date" inputSize="sm" {...register('joinedOn')} />
          <p className="mt-1 text-xs text-subtle-foreground">
            Leave blank for today. Backdating is fine; a future date is not.
          </p>
        </FormField>
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
        <InlineError error={adding.error} />
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" pending={adding.status === 'loading'}>
          <Plus />
          Add member
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

