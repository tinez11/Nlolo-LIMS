import { Plus, X } from 'lucide-react';
import { useState } from 'react';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { Select, Textarea } from '@/components/ui/input';
import {
  RETURN_CATEGORY_LABELS,
  returnForAmendment,
  type ReturnCategory,
  type ReturnReason,
} from '@/api/proposalAmendments';
import { toApiError, type ApiError } from '@/lib/apiError';

const CATEGORIES = Object.keys(RETURN_CATEGORY_LABELS) as ReturnCategory[];
const blank = (): ReturnReason => ({ category: 'PERSONAL_DETAILS', problem: '', change: '' });

/**
 * Send an undecided proposal back to its agent for amendment (2026-10-20): one or more reasons, each a category and
 * the underwriter's own words for what is wrong and what to change -- the agent reads exactly these. The server
 * refuses a reason missing either text; the button waits for them so the refusal is never the first the underwriter
 * hears of it.
 */
export function ReturnToAgentPanel({ caseId, onReturned }: { caseId: string; onReturned: () => void }) {
  const [reasons, setReasons] = useState<ReturnReason[]>([blank()]);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const complete = reasons.every((r) => r.problem.trim() && r.change.trim());
  const set = (i: number, patch: Partial<ReturnReason>) =>
    setReasons((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await returnForAmendment(caseId, reasons, note.trim() || null);
      onReturned();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel
      title="Return to the agent"
      subtitle="Send the proposal back for corrections. It cannot be decided until the agent resubmits it."
    >
      <div className="space-y-4 px-4 pb-4">
        {reasons.map((r, i) => (
          <fieldset key={i} className="space-y-2 rounded-md border border-border p-3">
            <legend className="px-1 text-xs font-medium text-muted-foreground">Reason {i + 1}</legend>
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <FormField label="What it concerns">
                  <Select value={r.category} onChange={(e) => set(i, { category: e.target.value as ReturnCategory })}>
                    {CATEGORIES.map((c) => (
                      <option key={c} value={c}>
                        {RETURN_CATEGORY_LABELS[c]}
                      </option>
                    ))}
                  </Select>
                </FormField>
              </div>
              {reasons.length > 1 && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={`Remove reason ${i + 1}`}
                  onClick={() => setReasons((rs) => rs.filter((_, j) => j !== i))}
                >
                  <X />
                </Button>
              )}
            </div>
            <FormField label="What is wrong">
              <Textarea className="min-h-16" value={r.problem} onChange={(e) => set(i, { problem: e.target.value })} />
            </FormField>
            <FormField label="What to change">
              <Textarea className="min-h-16" value={r.change} onChange={(e) => set(i, { change: e.target.value })} />
            </FormField>
          </fieldset>
        ))}
        <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => setReasons((rs) => [...rs, blank()])}>
          <Plus />
          Add a reason
        </Button>
        <FormField label="Note to the agent (optional)">
          <Textarea className="min-h-12" value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>
        {error && <InlineError error={error} />}
        <Button type="button" variant="outline" disabled={!complete} pending={busy} onClick={() => void submit()}>
          Return to the agent
        </Button>
      </div>
    </Panel>
  );
}
