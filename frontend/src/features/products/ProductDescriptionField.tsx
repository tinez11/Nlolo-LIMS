import { useState } from 'react';
import { describeProduct } from '@/api/products';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';

/**
 * What the product is, as staff read it when choosing one to quote, propose or issue (2026-10-26). Written when the
 * product is created or a version published, and changeable here by an ADMIN without publishing anything -- it changes
 * no price and no term. Separate from the customer portal's "What it is for", which customers read.
 */
export function ProductDescriptionField({
  productId,
  description,
  canEdit,
  onChanged,
}: {
  productId: string;
  description: string | null | undefined;
  canEdit: boolean;
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  async function save() {
    setSaving(true);
    setSaveError(null);
    try {
      await describeProduct(productId, text.trim());
      setEditing(false);
      onChanged();
    } catch (e) {
      setSaveError(toApiError(e));
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <div className="space-y-3 px-4 pt-1 pb-3">
        <FormField label="Description" hint="Who it is for and what it pays, in a sentence or two. Blank for none.">
          <Textarea className="min-h-20 py-2" maxLength={2000} value={text} onChange={(e) => setText(e.target.value)} />
        </FormField>
        {saveError && <InlineError error={saveError} lead="Not saved" />}
        <div className="flex gap-2">
          <Button variant="primary" size="sm" pending={saving} onClick={() => void save()}>Save</Button>
          <Button variant="ghost" size="sm" disabled={saving} onClick={() => setEditing(false)}>Cancel</Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-start gap-3 px-4 pt-1 pb-3">
      <p className={description ? 'flex-1 text-sm whitespace-pre-line' : 'flex-1 text-sm text-muted-foreground'}>
        {description || 'No description yet. Staff see it when choosing this product.'}
      </p>
      {canEdit && (
        <Button
          size="sm"
          variant="ghost"
          aria-label="Change description"
          onClick={() => {
            setText(description ?? '');
            setSaveError(null);
            setEditing(true);
          }}
        >
          {description ? 'Change' : 'Add'}
        </Button>
      )}
    </div>
  );
}
