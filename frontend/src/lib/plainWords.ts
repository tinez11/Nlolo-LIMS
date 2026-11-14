/**
 * A server's refusal, in words a person reads (2026-11-14, the user: "users are going to use [this], not software
 * engineers"). The server's messages are written for the record and still name things by id and state by enum --
 * "Claim 7f3e… is SETTLED; reopen it…", "Loan … must be DISBURSEMENT_REQUESTED (current: PAYOUT_REFUSED)". Applied once,
 * where every error is read from the server (`toApiError`), so every screen shows the same plain wording and a message
 * written tomorrow is covered too.
 *
 *  - "<thing> <uuid>" becomes "this <thing>", for the things the platform names that way; any other uuid is dropped.
 *  - "(current: X)" becomes ": it is x".
 *  - A state written as an enum -- AWAITING_PAYOUT_APPROVAL, SETTLED -- becomes words. Acronyms (TZS, KYC, IFRS…),
 *    policy numbers and references with digits are left alone.
 */

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';

/** The things a refusal names by id, longest first so "underwriting case" wins over "case". */
const THINGS = [
  'free-look cancellation', 'surrender request', 'underwriting case', 'agent of record', 'product version',
  'payment run', 'covered life', 'scheme member', 'withholding rule', 'rate declaration', 'adjustment request',
  'claim', 'loan', 'payout', 'stream', 'order', 'switch', 'withdrawal', 'adjustment', 'member', 'reservation',
  'election', 'case', 'agent', 'party', 'product', 'file', 'submission', 'disbursement', 'payment', 'invoice',
  'document', 'price', 'policy', 'journal', 'run', 'statement', 'treaty', 'fund', 'deposit', 'receipt', 'quotation',
  'request', 'reversal', 'cancellation', 'enrolment', 'client', 'account',
];

/** All-capital states the platform writes without an underscore. Anything with an underscore is a state too. */
const STATES = new Set([
  'ACTIVE', 'APPROVED', 'ACCEPTED', 'CANCELLED', 'CLOSED', 'CLOSING', 'COMPLETED', 'CONFIRMED', 'DECLINED', 'DISBURSED',
  'DRAFT', 'DUE', 'ENDED', 'EXPIRED', 'FAILED', 'HELD', 'ISSUED', 'LAPSED', 'LOCKED', 'MATCHED', 'MATURED', 'OPEN',
  'ORIGINATED', 'OVERDUE', 'PAID', 'PENDING', 'POSTED', 'POSTPONED', 'PRICED', 'PROPOSED', 'QUEUED', 'RECEIVED',
  'REFERRED', 'REGISTERED', 'REJECTED', 'REOPENED', 'REPAYING', 'RESERVED', 'RETIRED', 'REVERSED', 'SENT', 'SETTLED',
  'SUBMITTED', 'SURRENDERED', 'SUSPENDED', 'TERMINATED', 'VALIDATED', 'WAIVED', 'WITHDRAWN', 'ACCEPT', 'DECLINE',
]);

const thingPattern = new RegExp(`\\b(${THINGS.map((t) => t.replace(/[-]/g, '\\-')).join('|')})\\s+${UUID}`, 'gi');

function lowerState(token: string): string {
  return token.toLowerCase().replace(/_/g, ' ');
}

export function plainWords(message: string | null | undefined): string | null {
  if (message == null) return null;
  let text = message;

  // "Claim <uuid> is" -> "This claim is"; "not on claim <uuid>" -> "not on this claim".
  text = text.replace(thingPattern, (_match, thing: string, offset: number, whole: string) => {
    const before = whole.slice(0, offset).trimEnd();
    const startsSentence = before === '' || /[.;:!?(]$/.test(before);
    const noun = thing.toLowerCase();
    return startsSentence ? `This ${noun}` : `this ${noun}`;
  });
  // Any other id: dropped, with the space before it.
  text = text.replace(new RegExp(`\\s*${UUID}`, 'gi'), '');

  // "(current: PAYOUT_REFUSED)" -> ": it is payout refused".
  text = text.replace(/\s*\(current:\s*([A-Z][A-Z0-9_]*)\)/g, (_m, state: string) => `: it is ${lowerState(state)}`);

  // States as words: anything with an underscore, and the known single-word states.
  text = text.replace(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g, (token) => (/\d/.test(token) ? token : lowerState(token)));
  text = text.replace(/\b[A-Z]{3,}\b/g, (token) => (STATES.has(token) ? token.toLowerCase() : token));

  // The double spaces and "this this" a removal can leave.
  return text.replace(/\bthis this\b/gi, 'this').replace(/ {2,}/g, ' ').replace(/\s+([,.;:])/g, '$1').trim();
}
