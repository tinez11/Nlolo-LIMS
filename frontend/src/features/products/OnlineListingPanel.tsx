import { useEffect, useState } from 'react';
import { describeOnline, getOnlineListing, type OnlineListingView } from '@/api/products';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Textarea } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';

/**
 * Whether customers see the product in the portal, and what they read about it (2026-10-08, the customer portal design
 * step 5). Off until somebody says what the product is for. ADMIN edits; everyone on staff can read it.
 */
export function OnlineListingPanel({ productId, canEdit }: { productId: string; canEdit: boolean }) {
  const [listing, setListing] = useState<OnlineListingView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [editing, setEditing] = useState(false);
  const [available, setAvailable] = useState(false);
  const [summary, setSummary] = useState('');
  const [benefits, setBenefits] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    getOnlineListing(productId).then(
      (l) => { if (live) { setListing(l); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [productId, reload]);

  function startEditing(current: OnlineListingView) {
    setAvailable(current.available);
    setSummary(current.summary ?? '');
    setBenefits(current.benefits.join('\n'));
    setSaveError(null);
    setEditing(true);
  }

  async function save() {
    setSaving(true);
    setSaveError(null);
    try {
      const saved = await describeOnline(productId, {
        available, summary: summary.trim() || null, benefits: benefits.split('\n').map((b) => b.trim()).filter(Boolean),
      });
      setListing(saved);
      setEditing(false);
    } catch (e) {
      setSaveError(toApiError(e));
    } finally {
      setSaving(false);
    }
  }

  if (error) return <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />;
  if (!listing) return <LoadingBlock />;

  if (editing) {
    return (
      <div className="space-y-3 p-4">
        <CheckboxField label="Offer it in the customer portal" checked={available}
          onChange={(e) => setAvailable(e.target.checked)} />
        <FormField label="What it is for" hint="One or two sentences a customer reads first. Needed to offer it.">
          <Textarea rows={2} value={summary} onChange={(e) => setSummary(e.target.value)} />
        </FormField>
        <FormField label="Key benefits" hint="One per line.">
          <Textarea rows={4} value={benefits} onChange={(e) => setBenefits(e.target.value)} />
        </FormField>
        {saveError && <InlineError error={saveError} lead="Not saved" />}
        <div className="flex gap-2">
          <Button variant="primary" pending={saving} disabled={available && !summary.trim()} onClick={() => void save()}>
            Save
          </Button>
          <Button variant="ghost" disabled={saving} onClick={() => setEditing(false)}>Cancel</Button>
        </div>
      </div>
    );
  }

  return (
    <div className="pb-2">
      <dl className="px-4">
        <Field label="In the portal" value={listing.available ? 'Offered to customers' : 'Not offered'} />
        <Field label="What it is for" value={listing.summary ?? '—'} />
        {listing.benefits.length > 0 && <Field label="Key benefits" value={listing.benefits.join(' · ')} />}
      </dl>
      {canEdit && (
        <div className="px-4 pt-2">
          <Button size="sm" onClick={() => startEditing(listing)}>Change</Button>
        </div>
      )}
    </div>
  );
}
