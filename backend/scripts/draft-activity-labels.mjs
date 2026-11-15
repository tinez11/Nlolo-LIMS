// backend/scripts/draft-activity-labels.mjs -- usage: node scripts/draft-activity-labels.mjs > src/main/resources/audit/activity-labels.tsv
import fs from 'node:fs';
const dir = 'api/openapi/';
const KIND = { claims: 'claims', benefitpayout: 'claims', billing: 'money', payment: 'money', policyloan: 'money',
  finaccounting: 'money', accumulation: 'money', unitlinked: 'money', annuity: 'money', bonus: 'money',
  policy: 'policies', underwriting: 'policies', omnichannel: 'policies', party: 'people', iam: 'people',
  distribution: 'people', product: 'products', refdata: 'products', reinsurance: 'products' };
const PAST = { add: 'Added', approve: 'Approved', reject: 'Rejected', refuse: 'Refused', record: 'Recorded',
  create: 'Created', update: 'Updated', change: 'Changed', set: 'Set', delete: 'Deleted', remove: 'Removed',
  withdraw: 'Withdrew', cancel: 'Cancelled', register: 'Registered', request: 'Requested', submit: 'Submitted',
  decide: 'Decided', issue: 'Issued', propose: 'Proposed', publish: 'Published', confirm: 'Confirmed', pay: 'Paid',
  post: 'Posted', reverse: 'Reversed', upload: 'Uploaded', file: 'Filed', assign: 'Assigned', grant: 'Granted',
  revoke: 'Revoked', reset: 'Reset', send: 'Sent', resend: 'Resent', close: 'Closed', open: 'Opened', run: 'Ran',
  start: 'Started', stop: 'Stopped', end: 'Ended', mark: 'Marked', match: 'Matched', allocate: 'Allocated',
  waive: 'Waived', undo: 'Undid', correct: 'Corrected', amend: 'Amended', accept: 'Accepted', decline: 'Declined',
  activate: 'Activated', deactivate: 'Deactivated', suspend: 'Suspended', reinstate: 'Reinstated', lapse: 'Lapsed',
  surrender: 'Surrendered', import: 'Imported', price: 'Priced', quote: 'Quoted', review: 'Reviewed', sign: 'Signed',
  save: 'Saved', settle: 'Settled', assess: 'Assessed', invite: 'Invited', link: 'Linked', unlink: 'Unlinked',
  enrol: 'Enrolled', lock: 'Locked', unlock: 'Unlocked', vest: 'Vested', redirect: 'Redirected', switch: 'Switched',
  apply: 'Applied', generate: 'Generated', calculate: 'Calculated', move: 'Moved', return: 'Returned', give: 'Gave',
  take: 'Took', put: 'Put', make: 'Made', keep: 'Kept', note: 'Noted', replace: 'Replaced', release: 'Released',
  prepare: 'Prepared', attach: 'Attached', name: 'Named', author: 'Wrote', retire: 'Retired', resume: 'Resumed',
  reopen: 'Reopened', refer: 'Referred', rebuild: 'Rebuilt', reactivate: 'Reactivated', promote: 'Promoted',
  originate: 'Originated', onboard: 'Onboarded', offer: 'Offered', explain: 'Explained', draft: 'Drafted',
  describe: 'Described', collect: 'Collected', choose: 'Chose', try: 'Tried', reword: 'Reworded', ask: 'Asked',
  're-confirm': 'Re-confirmed' };
// Summaries that are not an order to do something: worded by hand from the summary's own words (2026-11-14).
const OVERRIDES = {
  'POST /agents/{agentId}/field-receipts/banked': 'Marked agent collections as paid in',
  'POST /balance-withdrawals/{withdrawalId}/approve': 'Approved a balance withdrawal',
  'POST /deposit-reversals/{reversalId}/confirm': 'Confirmed a deposit reversal',
  'POST /finance/periods/{period}/reopen-approval': 'Approved reopening a period',
  'POST /finance/share-register/issues/{issueId}/withdrawal': 'Withdrew a share issue',
  'POST /finance/share-register/issues/{issueId}/approval': 'Approved a share issue',
  'POST /finance/share-register/issues/{issueId}/rejection': 'Rejected a share issue',
  'POST /finance/accounting-policies/{electionId}/approval': 'Approved an accounting policy election',
  'POST /finance/accounting-policies/{electionId}/rejection': 'Rejected an accounting policy election',
  'POST /quotations/scheme/pdf': 'Made a group funeral scheme quotation',
  'POST /quotations/pdf': 'Made a quotation',
  'POST /agent-quotations': 'Recorded that the customer agreed to a quotation',
  'DELETE /agent-quotations/{quotationId}': 'Recorded that the customer did not go ahead',
  'POST /customer/products/{productId}/quote': 'Asked for a price',
  'POST /public/visits': 'Opened the public quote link',
  'POST /public/products/{productId}/quote': 'Asked for a price on the public quote link',
  'POST /public/phone-codes': 'Asked for a phone code',
  'POST /public/phone-codes/check': 'Checked a phone code',
  'POST /public/applications': 'Applied on the public quote link',
  'POST /organisation/preview': 'Previewed a sample receipt',
  'PUT /parties/{partyId}/language': "Changed a client's language",
  'POST /webhooks/mobile-money-callback': 'Mobile money result received',
  'POST /policies/manual-issue': 'Issued a policy by hand',
  'POST /policies/{policyNumber}/takeover': 'Spouse took over a funeral policy',
  'POST /group-schemes/{policyNumber}/families': 'Added a family to a group funeral scheme',
  'POST /group-schemes/{policyNumber}/funeral-joiners': "Added families from the association's file",
  'POST /group-schemes/{policyNumber}/members/{policyMemberId}/lives': "Added a life to a member's family",
  'POST /group-schemes/{policyNumber}/covered-lives/{coveredLifeId}/removal': "Removed a dependant from a member's family",
  'POST /group-schemes/{policyNumber}/members/{policyMemberId}/departure': 'Removed a member and their family',
  'POST /credit-life-schemes/{policyNumber}/exits/{submissionId}/acceptance': 'Approved credit life exits',
  'POST /credit-life-schemes/{policyNumber}/enrolments/{submissionId}/acceptance': 'Approved credit life enrolments',
  'POST /credit-life-schemes/{policyNumber}/enrolments/{submissionId}/withdrawal': 'Withdrew a credit life enrolment file',
  'POST /account-charges/{chargeId}/withdrawal': 'Stopped offering an account charge',
  'POST /account-charges/{chargeId}/reinstatement': 'Offered an account charge again',
  'PUT /reinsurance-statements/{statementId}': "Entered a reinsurer's statement",
  'POST /reinsurance-statements/{statementId}/withdrawal': "Sent a reinsurer's statement back to draft",
  'POST /underwriting/group-schedule/reading': 'Checked a group funeral schedule file',
  'PUT /underwriting/cases/{caseId}/terms': 'Amended the terms on a returned proposal',
  'PUT /underwriting/cases/{caseId}/applicant': "Corrected the client's details on a returned proposal",
  'POST /underwriting/cases/{caseId}/resubmit': 'Resubmitted a returned proposal',
};
Object.assign(OVERRIDES, {
  'POST /ifrs17/engine-runs/{id}/approval': 'Approved an IFRS 17 engine run',
  'POST /ifrs17/expense-allocations/{id}/approval': 'Approved an expense allocation',
  'POST /ifrs17/year-end-closes/{id}/approval': 'Approved the year-end close',
  'POST /reinsurance-statements/{statementId}/approval': "Approved a reinsurer's statement",
  'PUT /underwriting/cases/{caseId}/account-charges': 'Chose the account charges on a proposal',
  'POST /policies/{policyNumber}/units/payee': 'Named the payee of a maturity or lapse payout',
  'PUT /group-schemes/{policyNumber}/free-cover-limit': "Changed a scheme's free cover limit",
  'PUT /products/{productId}/description': "Changed a product's description",
  'POST /fund-prices/csv': 'Proposed fund prices from a CSV file',
  'POST /finance/unposted-events/{id}/dismissal': 'Asked to close a queued event without posting it',
});
const ACRONYMS = new Set(['KYC', 'PDF', 'CSV', 'TZS', 'SMS', 'IFRS', 'NAV', 'TIRA', 'USSD', 'OTP', 'EFT', 'CSM', 'LIC', 'LRC',
  'PAA', 'VAT', 'NIDA', 'TIN', 'API', 'PIN']);
const rows = [];
for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.yaml')).sort()) {
  const module = f.replace(/^openapi-|\.yaml$/g, '');
  const kind = KIND[module] ?? 'settings';
  const lines = fs.readFileSync(dir + f, 'utf8').split(/\r?\n/);
  let path = null;
  for (let i = 0; i < lines.length; i++) {
    const p = /^  (\/\S*):\s*$/.exec(lines[i]); if (p) { path = p[1]; continue; }
    const m = /^    (post|put|patch|delete):/.exec(lines[i]); if (!m || !path) continue;
    let summary = '';
    for (let j = i + 1; j < i + 8; j++) {
      const s = /^      summary:\s*(.+)$/.exec(lines[j] ?? '');
      if (!s) continue;
      summary = s[1].replace(/^['"]|['"]$/g, '');
      // A folded summary (`>-`) continues on the more-indented lines below it.
      if (/^[>|]-?$/.test(summary)) {
        const parts = [];
        for (let k = j + 1; /^        \S/.test(lines[k] ?? ''); k++) parts.push(lines[k].trim());
        summary = parts.join(' ');
      }
      break;
    }
    const clause = summary.split(/,| -- | — | \(|; |\. /)[0].trim().replace(/\.$/, '')
      // A status in capitals (DUE, ACTIVE, LOCKED) reads as a plain word; an acronym stays.
      .replace(/\b[A-Z]{3,}\b/g, (w) => (ACRONYMS.has(w) ? w : w.toLowerCase()))
      // "Record or change X" -> both verbs in the past.
      .replace(/\b(or|and) (\w+)\b/g, (all, joiner, verb) => (PAST[verb] ? `${joiner} ${PAST[verb].toLowerCase()}` : all));
    const [verb, ...rest] = clause.split(' ');
    const past = PAST[verb?.toLowerCase()];
    const fixed = OVERRIDES[`${m[1].toUpperCase()} ${path}`];
    const label = fixed ?? (past ? [past, ...rest].join(' ') : clause);
    rows.push([m[1].toUpperCase(), path, kind, label + (fixed || past ? '' : '\tCHECK')].join('\t'));
  }
}
console.log('# METHOD\tpattern\tkind\tlabel   -- drafted by scripts/draft-activity-labels.mjs, edited by hand, reviewed by the user');
for (const r of rows) console.log(r);
