import type { ReactNode } from 'react';
import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Field } from '@/components/Field';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { formatDate, formatInstant } from '@/lib/dates';
import { partyTypeLabel } from '@/lib/partyType';
import type { IdentityDocumentView, PartyDetailView, PartyDocumentView } from '@/api/types';
import type { Resource } from '@/store/createResourceSlice';

/**
 * Who the client is, in full, on one screen (2026-10-22, the user): the landing tab for staff and for an agent viewing
 * their own client. It replaces the pinned rail, which squeezed these facts into a column beside every tab.
 *
 * KYC leads as a band, not a card among cards: it is the one fact here that gates what the client may do, and staff
 * act on it from the next tab.
 */
export function ClientOverview({
  party,
  partyId,
  isStaff,
  documents,
}: {
  party: PartyDetailView;
  partyId: string;
  isStaff: boolean;
  documents: Resource<PartyDocumentView[]>;
}) {
  const isOrganisation = party.partyType === 'CORPORATE' || party.partyType === 'GROUP';
  const isIndividual = party.partyType === 'INDIVIDUAL';

  return (
    <div className="space-y-5">
      <KycBand party={party} isStaff={isStaff} documents={documents} />

      <div className="grid items-start gap-5 lg:grid-cols-2 2xl:grid-cols-3">
        <Section title="Identity">
          <Field label="Type" value={partyTypeLabel(party.partyType)} />
          {/* Only where it applies to this kind of party, or where one is recorded anyway: a company's "Date of
              birth —" said something false, that a date exists and is unknown. */}
          {(!isOrganisation || party.dateOfBirth) && (
            <Field label="Date of birth" value={formatDate(party.dateOfBirth) || '—'} />
          )}
          {(isOrganisation || party.registrationNumber) && (
            <Field label="Registration no." value={party.registrationNumber ?? '—'} />
          )}
          {(!isOrganisation || party.nationality) && <Field label="Nationality" value={party.nationality ?? '—'} />}
          {party.identityDocument?.type && (
            <Field
              label={ID_TYPE_LABELS[party.identityDocument.type]}
              value={<span className="font-mono select-all">{party.identityDocument.number ?? '—'}</span>}
            />
          )}
          {/* Selectable, because the only thing anybody does with this number is copy it into another system. */}
          <Field
            label="Client reference"
            value={party.clientReference ? <span className="font-mono select-all">{party.clientReference}</span> : '—'}
          />
        </Section>

        <Section title="Contact">
          <Field label="Phone" value={party.phoneNumber ?? '—'} />
          <Field label="Email" value={party.email ?? '—'} />
          {isIndividual && hasAddress(party) && (
            <>
              <Field label="Street or plot" value={party.address?.line ?? '—'} />
              <Field label="Ward" value={party.address?.ward ?? '—'} />
              <Field label="District" value={party.address?.district ?? '—'} />
              <Field label="Region" value={party.address?.region ?? '—'} />
              <Field label="Postal code" value={party.address?.postalCode ?? '—'} />
            </>
          )}
        </Section>

        {/* Individuals only, and only when something is on record: a corporate has none of this, and a panel of em
            dashes would read as data the platform lost rather than never asked for. */}
        {isIndividual && hasPersonRecord(party) && (
          <Section title="Underwriting facts" subtitle="What a premium is rated on.">
            <Field label="Sex" value={party.sex ? SEX_LABELS[party.sex] : '—'} />
            <Field
              label="Smoker status"
              value={party.smokerStatus ? SMOKER_LABELS[party.smokerStatus] : '—'}
              // UNKNOWN is a recorded answer a product may price; absent means nobody asked.
              {...(party.smokerStatus ? {} : { note: 'Never asked — not the same as declined.' })}
            />
            <Field label="Occupation" value={party.occupation ?? '—'} />
            <Field
              label="Occupation class"
              value={party.occupationClass ?? '—'}
              {...(party.occupationClass ? {} : { note: 'Unclassified — no occupation loading can apply.' })}
            />
            <Field label="Employer" value={party.employerName ?? '—'} />
          </Section>
        )}

        <Section title="Record">
          <Field label="Registered" value={formatInstant(party.createdAt) || '—'} />
          {/* One row, never a login id: `createdBy` is a Keycloak subject nothing here can name. An agent's
              registration links to the agent, who is also who commission accrues to. */}
          {party.registeredByPartyId ? (
            <Field
              label="Registered by"
              value={
                <span>
                  {isStaff ? (
                    <Link className="hover:underline" to={`/staff/parties/${party.registeredByPartyId}`}>
                      {party.createdByName ?? <PartyName partyId={party.registeredByPartyId} />}
                    </Link>
                  ) : (
                    party.createdByName ?? 'You'
                  )}
                  <span className="text-muted-foreground"> · agent</span>
                </span>
              }
              note="Commission on this client’s policies accrues to this agent."
            />
          ) : (
            <Field
              label="Registered by"
              value={party.createdByName ?? 'Name not recorded'}
              note="No agent introduced this client, so their business is direct."
            />
          )}
          <Field label="Client id" value={<span className="font-mono text-xs select-all">{partyId}</span>} />
        </Section>
      </div>
    </div>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <Panel title={title} {...(subtitle ? { subtitle } : {})}>
      <dl className="px-4 pb-2">{children}</dl>
    </Panel>
  );
}

const KYC_LINE: Record<string, string> = {
  VERIFIED: 'Identity verified. This client may hold a policy.',
  REJECTED: 'Identity rejected. This client cannot be taken on as a policyholder.',
  PENDING: 'Waiting for a decision on this client’s identity.',
};

/**
 * KYC at a glance: the status, the sentence it means, when it was decided, what is on file, and the way to it. Staff
 * go to review it; an agent goes to send documents.
 */
function KycBand({
  party,
  isStaff,
  documents,
}: {
  party: PartyDetailView;
  isStaff: boolean;
  documents: Resource<PartyDocumentView[]>;
}) {
  const filed = documents.data ?? [];
  const evidence = filed.filter((d) => d.documentType === 'KYC_EVIDENCE').length;
  const forms = filed.filter((d) => d.documentType === 'SIGNED_FORM').length;
  const status = party.kycStatus ?? 'PENDING';
  const onFile = documents.data === null
    ? null
    : [
        evidence === 0 ? 'No identity evidence' : `${evidence} identity document${evidence === 1 ? '' : 's'}`,
        ...(forms > 0 ? [`${forms} signed form${forms === 1 ? '' : 's'}`] : []),
      ].join(' · ');

  return (
    <section
      aria-label="KYC"
      className="flex flex-wrap items-center gap-x-6 gap-y-3 rounded-xl bg-surface px-5 py-4 shadow-card"
    >
      <StatusBadge kind="kyc" value={status} />
      <div className="min-w-60 flex-1">
        <p className="text-sm font-medium">{KYC_LINE[status] ?? KYC_LINE.PENDING}</p>
        <p className="text-sm text-muted-foreground">
          {[party.kycVerifiedAt ? `Decided ${formatInstant(party.kycVerifiedAt)}` : null, onFile]
            .filter(Boolean)
            .join(' · ')}
        </p>
      </div>
      <Button asChild variant={status === 'PENDING' && isStaff ? 'primary' : 'outline'}>
        <Link to="?tab=kyc">
          {isStaff ? (status === 'PENDING' ? 'Review KYC' : 'KYC & documents') : 'Send documents'}
          <ArrowRight />
        </Link>
      </Button>
    </section>
  );
}

const ID_TYPE_LABELS: Record<NonNullable<IdentityDocumentView['type']>, string> = {
  NATIONAL_ID: 'National ID',
  PASSPORT: 'Passport',
  DRIVING_LICENCE: 'Driving licence',
  VOTER_ID: 'Voter ID',
};

const SEX_LABELS: Record<NonNullable<PartyDetailView['sex']>, string> = {
  FEMALE: 'Female',
  MALE: 'Male',
};

const SMOKER_LABELS: Record<NonNullable<PartyDetailView['smokerStatus']>, string> = {
  SMOKER: 'Smoker',
  NON_SMOKER: 'Non-smoker',
  // Not "Unknown": the question was put and the applicant declined -- a recorded answer a product may price.
  UNKNOWN: 'Declined to say',
};

/** Whether any person fact is on record, so the section is not a column of em dashes. */
function hasPersonRecord(party: PartyDetailView): boolean {
  return Boolean(party.sex ?? party.smokerStatus ?? party.occupation ?? party.occupationClass ?? party.employerName);
}

function hasAddress(party: PartyDetailView): boolean {
  const address = party.address;
  if (!address) return false;
  return Boolean(address.line ?? address.ward ?? address.district ?? address.region ?? address.postalCode);
}
