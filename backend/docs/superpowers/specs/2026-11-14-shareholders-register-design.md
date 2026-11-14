# Shareholders register — design

Date: 2026-11-14. Status: design agreed in conversation (option B), awaiting the user's review of this document.

## Why

The ledger has the equity accounts (3110 ordinary share capital, 3120 share premium) and a manual journal template
for issuing shares (M-01), but no record of **who** holds the shares. Dev books carry no share capital at all:
liabilities exceed assets by about TZS 7.8M and bank 1110 is TZS 1,600 overdrawn. A real insurer starts with paid-up
capital, must keep a register of members (Companies Act), must be at least one-third Tanzanian-owned to be
registered, and must hold at least TIRA's minimum paid-up capital.

## Scope

In: a register of shareholders and share issues; issuing shares posts the journal; three capital checks.
Out (option C, not now): share transfers, certificates, calls on partly paid shares, dividends per shareholder.

## Where it lives

- Backend: the `finaccounting` module (it owns the ledger and manual journals).
- Sidebar: **Ledger** group, a new item **Shareholders** directly below **Manual journals**.
- The checks also appear in **Reports > Capital adequacy (RBC)**.

## What the register holds

- **Shareholder**: name; kind (person or company); Tanzanian or foreign; ID or company registration number; phone;
  address.
- **Share class**: name (Ordinary; Preference only if the company has it), nominal value per share. Ordinary is
  created with the register; its nominal value is entered once by finance.
- **Share issue**: shareholder, class, number of shares, price per share, amount paid, date received, bank account
  (a 11xx posting account), status PROPOSED / APPROVED / REJECTED, who proposed, who decided, reason.

Holdings are the sum of a shareholder's approved issues. Nothing is edited after approval; a mistake is corrected by
a manual journal and a note, as for any posted journal.

## Issuing shares

1. A finance officer records an issue (PROPOSED). Nothing posts.
2. A finance approver approves or rejects it with a reason. The proposer may approve their own only with
   `ledger.approve-own` (the platform's OwnWork rule). The proposer may withdraw it while PROPOSED.
3. On approval, one journal posts, dated the day the money was received (finding 89's rule):
   - Dr the chosen bank account: amount paid
   - Cr 3110 Ordinary share capital (3130 for preference): shares × nominal value
   - Cr 3120 Share premium: amount paid − shares × nominal value
   Price below nominal value is refused (shares may not be issued at a discount). Amount paid must equal shares ×
   price (fully paid only).
4. If the day's month is closed, approval is refused with "That month is closed" (no silent re-dating).
5. Waiting issues appear in **Approvals** as kind "share-issue".

## Permissions

The existing ledger permissions, no new ones: view `ledger.view`; record `ledger.manage`; approve or reject
`ledger.approve`; approve own `ledger.approve-own`. Finance officer and finance approver already hold the right ones.

## The three checks

Shown as a strip at the top of the Shareholders page and as a section in the capital adequacy report:

| Check | Met when | Shows |
|---|---|---|
| Register agrees with the books | approved issues' share capital + premium = ledger 3110 + 3120 + 3130 balance | the difference if not |
| Tanzanian ownership | Tanzanian shareholders hold ≥ one-third of all issued shares | the percentage held |
| Minimum paid-up capital | paid-up capital (3110 + 3120 + 3130) ≥ the minimum | "Not set" until finance enters the minimum |

The minimum paid-up capital is one money setting on the Shareholders page (`ledger.manage`, audited). It starts
empty; the platform does not guess TIRA's figure.

## Dev data

The dev seeder adds one approved issue: "Demo Holdings Ltd" (company, Tanzanian), 1,000,000 ordinary shares at
nominal and price TZS 1,000, into bank 1110. Applied to the current dev database by hand as well.

## Testing

- Unit: the posting split (nominal vs premium), discount refused, Tanzanian share and the three checks.
- Integration: propose → approve posts the journal on the received date; own approval refused without
  `ledger.approve-own`; closed month refused; register total equals the ledger after approval.
- Frontend: page renders the register and checks; "Not set" for an empty minimum; approval in the Approvals queue.
- E2E: one spec — record an issue as finance officer, approve as finance approver, see the checks met.
