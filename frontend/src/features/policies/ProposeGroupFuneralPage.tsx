import { Plus, Upload } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { getFuneralTerms } from '@/api/funeral';
import { downloadGroupScheduleTemplate, readGroupSchedule } from '@/api/groupFuneral';
import { saveBlob } from '@/lib/download';
import type { FuneralTermsView } from '@/api/types';
import { canUnderwriteGroupSchemes, readIdentity } from '@/auth/claims';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { PartyPicker } from '@/components/PartyPicker';
import { NoAccess } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { useUnderwritingStore } from '@/store/underwritingStore';
import { FamilyEditor } from './FamilyEditor';
import {
  blankFamily,
  fromLives,
  groupPlans,
  incompleteFamilies,
  monthlyBill,
  planBenefit,
  toLives,
  type FamilyRow,
} from './groupFuneral';
import { UnsavedGuard } from '@/components/UnsavedGuard';

/**
 * Propose a group funeral scheme (2026-10-07): an association, one plan of a FUNERAL product sold to
 * groups, and the members with their families -- typed, or read from the association's file. There is
 * no premium field: the bill is members x the plan's group rate, worked out when the scheme is issued.
 * Like every scheme it opens an underwriting case; the decision issues the scheme as an offer the
 * association accepts by paying.
 */
export function ProposeGroupFuneralPage() {
  const canUnderwrite = canUnderwriteGroupSchemes(readIdentity(useAuth().user?.access_token));
  const navigate = useNavigate();

  const openGroupCase = useUnderwritingStore((s) => s.openGroupCase);
  const resetOpenCase = useUnderwritingStore((s) => s.resetOpenCase);
  const openingStatus = useUnderwritingStore((s) => s.opening.status);
  const opening = useUnderwritingStore((s) => s.opening);
  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  useEffect(() => {
    resetOpenCase();
    void loadProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [association, setAssociation] = useState('');
  const [productId, setProductId] = useState('');
  const [planCode, setPlanCode] = useState('');
  const [commencementDate, setCommencementDate] = useState('');
  const [termMonths, setTermMonths] = useState('');
  const [families, setFamilies] = useState<FamilyRow[]>(() => [blankFamily([])]);
  // Every edit replaces the array, so the first one kept here says whether anything was touched.
  const [untouchedFamilies] = useState(families);
  const [fileProblems, setFileProblems] = useState<string[]>([]);
  const [showProblems, setShowProblems] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const funeralProducts = useMemo(
    () => (products.data ?? []).filter((p) => p.category === 'FUNERAL'),
    [products.data],
  );
  const product = funeralProducts.find((p) => p.productId === productId);
  const currency = product?.defaultCurrency ?? 'TZS';

  const snapshot = useProductStore(selectProductSnapshot(productId));
  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);
  const versionId = snapshot.data?.productVersionId ?? '';

  const [terms, setTerms] = useState<FuneralTermsView | null>(null);
  useEffect(() => {
    if (!productId || !versionId) return undefined;
    let live = true;
    getFuneralTerms(productId, versionId).then((t) => { if (live) setTerms(t); }, () => undefined);
    return () => { live = false; };
  }, [productId, versionId]);

  const plans = groupPlans(productId && versionId ? terms : null);
  const plan = plans.find((p) => p.planCode === planCode) ?? null;
  const problems = incompleteFamilies(families);
  const missing = [
    ...(association ? [] : ['Choose the association']),
    ...(plan ? [] : ['Choose a plan']),
    ...problems,
  ];

  async function readFile(file: File) {
    try {
      const reading = await readGroupSchedule(file);
      if (reading.lives.length > 0) setFamilies(fromLives(reading.lives));
      setFileProblems(reading.problems);
    } catch {
      setFileProblems(['The file could not be read; it must be the CSV template (member_reference, role, full_name, ...).']);
    }
  }

  async function onSubmit() {
    setShowProblems(true);
    if (missing.length > 0 || !plan) return;
    await openGroupCase({
      policyholderPartyId: association,
      productId,
      productVersionId: versionId,
      agentOfRecordId: null,
      benefitBasis: 'FUNERAL_PLAN',
      planCode: plan.planCode,
      lives: toLives(families),
      currency,
      premiumFrequency: 'MONTHLY',
      commencementDate: commencementDate || null,
      policyTermMonths: termMonths ? Number(termMonths) : null,
    });
    const result = useUnderwritingStore.getState().opening;
    if (result.status === 'success' && result.data?.caseId) {
      navigate(`/staff/underwriting/${encodeURIComponent(result.data.caseId)}`);
    }
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
        title="Propose a group funeral scheme"
        description="An association's members and their families on one plan. An underwriter decides it; cover starts when the association's first premium clears."
      />
      {!canUnderwrite && <NoAccess what="Proposing a group funeral scheme" who="underwriters" />}
      {canUnderwrite && (
        <form className="max-w-3xl space-y-4 px-6 pb-8" onSubmit={(e) => { e.preventDefault(); void onSubmit(); }}>
          {/* Not react-hook-form, so "dirty" is spelt out; and it stands down while the case is
              being opened or once it has been, so the jump to the new case is not held. */}
          <UnsavedGuard
            when={
              (association !== '' || productId !== '' || families !== untouchedFamilies) &&
              openingStatus !== 'loading' &&
              openingStatus !== 'success'
            }
            what="This scheme proposal"
          />
          <FormField label="Association (the policyholder, who pays)">
            <PartyPicker value={association || null} onChange={(id) => setAssociation(id ?? '')}
              placeholder="Search for the association by name" />
          </FormField>

          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label="Funeral product">
              {isInitialLoad(products) ? (
                <p className="text-xs text-muted-foreground">Loading products…</p>
              ) : funeralProducts.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  No funeral products exist yet. <Link to="/staff/products/new" className="underline">Author one</Link>{' '}
                  sold to group schemes first.
                </p>
              ) : (
                <Select value={productId} onChange={(e) => { setProductId(e.target.value); setPlanCode(''); setTerms(null); }}>
                  <option value="">Select a funeral product</option>
                  {funeralProducts.map((p) => (
                    <option key={p.productId} value={p.productId}>{p.productName} ({p.productCode})</option>
                  ))}
                </Select>
              )}
            </FormField>
            <FormField label="Plan">
              {productId && terms && plans.length === 0 ? (
                <p className="text-xs text-status-danger-fg">
                  This product is not sold to group schemes. Choose one whose terms are sold to groups, with a group rate.
                </p>
              ) : (
                <Select value={planCode} onChange={(e) => setPlanCode(e.target.value)} disabled={plans.length === 0}>
                  <option value="">Select a plan</option>
                  {plans.map((p) => (
                    <option key={p.planCode} value={p.planCode}>
                      {p.name} — {currency} {p.rate.toLocaleString('en-US')} per member per {p.period === 'YEARLY' ? 'year' : 'month'}
                    </option>
                  ))}
                </Select>
              )}
            </FormField>
            <FormField label="Cover commences">
              <DatePicker value={commencementDate || null} onChange={(iso) => setCommencementDate(iso ?? '')} />
              <p className="mt-1 text-xs text-subtle-foreground">Blank means today. A future date is not supported.</p>
            </FormField>
            <FormField label="Term in months (optional)">
              <Input inputMode="numeric" value={termMonths} onChange={(e) => setTermMonths(e.target.value)}
                placeholder="Leave blank — renews annually" />
            </FormField>
          </div>

          {plan && (
            <p className="rounded-md border border-border bg-hover px-3 py-2 text-xs">
              Covered per family: main member {fmt(planBenefit(terms, plan.planCode, 'MAIN_MEMBER'))}, spouse{' '}
              {fmt(planBenefit(terms, plan.planCode, 'SPOUSE'))}, child {fmt(planBenefit(terms, plan.planCode, 'CHILD'))}.
              The bill: <strong>{monthlyBill(families.length, plan.rate, currency, plan.period)}</strong>.
            </p>
          )}

          <fieldset className="border-t border-border pt-3">
            <legend className="pr-2 text-eyebrow text-subtle-foreground uppercase">
              Members and their families
            </legend>
            <div className="mb-2.5 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>Type them, or read the association&apos;s file (it replaces what is below).</span>
              <Button type="button" size="sm" variant="ghost"
                onClick={async () => saveBlob(await downloadGroupScheduleTemplate(), 'group-funeral-schedule-template.csv')}>
                Download template
              </Button>
              <input ref={fileInput} type="file" accept=".csv,text/csv" className="hidden" aria-label="Schedule file"
                onChange={(e) => { const f = e.target.files?.[0]; if (f) void readFile(f); e.target.value = ''; }} />
              <Button type="button" size="sm" variant="ghost" onClick={() => fileInput.current?.click()}>
                <Upload />
                Read a file
              </Button>
            </div>
            {fileProblems.length > 0 && (
              <ul className="mb-2 list-disc space-y-0.5 pl-5 text-xs text-status-danger-fg" aria-label="File problems">
                {fileProblems.map((p) => <li key={p}>{p}</li>)}
              </ul>
            )}

            <div className="space-y-3">
              {families.map((family, index) => (
                <FamilyEditor key={index} family={family}
                  onChange={(next) => setFamilies((all) => all.map((f, i) => (i === index ? next : f)))}
                  onRemove={families.length === 1 ? undefined
                    : () => setFamilies((all) => all.filter((_, i) => i !== index))} />
              ))}
            </div>
            <Button type="button" size="sm" variant="ghost" className="mt-2 -ml-2"
              onClick={() => setFamilies((all) => [...all, blankFamily(all)])}>
              <Plus />
              Add member
            </Button>
          </fieldset>

          {showProblems && missing.length > 0 && (
            <ul className="list-disc space-y-0.5 pl-5 text-xs text-status-danger-fg" aria-label="Before proposing">
              {missing.map((p) => <li key={p}>{p}</li>)}
            </ul>
          )}
          {opening.status === 'error' && opening.error && <InlineError error={opening.error} />}

          <div className="flex items-center gap-2 border-t border-border pt-4">
            <Button type="submit" variant="primary" pending={opening.status === 'loading'}>Propose scheme</Button>
            <Button asChild type="button" variant="ghost"><Link to="/staff/policies">Cancel</Link></Button>
          </div>
        </form>
      )}
    </>
  );
}

function fmt(amount: number | null): string {
  return amount == null ? 'not covered' : amount.toLocaleString('en-US');
}
