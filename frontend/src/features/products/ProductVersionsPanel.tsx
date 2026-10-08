import { ChevronDown, ChevronRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { getAnnuityTerms } from '@/api/annuity';
import { getFuneralTerms } from '@/api/funeral';
import { listProductVersions } from '@/api/products';
import type { AnnuityTermsView, FuneralTermsView, ProductVersionSummaryView, UnitLinkedTermsView } from '@/api/types';
import { getUnitLinkedTerms } from '@/api/unitlinked';
import { Field } from '@/components/Field';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate, todayIso } from '@/lib/dates';
import { bucketLabel, modelLabel } from '@/lib/ifrs17';
import { selectVersionRating, useProductStore } from '@/store/productStore';
import { RatingBasis } from './RatingBasis';
import { funeralSummary, pricedOtherwise } from './versionText';
import { AnnuityTermsDetail, FuneralTermsDetail, UnitLinkedTermsDetail } from './VersionTerms';

/**
 * Every published version of a product (2026-10-08): one version is shown whole; several are a list, newest first,
 * the one a sale today is priced on marked Current and open, any other opened by a click. The page showed only the
 * version in force today, so a product published four times read as having one.
 */
export function ProductVersionsPanel({ productId, category, refreshKey }: {
  productId: string;
  category: string | null | undefined;
  /** Changes when a version is published here, so the list reloads. */
  refreshKey: number;
}) {
  const [versions, setVersions] = useState<ProductVersionSummaryView[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  // Null until the reader opens or closes one: the current version starts open.
  const [open, setOpen] = useState<Record<string, boolean>>({});

  useEffect(() => {
    let live = true;
    listProductVersions(productId).then(
      (v) => { if (live) { setVersions(v); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [productId, refreshKey, reload]);

  if (error && versions === null) return <div className="p-4"><ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} /></div>;
  if (versions === null) return <LoadingBlock label="Loading versions" />;
  if (versions.length === 0) {
    return <p className="px-4 pb-4 pt-3 text-xs text-muted-foreground">No version has been published yet.</p>;
  }
  if (versions.length === 1) {
    const only = versions[0] as ProductVersionSummaryView;
    return (
      <div className="pb-2">
        <p className="px-4 pt-3 text-xs text-muted-foreground">
          One version, {stateOf(only)}. Published {formatDate(only.publishedAt)}{only.publishedBy ? ` by ${only.publishedBy}` : ''}.
        </p>
        <VersionDetails productId={productId} category={category} version={only} />
      </div>
    );
  }

  const isOpen = (v: ProductVersionSummaryView) => open[v.productVersionId] ?? v.current;
  return (
    <ul className="divide-y divide-border" aria-label="Versions">
      {versions.map((v, i) => (
        <li key={v.productVersionId}>
          <button type="button" aria-expanded={isOpen(v)}
            className="flex w-full items-start gap-2 px-4 py-3 text-left hover:bg-hover"
            onClick={() => setOpen((o) => ({ ...o, [v.productVersionId]: !isOpen(v) }))}>
            {isOpen(v) ? <ChevronDown className="mt-0.5 size-4 shrink-0" /> : <ChevronRight className="mt-0.5 size-4 shrink-0" />}
            <span className="min-w-0 flex-1">
              <span className="flex flex-wrap items-center gap-2 text-sm font-medium">
                Version {versions.length - i}
                <VersionBadge version={v} />
              </span>
              <span className="block text-xs text-muted-foreground">
                Effective {formatDate(v.effectiveDate)}{v.retirementDate ? ` to ${formatDate(v.retirementDate)}` : ''}
                {' · '}published {formatDate(v.publishedAt)}{v.publishedBy ? ` by ${v.publishedBy}` : ''}
              </span>
              {category === 'FUNERAL' && <FuneralSummaryLine productId={productId} versionId={v.productVersionId} />}
            </span>
          </button>
          {isOpen(v) && <div className="border-t border-border bg-surface"><VersionDetails productId={productId} category={category} version={v} /></div>}
        </li>
      ))}
    </ul>
  );
}

function stateOf(v: ProductVersionSummaryView): string {
  if (v.current) return 'current — what a sale today is priced on';
  return v.effectiveDate > todayIso() ? `starting ${formatDate(v.effectiveDate)}` : 'superseded';
}

function VersionBadge({ version }: { version: ProductVersionSummaryView }) {
  if (version.current) {
    return <span className="rounded bg-status-success-bg px-1.5 py-0.5 text-xs font-medium text-status-success-fg">Current</span>;
  }
  if (version.effectiveDate > todayIso()) {
    return <span className="rounded bg-status-pending-bg px-1.5 py-0.5 text-xs font-medium text-status-pending-fg">Starts {formatDate(version.effectiveDate)}</span>;
  }
  return <span className="rounded bg-status-neutral-bg px-1.5 py-0.5 text-xs text-status-neutral-fg">Superseded</span>;
}

/** A funeral version in one line under its row: plan, main member cover, and what it costs. */
function FuneralSummaryLine({ productId, versionId }: { productId: string; versionId: string }) {
  const terms = useTerms<FuneralTermsView>(getFuneralTerms, productId, versionId);
  if (!terms) return null;
  return <span className="mt-0.5 block text-xs">{funeralSummary(terms)}</span>;
}

function useTerms<T>(load: (productId: string, versionId: string) => Promise<T | null>, productId: string, versionId: string): T | null {
  const [terms, setTerms] = useState<{ versionId: string; value: T | null } | null>(null);
  useEffect(() => {
    let live = true;
    load(productId, versionId).then((t) => { if (live) setTerms({ versionId, value: t }); }, () => undefined);
    return () => { live = false; };
  }, [load, productId, versionId]);
  return terms?.versionId === versionId ? terms.value : null;
}

function VersionDetails({ productId, category, version }: {
  productId: string;
  category: string | null | undefined;
  version: ProductVersionSummaryView;
}) {
  const versionId = version.productVersionId;
  const rating = useProductStore(selectVersionRating(versionId));
  const loadRating = useProductStore((s) => s.loadRating);
  useEffect(() => {
    void loadRating(productId, versionId);
  }, [productId, versionId, loadRating]);

  return (
    <div className="pb-2">
      <dl className="px-4">
        <Field label="Effective" value={`${formatDate(version.effectiveDate)}${version.retirementDate ? ` to ${formatDate(version.retirementDate)}` : ''}`} />
        <Field label="Grace period" value={`${version.gracePeriodDays} days`} />
        <Field label="Expected profitability" value={bucketLabel(version.expectedProfitabilityBucket)} />
        <Field label="Model override"
          value={version.measurementModelOverride ? modelLabel(version.measurementModelOverride) : 'None — the register decides'} />
        {version.survivalInvestmentComponentPercent != null && (
          <Field label="Survival investment component" value={`${version.survivalInvestmentComponentPercent}%`} />
        )}
        {/* Only where base rates price the version: a funeral plan has none and is priced all the same. */}
        {!pricedOtherwise(category) && (
          <Field label="Base rate cells"
            value={rating.data ? String((rating.data.baseRates ?? []).length) : rating.status === 'error' ? '—' : 'Loading…'}
            {...(rating.data && (rating.data.baseRates ?? []).length === 0
              ? { note: 'No cells means unpriced — no premium can be quoted.' } : {})} />
        )}
      </dl>
      {category === 'FUNERAL' && <FuneralTerms productId={productId} versionId={versionId} />}
      {category === 'ANNUITY' && <AnnuityTerms productId={productId} versionId={versionId} />}
      {category === 'UNIT_LINKED' && <UnitLinkedTerms productId={productId} versionId={versionId} />}
      <RatingBasis rating={rating} versionId={versionId} category={category}
        onRetry={() => void loadRating(productId, versionId)} />
    </div>
  );
}

function FuneralTerms({ productId, versionId }: { productId: string; versionId: string }) {
  const terms = useTerms<FuneralTermsView>(getFuneralTerms, productId, versionId);
  return terms ? <FuneralTermsDetail terms={terms} /> : <LoadingBlock label="Loading the plan terms" />;
}

function AnnuityTerms({ productId, versionId }: { productId: string; versionId: string }) {
  const terms = useTerms<AnnuityTermsView>(getAnnuityTerms, productId, versionId);
  return terms ? <AnnuityTermsDetail terms={terms} /> : null;
}

function UnitLinkedTerms({ productId, versionId }: { productId: string; versionId: string }) {
  const terms = useTerms<UnitLinkedTermsView>(getUnitLinkedTerms, productId, versionId);
  return terms ? <UnitLinkedTermsDetail terms={terms} /> : null;
}
