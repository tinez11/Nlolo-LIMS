import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  discardAgentQuotation,
  getAgentQuotation,
  submitProposal,
  type AgentQuotation,
} from '@/api/agentQuotations';
import { Field } from '@/components/Field';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { Receipt } from '@/components/Receipt';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { useProductStore } from '@/store/productStore';
import { AcceptedQuotationPanel, QuotationUnavailable } from './AcceptedQuotationPanel';

/**
 * The last step of the agents' prospect-to-proposal flow (2026-10-20): the customer, now registered, and the
 * quotation they agreed to, submitted together to the underwriter's queue.
 *
 * <p>The proposal is opened on the server from the SAVED quotation -- its version, sum assured, term, frequency and
 * family -- so nothing here can change what the customer was quoted. Cancel discards the quotation: the customer is
 * not going ahead.
 */
export function SubmitProposalPage() {
  const { quotationId = '' } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [quotation, setQuotation] = useState<AgentQuotation | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState<'submit' | 'discard' | null>(null);
  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);

  useEffect(() => {
    let live = true;
    void loadProducts();
    getAgentQuotation(quotationId).then(
      (q) => { if (live) setQuotation(q); },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [quotationId, loadProducts]);

  // The registered customer: the client the quotation was for, or the one just onboarded from it.
  const applicantPartyId = quotation?.applicantPartyId ?? params.get('party') ?? '';
  const productName = (products.data ?? []).find((p) => p.productId === quotation?.productId)?.productName;

  async function submit() {
    setBusy('submit');
    setError(null);
    try {
      setQuotation(await submitProposal(quotationId, applicantPartyId));
    } catch (e) {
      const error = toApiError(e);
      setError(error);
      // Submitted from another tab, or a double click that lost: read it again, so the screen shows the proposal that
      // did go in rather than an error about one that exists.
      if (error.status === 422) {
        getAgentQuotation(quotationId).then((q) => { if (q.caseId) { setQuotation(q); setError(null); } }, () => undefined);
      }
    } finally {
      setBusy(null);
    }
  }

  async function discard() {
    setBusy('discard');
    setError(null);
    try {
      await discardAgentQuotation(quotationId);
      navigate('../../quotation', { relative: 'path' });
    } catch (e) {
      const error = toApiError(e);
      // Already gone: what the agent asked for is true, so go back regardless.
      if (error.status === 404) {
        navigate('../../quotation', { relative: 'path' });
        return;
      }
      setError(error);
      setBusy(null);
    }
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Quotation calculator', to: '/agents/quotation' }]}
        title="Submit the proposal"
        description="The customer and the quotation they agreed to, to the underwriter."
      />
      <FormLayout formId="submit-proposal" outlineLabel="Proposal sections" card={false}>
      <div className="space-y-4">
        {!quotation && !error && <LoadingBlock label="Loading the quotation…" />}
        {quotation && <AcceptedQuotationPanel quotation={quotation} productName={productName} />}

        {quotation && (
          <Panel title="The customer">
            <dl className="px-4 pb-3">
              <Field
                label="Registered customer"
                value={applicantPartyId ? <PartyName partyId={applicantPartyId} /> : '—'}
                {...(applicantPartyId ? {} : { note: 'Register the customer first.' })}
              />
            </dl>
          </Panel>
        )}

        {error && (!quotation || error.status === 404
          ? <QuotationUnavailable error={error} />
          : <InlineError error={error} lead="The proposal could not be submitted:" />)}

        {quotation?.caseId ? (
          <Receipt
            heading="Submitted to underwriting"
            lines={[
              { label: 'Proposal', value: quotation.proposalNumber ?? '—' },
              { label: 'Customer', value: <PartyName partyId={applicantPartyId} /> },
            ]}
            note="An underwriter decides it. If it is returned for corrections, it appears under Needs your action."
            onward={
              <div className="flex flex-wrap gap-2">
                <Button asChild size="sm" variant="outline">
                  <Link to={`/agents/proposals/${quotation.caseId}`}>Open the proposal</Link>
                </Button>
                <Button asChild size="sm" variant="ghost">
                  <Link to="/agents/dashboard">Back to the dashboard</Link>
                </Button>
              </div>
            }
          />
        ) : (
          quotation && (
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="primary"
                disabled={!applicantPartyId || busy !== null}
                pending={busy === 'submit'}
                onClick={() => void submit()}
              >
                Submit to underwriting
              </Button>
              <Button
                type="button"
                variant="ghost"
                disabled={busy !== null}
                pending={busy === 'discard'}
                onClick={() => void discard()}
              >
                Cancel and discard the quotation
              </Button>
            </div>
          )
        )}
      </div>
      </FormLayout>
    </>
  );
}
