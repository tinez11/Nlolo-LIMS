import type { DisclosureAnswer, RecordDisclosuresRequest } from '@/api/types';

/**
 * The capture form for `POST /underwriting/cases/{caseId}/disclosures`.
 *
 * Deliberately not a zod schema over the whole form, unlike `submitAssessmentForm`. The form
 * is a growable list of rows, and a half-typed row is a normal intermediate state rather than
 * an error to report -- so the rule is per row, and incomplete rows are dropped on save rather
 * than blocking it. What must never happen is a row reaching the wire with a code and an answer
 * but no question: the wording put to the applicant is the part a contested claim turns on, so
 * a row missing it looks like evidence and is not. Hence `isComplete`, which the panel also
 * uses to tell the user how many rows will actually be recorded.
 */

/** One editable row. Strings throughout -- this is form state, not the wire shape. */
export interface AnswerDraft {
  questionCode: string;
  question: string;
  answer: string;
  notes: string;
}

export function blankAnswer(): AnswerDraft {
  return { questionCode: '', question: '', answer: '', notes: '' };
}

/** Every part except `notes` is required, mirroring the request DTO's three `@NotBlank`s. */
export function isComplete(draft: AnswerDraft): boolean {
  return Boolean(draft.questionCode.trim() && draft.question.trim() && draft.answer.trim());
}

export function completeAnswers(drafts: AnswerDraft[]): AnswerDraft[] {
  return drafts.filter(isComplete);
}

export function toRecordDisclosuresRequest(drafts: AnswerDraft[]): RecordDisclosuresRequest {
  return {
    answers: completeAnswers(drafts).map((draft): DisclosureAnswer => ({
      questionCode: draft.questionCode.trim(),
      question: draft.question.trim(),
      answer: draft.answer.trim(),
      // Omitted rather than sent as '': the field is nullable, and an empty string would
      // record that a note was taken and was blank.
      ...(draft.notes.trim() ? { notes: draft.notes.trim() } : {}),
    })),
  };
}
