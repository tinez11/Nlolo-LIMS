import type { CallOutcome, FollowUpAction, RecordCallBody } from '@/api/followUp';
import { formatDate, formatInstantDay } from '@/lib/dates';

/** What happened on a call, in the words a person says it (2026-11-11, the decided design). */
export const CALL_OUTCOMES: { value: CallOutcome; label: string }[] = [
  { value: 'WILL_PAY', label: 'Spoke — will pay' },
  { value: 'CANNOT_PAY_NOW', label: 'Spoke — cannot pay now' },
  { value: 'NO_ANSWER', label: 'No answer' },
  { value: 'WRONG_NUMBER', label: 'Wrong number' },
];

export const NOTE_MAX = 500;

export interface CallFormValues {
  outcome: CallOutcome | '';
  promisedOn: string | null;
  note: string;
}

export const blankCall = (): CallFormValues => ({ outcome: '', promisedOn: null, note: '' });

/** Why the form cannot be saved yet, in words; null when it can. The server checks the same again. */
export function callProblem(values: CallFormValues): string | null {
  if (!values.outcome) return 'Say what happened on the call.';
  if (values.note.length > NOTE_MAX) return `Keep the note to ${NOTE_MAX} characters.`;
  return null;
}

/** The body the server takes. A promised date goes only with "will pay" -- the server refuses it on any other. */
export function toBody(invoiceId: string, values: CallFormValues): RecordCallBody {
  const outcome = values.outcome as CallOutcome;
  const note = values.note.trim();
  return {
    invoiceId,
    outcome,
    promisedOn: outcome === 'WILL_PAY' ? values.promisedOn : null,
    note: note === '' ? null : note,
  };
}

/** One thing done, as the list says it: "Called 8 Nov: promised to pay 12 Nov", "Reminder sent 3 Nov". */
export function actionInWords(action: FollowUpAction): string {
  const on = formatInstantDay(action.recordedAt);
  switch (action.kind) {
    case 'CALL': {
      const outcome = CALL_OUTCOMES.find((o) => o.value === action.outcome)?.label ?? 'Called';
      return action.promisedOn
        ? `Called ${on}: promised to pay ${formatDate(action.promisedOn)}`
        : `Called ${on}: ${outcome.replace('Spoke — ', '')}`;
    }
    case 'REMINDER':
      return `Reminder sent ${on}`;
    case 'PAYMENT_REQUEST':
      return `Asked to pay by phone ${on}`;
    default:
      return on;
  }
}
