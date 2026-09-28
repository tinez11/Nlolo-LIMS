import { Plus, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/InlineError';
import { ErrorPanel } from '@/components/states';
import { FormField } from '@/components/FormField';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectDisclosures,
  selectRecordingDisclosures,
  useUnderwritingStore,
} from '@/store/underwritingStore';
import {
  blankAnswer,
  completeAnswers,
  toRecordDisclosuresRequest,
  type AnswerDraft,
} from './disclosureForm';
import { Input } from '@/components/ui/input';

/**
 * What an applicant declared on the proposal form, and the form for recording it.
 *
 * <p>Deliberately NOT a fixed questionnaire. Which questions a proposal asks is
 * product-specific and shaped by what the regulator and reinsurer require, and this platform
 * has no such list — so it records the question AS ASKED rather than offering a canonical set
 * it would be inventing. That is also why the question text is captured and stored rather than
 * looked up: contesting a claim turns on the wording put to the person at the time, not on
 * whatever the current version of the form says.
 *
 * Recording is open to agents as well as staff, unlike assessment: the person sitting with the
 * customer is the one who asks and writes down the answers.
 */
export function DisclosurePanel({ caseId }: { caseId: string }) {
  const disclosures = useUnderwritingStore(selectDisclosures(caseId));
  const recording = useUnderwritingStore(selectRecordingDisclosures(caseId));
  const loadDisclosures = useUnderwritingStore((s) => s.loadDisclosures);
  const recordDisclosures = useUnderwritingStore((s) => s.recordDisclosures);
  const resetRecordDisclosures = useUnderwritingStore((s) => s.resetRecordDisclosures);

  const [drafts, setDrafts] = useState<AnswerDraft[]>([]);

  useEffect(() => {
    void loadDisclosures(caseId);
    // `recording` is keyed by caseId and outlives this component's mount, so a rejection from
    // a previous visit would resurface on arrival -- the same bug found live on beneficiaries,
    // claims and policy issuance.
    resetRecordDisclosures(caseId);
  }, [caseId, loadDisclosures, resetRecordDisclosures]);

  const sets = disclosures.data ?? [];
  const complete = completeAnswers(drafts);

  async function save() {
    await recordDisclosures(caseId, toRecordDisclosuresRequest(drafts));
    if (useUnderwritingStore.getState().recordingDisclosures[caseId]?.status === 'success') {
      setDrafts([]);
    }
  }

  return (
    <div className="space-y-4 p-4">
      {isInitialLoad(disclosures) && <p className="text-xs text-muted-foreground">Loading…</p>}

      {disclosures.status === 'error' && disclosures.error && disclosures.data === null && (
        <ErrorPanel error={disclosures.error} onRetry={() => void loadDisclosures(caseId)} />
      )}

      {disclosures.status === 'success' && sets.length === 0 && (
        <p className="text-xs text-muted-foreground">
          Nothing declared on this case. That is not the same as “declared nothing” — it means no
          proposal answers were ever recorded here.
        </p>
      )}

      {sets.map((set) => (
        // A section with a name, not a bare div: each set is one self-contained piece of
        // evidence, and a reader (or a screen reader) needs to tell where one recording ends
        // and the next begins -- a correction is a second set, not an edit of the first.
        <section
          key={set.medicalDisclosureId}
          aria-label={`Declarations recorded ${formatInstant(set.recordedAt)}`}
          className="rounded-md border border-border"
        >
          <p className="border-b border-border px-3 py-1.5 text-xs text-muted-foreground">
            Recorded {formatInstant(set.recordedAt)}
            {set.recordedBy && <> by <span className="font-mono">{set.recordedBy}</span></>}
          </p>
          <dl className="divide-y divide-border">
            {(set.answers ?? []).map((a, i) => (
              <div key={`${set.medicalDisclosureId}-${i}`} className="px-3 py-2">
                <dt className="text-xs">
                  <span className="mr-2 font-mono text-xs text-muted-foreground">{a.questionCode}</span>
                  {a.question}
                </dt>
                <dd className="mt-0.5 text-xs font-medium">{a.answer}</dd>
                {a.notes && <dd className="mt-0.5 text-xs text-muted-foreground">{a.notes}</dd>}
              </div>
            ))}
          </dl>
        </section>
      ))}

      {/* A later set never replaces an earlier one -- a correction is additional evidence, not
          an edit -- so this stays available even after a set exists, and even after a decision:
          a non-disclosure usually surfaces when a claim is made, long after the case closed. */}
      <div className="space-y-2">
        {drafts.map((draft, index) => (
          <div key={index} className="space-y-2 rounded-md border border-border p-3">
            <div className="flex items-start gap-2">
              <FormField label="Question code">
                <Input
                  inputSize="sm" className="w-28 font-mono"
                  placeholder="Q1"
                  value={draft.questionCode}
                  onChange={(e) =>
                    setDrafts((rows) =>
                      rows.map((r, i) => (i === index ? { ...r, questionCode: e.target.value } : r)),
                    )
                  }
                />
              </FormField>
              <div className="flex-1">
                <FormField label="Question as asked">
                  <Input
                    inputSize="sm"
                    placeholder="Have you ever been treated for heart disease?"
                    value={draft.question}
                    onChange={(e) =>
                      setDrafts((rows) =>
                        rows.map((r, i) => (i === index ? { ...r, question: e.target.value } : r)),
                      )
                    }
                  />
                </FormField>
              </div>
              <Button
                type="button"
                size="icon"
                variant="ghost"
                aria-label="Remove declaration"
                onClick={() => setDrafts((rows) => rows.filter((_, i) => i !== index))}
              >
                <X />
              </Button>
            </div>
            <FormField label="Answer">
              <Input
                inputSize="sm"
                placeholder="No — or the answer in the applicant's own terms"
                value={draft.answer}
                onChange={(e) =>
                  setDrafts((rows) => rows.map((r, i) => (i === index ? { ...r, answer: e.target.value } : r)))
                }
              />
            </FormField>
            <FormField label="Notes (optional)">
              <Input
                inputSize="sm"
                value={draft.notes}
                onChange={(e) =>
                  setDrafts((rows) => rows.map((r, i) => (i === index ? { ...r, notes: e.target.value } : r)))
                }
              />
            </FormField>
          </div>
        ))}

        {recording.status === 'error' && recording.error && (
          <InlineError error={recording.error} />
        )}

        <div className="flex items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => setDrafts((rows) => [...rows, blankAnswer()])}
          >
            <Plus />
            Add a declaration
          </Button>
          {drafts.length > 0 && (
            <Button
              type="button"
              size="sm"
              variant="primary"
              // Every part except notes is required, so a half-filled row cannot be saved: a
              // recorded question with no wording, or an answer with no question, looks like
              // evidence and is not.
              pending={recording.status === 'loading'}
              disabled={complete.length === 0}
              onClick={() => void save()}
            >
              {/* The count stays in the label, because it is the one thing that tells somebody
                  how much of the form the platform thinks is finished. It is not a tense, so
                  `pending` does not touch it. */}
              {`Record ${complete.length} declaration(s)`}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
