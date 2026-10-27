import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { grantRoles } from '@/test/access';
import { getParty, listKycDecisions, listPartyDocuments } from '@/api/party';
import type { PartyDetailView, PartyDocumentView } from '@/api/types';
import { usePartyStore } from '@/store/partyStore';
import { useClientRecordStore } from '@/store/clientRecord';
import { renderScreen } from '@/test/renderScreen';
import { PartyDetailPage } from './PartyDetailPage';

vi.mock('@/api/party');
vi.mock('@/api/policies');
vi.mock('@/api/claims');
vi.mock('@/api/underwriting');
vi.mock('@/api/distribution');

// A factory rather than the automock the others take: the viewer calls `.then()` on what this
// returns, and an automocked function hands back undefined.
vi.mock('@/api/documents', () => ({
  downloadStoredDocument: vi.fn(() => Promise.resolve(new Blob(['scan'], { type: 'image/png' }))),
}));

/**
 * Written BEFORE the party record is restructured into tabs, which is the order plan 4
 * established and this file exists to repeat: the tests that pass identically either side of a
 * restructure are what make it a restructure rather than a rewrite.
 *
 * At 1181 lines this is the largest screen on the platform, and the one the agreed proposal
 * named for tabs back on 2026-09-25 -- so it has the most to lose from a move made blind.
 */

const PARTY_ID = '11111111-1111-1111-1111-111111111111';

const RESOURCE = { status: 'success' as const, error: null, loadedAt: Date.now() };

function party(over: Partial<PartyDetailView> = {}): PartyDetailView {
  return {
    partyId: PARTY_ID,
    partyType: 'INDIVIDUAL',
    kycStatus: 'PENDING',
    displayName: 'Amina Owner',
    dateOfBirth: '1985-01-01',
    phoneNumber: '+255700000001',
    ...over,
  };
}

/**
 * Routed rather than mocking `useParams`. The page reads its id from the URL, so a test that
 * stubs the hook is testing a different component from the one that ships -- and a `vi.mock`
 * factory is hoisted above the constants it would need, which is how the first attempt at this
 * file failed before a single assertion ran.
 */
function renderAt(route: string, realm: 'staff' | 'agents' = 'staff') {
  const path = realm === 'staff' ? '/staff/parties/:partyId' : '/agents/clients/:partyId';
  return renderScreen(
    <Routes>
      <Route path={path} element={<PartyDetailPage realm={realm} />} />
    </Routes>,
    { route },
  );
}

/** Every register this record reads, empty. A test that wants one fills it in. */
function emptyRegisters() {
  const page = { page: { page: 0, pageSize: 20, totalElements: 0 }, items: [] };
  useClientRecordStore.setState({
    policies: { [PARTY_ID]: { ...RESOURCE, data: page } },
    claims: { [PARTY_ID]: { ...RESOURCE, data: page } },
    cases: { [PARTY_ID]: { ...RESOURCE, data: page } },
  } as never);
}

/**
 * Put a document on record, in the store AND behind the read the page fires on mount.
 *
 * Both halves are needed, and the second is the one that bites: every other test here asserts
 * synchronously, so they never see what happens when the page's own `loadPartyDocuments` lands.
 * Under the automock that read resolves to `undefined`, which the store writes over the fixture —
 * so a test that awaits anything watches its own rows disappear between two assertions.
 */
function withEvidence(documents: PartyDocumentView[]) {
  usePartyStore.setState({
    documents: { [PARTY_ID]: { ...RESOURCE, data: documents } },
  } as never);
  vi.mocked(listPartyDocuments).mockResolvedValue(documents);
}

/** One KYC document already on record — filed by somebody else, in some earlier session. */
function evidenceOnRecord(over: Partial<PartyDocumentView> = {}): PartyDocumentView {
  return {
    documentRef: 'doc-ref-1',
    documentType: 'KYC_EVIDENCE',
    contentType: 'image/png',
    fileName: 'amina-nida.png',
    uploadedAt: '2026-10-13T08:00:00Z',
    ...over,
  } as PartyDocumentView;
}

beforeEach(() => {
  // Staff here decide KYC and file evidence: customer service, as the page reads it from permissions (2026-10-26).
  grantRoles('CUSTOMER_SERVICE_REP');
  usePartyStore.setState({
    detail: { [PARTY_ID]: { ...RESOURCE, data: party() } },
    documents: { [PARTY_ID]: { ...RESOURCE, data: [] } },
  } as never);
  emptyRegisters();
  /*
   * The page fetches on mount, and under the automock every one of those reads resolves to
   * `undefined` — which the store writes straight over the fixture set above. A synchronous
   * test never notices; the moment a test awaits anything, the record it is asserting on is
   * replaced by an empty one mid-way through, and the symptom is a whole tab going blank
   * between two assertions rather than anything that reads as a stale mock.
   */
  vi.mocked(getParty).mockResolvedValue(party());
  vi.mocked(listPartyDocuments).mockResolvedValue([]);
  vi.mocked(listKycDecisions).mockResolvedValue([]);
  // jsdom implements neither, and the viewer and the Documents tab both make object URLs.
  URL.createObjectURL = vi.fn(() => 'blob:stub');
  URL.revokeObjectURL = vi.fn();
});

describe('PartyDetailPage', () => {
  it('renders the record with the party the store holds', () => {
    renderAt(`/staff/parties/${PARTY_ID}`);
    expect(screen.getByRole('heading', { name: 'Amina Owner' })).toBeInTheDocument();
  });

  /**
   * The record lands on its Overview (2026-10-22, the user): who the person is, and where their KYC stands, before
   * any register. It replaced the pinned rail.
   */
  it('lands on the overview: who the person is, and their KYC', () => {
    renderAt(`/staff/parties/${PARTY_ID}`);
    expect(screen.getByRole('tab', { name: 'Overview', selected: true })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Identity' })).toBeInTheDocument();
    expect(screen.getByText('+255700000001')).toBeInTheDocument();
    const kyc = screen.getByRole('region', { name: 'KYC' });
    expect(within(kyc).getByText('Waiting for a decision on this client’s identity.')).toBeInTheDocument();
    expect(within(kyc).getByRole('link', { name: /Review KYC/ })).toHaveAttribute('href', expect.stringContaining('tab=kyc'));
  });

  it('lands an agent on the overview of their own client too', () => {
    renderAt(`/agents/clients/${PARTY_ID}`, 'agents');
    expect(screen.getByRole('tab', { name: 'Overview', selected: true })).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'KYC' })).getByRole('link', { name: /Send documents/ }))
      .toBeInTheDocument();
  });

  /**
   * THE GATE THAT MATTERS. KYC verification is a staff act -- an agent looking at their own
   * client must not be offered the decision, and `GET /agents` is staff-only besides, which is
   * why the page does not even fetch the agent record in the agents realm.
   */
  it('offers KYC verification to staff', () => {
    renderAt(`/staff/parties/${PARTY_ID}?tab=kyc`, 'staff');
    expect(screen.getByRole('heading', { level: 2, name: 'KYC & documents' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });

  it('offers it to nobody in the agents realm', () => {
    renderAt(`/agents/clients/${PARTY_ID}?tab=kyc`, 'agents');
    expect(screen.getByRole('heading', { level: 2, name: 'KYC & documents' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Agent' })).not.toBeInTheDocument();
  });

  /**
   * An agent sees their own book, and the wording says so. "Your Policies" is not a cosmetic
   * difference from "Policies": a policy written by another agent does not appear, and the
   * subtitle is where the console admits that.
   */
  it('names the register from the reader it is for', () => {
    renderAt(`/agents/clients/${PARTY_ID}?tab=policies`, 'agents');
    expect(screen.getByRole('heading', { level: 2, name: 'Your Policies' })).toBeInTheDocument();
  });

  /**
   * A corporate has no date of birth, sex or smoker status, so the Person panel is withheld
   * rather than rendered as a column of em dashes -- which would read as data the platform lost
   * instead of data it never asked for. The page's own comment says exactly this.
   */
  /**
   * THE DEFECT THIS SUITE EXISTS TO HOLD CLOSED (2026-10-14).
   *
   * Verify and Reject used to be gated on `uploading.status === 'success'` — the upload made in
   * THIS browser session — so a reviewer opening a client whose evidence was already on record
   * got a dropzone and nothing else. There was no way to decide on a document somebody else had
   * filed, which is the only flow the server-side split was ever built for.
   *
   * The store below holds a document and no upload. That is the whole fixture.
   */
  it('lets staff decide on evidence filed in an earlier session', async () => {
    withEvidence([evidenceOnRecord()]);
    renderAt(`/staff/parties/${PARTY_ID}?tab=kyc`);

    // On record and visible, with nothing uploaded here.
    const row = screen.getByRole('button', { name: /amina-nida\.png/ });
    expect(row).toHaveAttribute('aria-pressed', 'false');

    // A decision is recorded against a document, so there is no decision until one is open.
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();

    await userEvent.click(row);

    expect(row).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Verify' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeEnabled();
  });

  /**
   * A DECIDED CASE PUTS ITS CONTROLS AWAY (2026-10-14).
   *
   * The confirmation always closed on success; what stayed was the Verify/Reject pair, so a
   * verified client still led with a live Reject button and an acting panel that read as
   * unfinished work. It cannot simply be removed — a KYC decision is reversible and overturning
   * a wrong rejection is real — so it moves behind one deliberate click.
   */
  it('puts the decision controls away once the client is verified', async () => {
    withEvidence([evidenceOnRecord()]);
    usePartyStore.setState({
      detail: { [PARTY_ID]: { ...RESOURCE, data: party({ kycStatus: 'VERIFIED' }) } },
    } as never);
    vi.mocked(getParty).mockResolvedValue(party({ kycStatus: 'VERIFIED' }));
    renderAt(`/staff/parties/${PARTY_ID}?tab=kyc`);

    expect(screen.getByText('Identity verified. This client may hold a policy.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();

    // Reversible, so the act is reachable — behind an explicit click, not left armed.
    await userEvent.click(screen.getByRole('button', { name: 'Change this decision' }));

    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    // Re-verifying is refused, which is what "already verified" means.
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();
  });

  /** A client nobody has decided on still leads with the act — that is the panel's whole job. */
  it('still leads with the decision while the client is pending', () => {
    withEvidence([evidenceOnRecord()]);
    renderAt(`/staff/parties/${PARTY_ID}?tab=kyc`);

    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change this decision' })).not.toBeInTheDocument();
  });

  /**
   * The decision trail, which the platform had written since its first KYC decision and could
   * not read until 2026-10-14. The point of the panel is the OVERTURNED row: the party record
   * keeps only the decision in force, so this list is the only place a client's rejection
   * survives their later verification.
   */
  it('shows every KYC decision, including one later overturned', async () => {
    withEvidence([evidenceOnRecord()]);
    vi.mocked(listKycDecisions).mockResolvedValue([
      {
        status: 'VERIFIED',
        evidenceDocumentRef: 'doc-ref-1',
        decidedBy: 'subject-juma',
        decidedByName: 'Juma Senior',
        decidedAt: '2026-10-13T09:00:00Z',
      },
      {
        status: 'REJECTED',
        evidenceDocumentRef: 'doc-ref-gone',
        decidedBy: 'subject-asha',
        decidedByName: null,
        decidedAt: '2026-10-01T09:00:00Z',
      },
    ]);
    renderAt(`/staff/parties/${PARTY_ID}?tab=kyc`);

    expect(await screen.findByText('Juma Senior')).toBeInTheDocument();
    // The party reads VERIFIED; only this panel remembers the rejection.
    const overturned = screen.getByText('Rejected').closest('li');
    expect(overturned).not.toBeNull();
    // A decision taken before the name was captured says so rather than printing a subject.
    // Scoped to the row, because the record rail says the same of an unnamed registrar.
    expect(within(overturned as HTMLElement).getByText('Name not recorded')).toBeInTheDocument();
    expect(screen.queryByText('subject-asha')).not.toBeInTheDocument();

    // The evidence still on file is a way back into the viewer; the ref that is not stays text.
    expect(
      screen.getByRole('button', { name: /Open the evidence this verified decision/ }),
    ).toBeInTheDocument();
    expect(screen.getByText('doc-ref-gone')).toBeInTheDocument();
  });

  /**
   * The agent's half of the same split: send the papers, do not decide on them. Both absences
   * are the server's rules — `POST /parties/{id}/kyc` and `GET /documents/{ref}` are each
   * staff-only — so a control offered here would be a 403 discovered after the click.
   */
  it('lets an agent file evidence but never decide or open it', () => {
    withEvidence([evidenceOnRecord()]);
    renderAt(`/agents/clients/${PARTY_ID}?tab=kyc`, 'agents');

    expect(screen.getByRole('region', { name: 'Identity evidence' })).toBeInTheDocument();
    expect(screen.getByLabelText('Send identity evidence')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    // The filed document is listed, and is not a control.
    expect(screen.getByText('amina-nida.png')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /amina-nida\.png/ })).not.toBeInTheDocument();
  });

  /**
   * The client's own documents in one place (2026-10-22, the user): identity evidence and signed forms, each saying
   * where it came from -- the registering agent's uploads by name, anything else from the office. There is no
   * separate Documents tab any more, and a signed form cannot carry a KYC decision.
   */
  it('groups what was filed on the client and says who filed each', async () => {
    usePartyStore.setState({
      detail: {
        [PARTY_ID]: {
          ...RESOURCE,
          data: party({ createdBy: 'subject-neema', createdByName: 'Neema Junior', registeredByPartyId: 'agent-party' }),
        },
      },
    } as never);
    vi.mocked(getParty).mockResolvedValue(
      party({ createdBy: 'subject-neema', createdByName: 'Neema Junior', registeredByPartyId: 'agent-party' }),
    );
    withEvidence([
      evidenceOnRecord({ uploadedBy: 'subject-neema' }),
      evidenceOnRecord({ documentRef: 'doc-ref-2', documentType: 'SIGNED_FORM', fileName: 'proposal-signed.pdf',
        contentType: 'application/pdf', uploadedBy: 'subject-office' }),
    ]);
    renderAt(`/staff/parties/${PARTY_ID}?tab=kyc`);

    expect(screen.queryByRole('tab', { name: 'Documents' })).not.toBeInTheDocument();
    const evidence = screen.getByRole('region', { name: 'Identity evidence' });
    expect(within(evidence).getByText(/Sent by Neema Junior, the registering agent/)).toBeInTheDocument();
    const forms = screen.getByRole('region', { name: 'Signed forms' });
    expect(within(forms).getByText(/Filed by the office/)).toBeInTheDocument();

    await userEvent.click(within(forms).getByRole('button', { name: /proposal-signed\.pdf/ }));
    expect(screen.getByText('A signed form is not proof of identity. Open identity evidence to decide on KYC.'))
      .toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument();

    // What is being added is said before it is dropped.
    await userEvent.click(screen.getByRole('radio', { name: 'Signed form' }));
    expect(screen.getByLabelText('Add signed form')).toBeInTheDocument();
  });

  // The user, 2026-10-22: a list of policy numbers did not say which policy is which product.
  it('says what product each policy was sold as', () => {
    useClientRecordStore.setState({
      policies: {
        [PARTY_ID]: {
          ...RESOURCE,
          data: {
            page: { page: 0, pageSize: 20, totalElements: 1 },
            items: [{ policyNumber: 'POL-FUN-1', status: 'ACTIVE', productCategory: 'FUNERAL' }],
          },
        },
      },
    } as never);
    renderAt(`/staff/parties/${PARTY_ID}?tab=policies`);
    const row = screen.getByRole('link', { name: /POL-FUN-1/ });
    expect(within(row).getByText('Funeral')).toBeInTheDocument();
  });

  it('withholds the underwriting-facts panel from a corporate', () => {
    usePartyStore.setState({
      detail: {
        [PARTY_ID]: {
          ...RESOURCE,
          data: party({ partyType: 'CORPORATE', dateOfBirth: null, registrationNumber: 'RC-1' }),
        },
      },
    } as never);
    renderAt(`/staff/parties/${PARTY_ID}`);
    expect(screen.getByRole('heading', { level: 2, name: 'Identity' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'Underwriting facts' })).not.toBeInTheDocument();
  });

  it('shows an individual the underwriting facts a premium is rated on', () => {
    usePartyStore.setState({
      detail: { [PARTY_ID]: { ...RESOURCE, data: party({ sex: 'FEMALE', smokerStatus: 'NON_SMOKER' }) } },
    } as never);
    renderAt(`/staff/parties/${PARTY_ID}`);
    expect(screen.getByRole('heading', { level: 2, name: 'Underwriting facts' })).toBeInTheDocument();
    expect(screen.getByText('Non-smoker')).toBeInTheDocument();
  });
});
