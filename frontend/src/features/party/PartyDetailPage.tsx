import { ArrowLeft, Check, FileCheck, X } from 'lucide-react';
import { useEffect } from 'react';
import { useDropzone } from 'react-dropzone';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/AppShell';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import type { KycStatus } from '@/api/types';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectParty,
  selectSubmittingKyc,
  selectUploadingKycEvidence,
  usePartyStore,
} from '@/store/partyStore';

const ACCEPTED_EVIDENCE_TYPES = {
  'image/jpeg': ['.jpg', '.jpeg'],
  'image/png': ['.png'],
  'application/pdf': ['.pdf'],
};

/**
 * Reached only by drilling in from a partyId already on screen (a policy's
 * policyholderPartyId, a claim's claimantPartyId, an underwriting case's
 * applicantPartyId, an agent's own partyId) -- there is no `GET /parties`
 * list anywhere on the platform. `PartyApi.submitKycEvidence` has always
 * required a real `evidenceDocumentRef`; until this staff-portal CRUD audit
 * there was no upload path anywhere that could produce one for a KYC
 * purpose, so this page also owns that upload, not just the decision.
 */
export function PartyDetailPage() {
  const { partyId = '' } = useParams();
  const navigate = useNavigate();
  const detail = usePartyStore(selectParty(partyId));
  const loadParty = usePartyStore((s) => s.loadParty);

  useEffect(() => {
    if (partyId) void loadParty(partyId);
  }, [partyId, loadParty]);

  const party = detail.data;

  if (isInitialLoad(detail)) {
    return <LoadingBlock label="Loading party" />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink onClick={() => navigate(-1)} />
        <ErrorPanel error={detail.error} onRetry={() => void loadParty(partyId)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink onClick={() => navigate(-1)} />
      </div>

      <PageHeader
        title={party?.displayName ?? 'Party'}
        description={party?.partyType ? <span>{party.partyType}</span> : undefined}
        actions={party?.kycStatus && <StatusBadge kind="kyc" value={party.kycStatus} />}
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <Panel
            title="KYC verification"
            subtitle="Upload evidence, then record a decision against it."
          >
            {party && <KycPanel partyId={partyId} currentStatus={party.kycStatus} />}
          </Panel>
        </div>

        <div className="space-y-5">
          <Panel title="Party">
            {party && (
              <dl className="px-4 pb-2">
                <Field label="Party id" value={<span className="font-mono text-xs">{partyId}</span>} />
                <Field label="Type" value={party.partyType ?? '—'} />
              </dl>
            )}
          </Panel>
        </div>
      </div>
    </>
  );
}

function KycPanel({
  partyId,
  currentStatus,
}: {
  partyId: string;
  currentStatus: KycStatus | undefined;
}) {
  const uploadKycEvidence = usePartyStore((s) => s.uploadKycEvidence);
  const resetUploadKycEvidence = usePartyStore((s) => s.resetUploadKycEvidence);
  const uploading = usePartyStore(selectUploadingKycEvidence(partyId));

  const submitKyc = usePartyStore((s) => s.submitKyc);
  const resetSubmitKyc = usePartyStore((s) => s.resetSubmitKyc);
  const deciding = usePartyStore(selectSubmittingKyc(partyId));

  useEffect(() => {
    resetUploadKycEvidence(partyId);
    resetSubmitKyc(partyId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [partyId]);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    accept: ACCEPTED_EVIDENCE_TYPES,
    multiple: false,
    onDrop: (accepted) => {
      if (accepted[0]) void uploadKycEvidence(partyId, accepted[0]);
    },
  });

  const documentRef = uploading.status === 'success' ? uploading.data?.documentRef : undefined;

  async function decide(status: KycStatus) {
    if (!documentRef) return;
    await submitKyc(partyId, status, documentRef);
  }

  return (
    <div className="space-y-3 px-4 pt-3 pb-4">
      <div
        {...getRootProps()}
        className={cn(
          'flex cursor-pointer flex-col items-center gap-1.5 rounded-md border border-dashed border-border px-4 py-6 text-center transition-colors',
          isDragActive ? 'border-accent bg-hover' : 'hover:bg-hover',
          uploading.status === 'loading' && 'pointer-events-none opacity-60',
        )}
      >
        <input {...getInputProps()} disabled={uploading.status === 'loading'} />
        <FileCheck className="size-5 text-muted-foreground" />
        <p className="text-xs text-muted-foreground">
          {uploading.status === 'loading'
            ? 'Uploading…'
            : isDragActive
              ? 'Drop the file'
              : 'Drag an ID scan or proof of address here, or click to browse'}
        </p>
        <p className="text-[11px] text-subtle-foreground">JPEG, PNG, or PDF</p>
      </div>

      {uploading.status === 'error' && uploading.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {uploading.error.detail ?? uploading.error.title}
        </p>
      )}

      {documentRef && (
        <div className="space-y-2 rounded-md border border-border p-2.5">
          <p className="text-[11px] text-muted-foreground">
            Evidence uploaded: <span className="font-mono">{documentRef}</span>
          </p>
          <div className="flex items-center gap-1.5">
            <Button
              size="sm"
              variant="primary"
              disabled={deciding.status === 'loading' || currentStatus === 'VERIFIED'}
              onClick={() => void decide('VERIFIED')}
            >
              <Check />
              Verify
            </Button>
            <Button
              size="sm"
              variant="danger"
              disabled={deciding.status === 'loading' || currentStatus === 'REJECTED'}
              onClick={() => void decide('REJECTED')}
            >
              <X />
              Reject
            </Button>
          </div>
          {deciding.status === 'error' && deciding.error && (
            <p role="alert" className="text-[11px] text-status-danger-fg">
              {deciding.error.detail ?? deciding.error.title}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Goes back to wherever the caller actually came from -- unlike every other
 * detail page's BackLink, a party is reached from four different contexts
 * (policy/claim/underwriting/agent), so there is no single "all X" list this
 * could point at.
 */
function BackLink({ onClick }: { onClick: () => void }) {
  return (
    <Button variant="ghost" size="sm" className="-ml-2" onClick={onClick}>
      <ArrowLeft />
      Back
    </Button>
  );
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
