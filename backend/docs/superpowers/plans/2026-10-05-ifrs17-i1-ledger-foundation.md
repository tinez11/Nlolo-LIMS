# IFRS 17 — I1 Ledger Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan is executed INLINE (user's choice: no subagents).**

**Goal:** Replace the placeholder 36-account ledger with the guide's IFRS 17 chart, give every journal the guide's header and line dimensions, enforce the ledger's invariants in the database, add accounting periods (open → closing → locked) and the effective-dated accounting policy register — while every existing posting keeps working.

**Architecture:** All work is in `finaccounting` (plus test migration lists, the console's finance screens and the e2e spec). The guide's chart lives in one CSV resource read by `ChartOfAccountBlueprint`; migration `finaccounting/V10` clears the development ledger, widens the schema and installs trigger guards. Existing posting code is re-pointed to the guide's accounts by an **interim remap** of `PostingRule`'s constants (I3 replaces the whole rule mechanism later). Periods and the policy register are two small services with their own tables, controllers and console pages.

**Tech Stack:** Spring Boot 3 / Spring Modulith, JPA/Hibernate, Postgres 16 (Testcontainers in tests), React + TypeScript + zod + react-hook-form, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-05-ifrs17-design.md` (commit cbc02796), §2 (D2, D7), §3, §5.

## Global Constraints

- Account codes, names, types, normal balances and modes are the guide's (2.5), exactly. No account is invented.
- The development ledger starts clean (D2): `journal_entry`, `gl_posting` and `chart_of_account` rows are deleted; no other table's data is touched.
- Mode table (spec §5.3, as amended in Task 0): AUTO accepts EVENT, SYSTEM, ENGINE_RUN; MAN accepts SYSTEM, ENGINE_RUN, MANUAL; BOTH accepts all (MANUAL with a reason code).
- Every journal records the accounting policy register version in force when it was posted (D7).
- Posted journals and lines are never updated or deleted; corrections are reversals.
- Elections are never edited; a change is a new dated version, proposer ≠ approver, with a sign-off reference, effective from today or later (prospective only).
- Reopening a locked period needs a proposer and a different approver with a reason.
- Every scheduled job or listener that posts must keep working through the interim remap: existing tests keep their meaning with the new codes.
- Never run Prettier; never run Maven in Docker; stop the dev backend before `clean`; run affected classes by name (user preference); full e2e at the gate.

## Corrections against the spec (decided while reading the code)

- **R1 — SYSTEM may post to MAN accounts.** The spec's mode table refused SYSTEM on MAN accounts, but the year-end closing run (M-07/M-11, spec §10) and the auto-reversal of an approved manual journal (spec §8) are SYSTEM runs that must touch MAN accounts (3210, 4xxx–8xxx). Task 0 amends the spec's table.
- **R2 — An interim remap is part of I1.** The spec gives I3 the job of re-mapping events, but replacing the chart in I1 removes every code `PostingRule` posts to (1120, 1210, 2140, 2150, 4310, 5100, 5600 …) and changes the meaning of others (2110 Claims Payable → LRC PVFCF). Without a remap every event posting would fail its foreign key or land on the wrong account. Task 5 re-points the existing constants to the nearest guide accounts allowed for EVENT postings (AUTO/BOTH); I3 replaces the mechanism.
- **R3 — Accounts the guide nests under a heading whose code does not share its prefix** (5300–5600 under 5200; 8210–8490 under 8100; 8800's children) hang off their **class root**, because `ChartOfAccount.childOf` requires a child's code to begin with its parent's significant prefix. Level-skipping is already allowed (V5). The console groups by code range, so nothing is lost.
- **R4 — Account types.** The guide has contra and clearing accounts the leading-digit rule cannot express. `AccountType` gains `CLEARING`; contra accounts keep their parent's type with the opposite normal balance (e.g. 2122 is LIABILITY / DR). Seeded accounts take type and normal balance from the CSV; only API-created accounts derive them, from their parent (or a class map for a root).
- **R5 — The balance guard is a deferred constraint trigger on `journal_entry`** (non-partitioned), checking at commit that the entry's lines balance; a BEFORE INSERT trigger on `gl_posting` refuses a line whose journal was not created in the same transaction, so lines cannot be appended to an older balanced journal.
- **R6 — `csm_ledger`, `lrc_ledger`, `lic_ledger` (M1, unused) are dropped** in V10 (spec §5.6). `group_of_contracts` stays for I2.

## File structure

**Backend (finaccounting)**
- Create `src/main/resources/finaccounting/ifrs17-chart.csv` — the guide's chart, one row per account.
- Modify `domain/ChartOfAccountBlueprint.java` — loads the CSV; `Seed` gains type, normal balance, mode.
- Modify `domain/ChartOfAccount.java` — `mode` column; factories take explicit type/normal/mode; API path inherits from parent.
- Modify `api/AccountType.java` (+ `CLEARING`), create `api/PostingMode.java`, `api/JournalSource.java`, `api/PeriodStatus.java`.
- Modify `infrastructure/ChartOfAccountSeeder.java` — seeds explicit values; also seeds the policy register baseline.
- Modify `domain/JournalEntry.java`, `domain/GlPosting.java` — header and line dimension columns.
- Modify `application/FinaccountingApiImpl.java` — records the register version and source; account-code pattern `^[1-9]\d{3}$`.
- Modify `domain/PostingRule.java`, `application/UnitLinkedEventListener.java`, `infrastructure/UnitLinkedReconciliationController.java` — interim remap.
- Create `domain/AccountingPeriod.java`, `infrastructure/AccountingPeriodRepository.java`, `application/AccountingPeriods.java`, `infrastructure/AccountingPeriodController.java`, `api/AccountingPeriodView.java`.
- Create `domain/PolicyElection.java`, `infrastructure/PolicyElectionRepository.java`, `application/PolicyRegister.java`, `application/PolicyRegisterBaseline.java`, `infrastructure/PolicyRegisterController.java`, `api/PolicyElectionView.java`, `api/PolicyElectionInput.java`.
- Modify `api/FinaccountingApi.java` (period + register methods), `infrastructure/FinaccountingExceptionHandler.java`.
- Create `db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql`.
- Modify `api/openapi/openapi-finaccounting.yaml`; `scripts/migrate.sh` and `.github/workflows/ci-cd.yml` only if they list finaccounting migrations explicitly (check in Task 2).

**Backend tests**
- Create `finaccounting/Ifrs17ChartTest.java`, `finaccounting/LedgerGuardsIntegrationTest.java`, `finaccounting/AccountingPeriodIntegrationTest.java`, `finaccounting/PolicyRegisterIntegrationTest.java`.
- Modify every test migration list that contains `finaccounting/V5__chart_of_account_hierarchy.sql` (append V10).
- Modify the code-coupled tests: `ChartOfAccountBlueprintTest`, `ChartOfAccountMigrationV5Test` (frozen legacy list), `ChartOfAccountTest`, `JournalEntryBalanceTest`, `FinaccountingContractTest`, `FinaccountingApiIntegrationTest`, `EftDisbursementIntegrationTest`, `WithholdingIntegrationTest`, `UnitLinkedAccountingIntegrationTest`, `UnitLinkedU2AccountingIntegrationTest`, `PremiumPostingEndToEndTest`, `ClaimAndCommissionPostingEndToEndTest`, `ReinsuranceAndLoanPostingEndToEndTest`, `PayoutPaymentEndToEndTest`, `FuneralEndorsementIntegrationTest`, `MainMemberDeathIntegrationTest`, `AppRolePrivilegesIntegrationTest`, `RowLevelSecurityIntegrationTest` — only where they name an old code or depend on the old chart.

**Frontend**
- Modify `src/api/finaccounting.ts`, `src/api/types.ts`, the chart of accounts screen (type, normal balance, mode columns), create `src/features/finance/PeriodsPage.tsx`, `src/features/finance/PolicyRegisterPage.tsx`, `src/features/finance/registerForms.ts` (+ test), routes and nav entries.
- Modify `e2e/staff-finaccounting.spec.ts` (guide names/codes); create `e2e/staff-ifrs17-foundation.spec.ts`.

---

### Task 0: Branch and spec amendment

- [ ] **Step 1:** Work in `.worktrees/ifrs17` (branch `ifrs17`, from main a687ef2b; already created with the spec).
- [ ] **Step 2:** In the spec §5.3 mode table, change the MAN row's SYSTEM cell from `no` to `yes`, and add under the table: "SYSTEM is the platform's own approved runs only (PAA earning, auto-reversal of an approved journal, the year-end closing run); it may post to every mode."
- [ ] **Step 3:** Commit: `docs(spec): SYSTEM runs may post to MAN accounts (year-end close, auto-reversals)`.

---

### Task 1: The guide's chart as data

**Files:**
- Create: `src/main/resources/finaccounting/ifrs17-chart.csv`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ChartOfAccountBlueprint.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/AccountType.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/PostingMode.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/Ifrs17ChartTest.java`

**Interfaces — Produces:** `ChartOfAccountBlueprint.accounts() -> List<Seed>`, `record Seed(String code, String name, String parentCode, AccountType type, PostingDirection normalBalance, PostingMode mode, boolean postingAllowed, String usedFor)`; `PostingMode { AUTO, MAN, BOTH }`; `AccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE, CLEARING }`; `ChartOfAccountBlueprint.SEED_CURRENCY = "TZS"`.

- [ ] **Step 1: Write the CSV.** `;`-separated, header line, UTF-8. Columns: `code;name;parent;type;normal;mode;posting;used_for`. `posting` is `Y` for a posting account and `N` for a heading. Headings carry the type and normal balance of their block and mode `MAN`. Parents follow R3.

```
code;name;parent;type;normal;mode;posting;used_for
1000;Assets;;ASSET;DR;MAN;N;What the company owns or is owed
1100;Cash and bank;1000;ASSET;DR;MAN;N;
1110;Main operating bank account;1100;ASSET;DR;BOTH;Y;General receipts and payments.
1120;Premium collection bank account;1100;ASSET;DR;AUTO;Y;Premiums by bank transfer, standing order, cheque.
1130;Claims and benefits payment bank account;1100;ASSET;DR;AUTO;Y;Claims, maturities, surrenders, annuities paid out.
1140;Mobile money wallets (M-Pesa, Tigo Pesa, Airtel Money, HaloPesa);1100;ASSET;DR;AUTO;Y;Premiums collected by mobile money, after settlement.
1150;Petty cash;1100;ASSET;DR;MAN;Y;
1160;Unit fund bank accounts (underlying items);1100;ASSET;DR;AUTO;Y;Cash inside unit-linked funds.
1200;Financial investments (IFRS 9);1000;ASSET;DR;MAN;N;
1210;Treasury bills – amortised cost;1200;ASSET;DR;MAN;Y;Held to collect contractual cash flows.
1215;Treasury bonds – amortised cost;1200;ASSET;DR;MAN;Y;
1216;Treasury bonds – fair value through OCI (FVOCI);1200;ASSET;DR;MAN;Y;Held to collect and sell; often used to match insurance finance OCI option.
1220;Fixed and term deposits – amortised cost;1200;ASSET;DR;MAN;Y;
1230;Listed equities – fair value through profit or loss (FVTPL);1200;ASSET;DR;MAN;Y;
1231;Listed equities – FVOCI (irrevocable election);1200;ASSET;DR;MAN;Y;Gains never recycled to profit or loss.
1240;Corporate bonds – amortised cost / FVOCI;1200;ASSET;DR;MAN;Y;Use one sub-account per IFRS 9 category.
1250;Collective investment schemes – FVTPL;1200;ASSET;DR;MAN;Y;
1260;Investment property (IAS 40);1200;ASSET;DR;MAN;Y;
1270;Underlying items – unit-linked and participating fund assets (FVTPL);1200;ASSET;DR;AUTO;Y;Assets backing VFA contracts and unit-linked investment contracts.
1275;Funds placed with external fund manager (not yet invested);1200;ASSET;DR;MAN;Y;
1280;Accrued investment income;1200;ASSET;DR;BOTH;Y;
1290;Expected credit loss (ECL) allowance – investments;1200;ASSET;CR;MAN;Y;IFRS 9 impairment on amortised cost investments.
1300;Receivables outside IFRS 17 (IFRS 9);1000;ASSET;DR;MAN;N;
1320;Due from agents and brokers (premiums they collected);1300;ASSET;DR;AUTO;Y;Once the policyholder has paid the intermediary, the debt is the intermediary's, not the policyholder's.
1330;Due from employers – check-off collections on individual policies;1300;ASSET;DR;AUTO;Y;Employer deducted premiums from salaries and must remit them.
1370;Commission clawback receivable from agents;1300;ASSET;DR;AUTO;Y;
1375;Agent advances and loans;1300;ASSET;DR;MAN;Y;
1380;Staff and other receivables;1300;ASSET;DR;MAN;Y;
1385;Called-up share capital not paid (calls in arrears);1300;ASSET;DR;MAN;Y;
1390;ECL allowance – receivables;1300;ASSET;CR;MAN;Y;
1400;Reinsurance contract assets (reinsurance held – IFRS 17);1000;ASSET;DR;MAN;N;
1410;Asset for remaining coverage – PV of future cash flows;1400;ASSET;DR;MAN;Y;From the IFRS 17 engine.
1411;Asset for remaining coverage – risk adjustment;1400;ASSET;DR;MAN;Y;Risk transferred to the reinsurer.
1412;Asset for remaining coverage – CSM (net cost or net gain of reinsurance);1400;ASSET;DR;MAN;Y;Can be a debit or credit balance.
1413;Loss-recovery component;1400;ASSET;DR;MAN;Y;Recovery of losses on onerous underlying contracts.
1420;Asset for incurred claims – recoveries on claims incurred;1400;ASSET;DR;AUTO;Y;Reinsurer's share of claims already incurred.
1421;Asset for incurred claims – risk adjustment and PV adjustment;1400;ASSET;DR;MAN;Y;
1430;Reinsurance premiums payable (operational);1400;ASSET;CR;AUTO;Y;Credit sub-account inside reinsurance contract balances.
1431;Reinsurance commission receivable – not contingent on claims;1400;ASSET;DR;AUTO;Y;Treated as a reduction of reinsurance premium (IFRS 17 para 86).
1433;Reinsurance profit commission receivable – contingent on claims;1400;ASSET;DR;MAN;Y;Treated as part of amounts recovered.
1434;Reinsurance current account (agreed statement balance);1400;ASSET;DR;MAN;Y;
1436;Reinsurance premiums ceded – actual cash flows (cleared at period end);1400;ASSET;DR;AUTO;Y;Collects the period's ceded premiums net of non-contingent commission.
1490;Presentation reclass – reinsurance portfolios in liability position;1400;ASSET;DR;MAN;Y;Auto-reversing reporting-date reclass to 2540.
1500;Insurance contract assets (presentation only);1000;ASSET;DR;MAN;N;
1510;Insurance contract assets – portfolios in net asset position;1500;ASSET;DR;MAN;Y;Reporting-date reclass (auto-reversing) from 2190.
1600;Other assets;1000;ASSET;DR;MAN;N;
1610;Prepayments;1600;ASSET;DR;MAN;Y;
1620;Insurance acquisition cash flows asset (pre-recognition);1600;ASSET;DR;AUTO;Y;Commission paid before the group of contracts is recognised (para 28B). Moves into 2123 on recognition.
1621;Impairment allowance – acquisition cash flows asset;1600;ASSET;CR;MAN;Y;Para 28E impairment test.
1630;Withholding tax recoverable;1600;ASSET;DR;BOTH;Y;
1640;Corporate tax paid in advance (provisional tax);1600;ASSET;DR;MAN;Y;
1650;Deferred tax asset;1600;ASSET;DR;MAN;Y;
1700;Property, equipment, leases and intangibles;1000;ASSET;DR;MAN;N;
1710;Land and buildings (own use);1700;ASSET;DR;MAN;Y;
1715;Right-of-use assets (IFRS 16 leases);1700;ASSET;DR;MAN;Y;Leased offices and branches.
1720;Motor vehicles;1700;ASSET;DR;MAN;Y;
1730;Furniture and fittings;1700;ASSET;DR;MAN;Y;
1740;Computers and IT equipment;1700;ASSET;DR;MAN;Y;
1750;Software and intangible assets;1700;ASSET;DR;MAN;Y;
1790;Accumulated depreciation and amortisation;1700;ASSET;CR;BOTH;Y;
1800;Statutory deposit;1000;ASSET;DR;MAN;N;
1810;Statutory deposit with the regulator (TIRA);1800;ASSET;DR;MAN;Y;
2000;Liabilities;;LIABILITY;CR;MAN;N;Accounts 2100–2299 together make up the IFRS 17 insurance contract liability
2100;Insurance contract liabilities – LRC measurement components (GMM and VFA);2000;LIABILITY;CR;MAN;N;
2110;LRC – present value of future cash flows (PVFCF);2100;LIABILITY;CR;MAN;Y;Best-estimate discounted future inflows and outflows. From the IFRS 17 engine; can be a debit balance for profitable new business.
2111;LRC – risk adjustment for non-financial risk;2100;LIABILITY;CR;MAN;Y;Compensation for uncertainty in amount and timing of cash flows.
2112;LRC – contractual service margin (CSM);2100;LIABILITY;CR;MAN;Y;Unearned profit, released as cover is provided.
2113;LRC – loss component (tracking);2100;LIABILITY;CR;MAN;Y;For onerous groups, if the engine reports it as a separate balance.
2120;Insurance contract liabilities – LRC actual cash flow sub-accounts (GMM and VFA);2100;LIABILITY;CR;MAN;N;
2121;Premiums and other contract inflows (policy fees, penalties, revival fees, loan interest);2120;LIABILITY;CR;AUTO;Y;Every inflow from a policyholder under an insurance contract. The movement-type code tells them apart.
2122;Premiums due from policyholders (receivable);2120;LIABILITY;DR;AUTO;Y;Debit sub-account: billed but not yet paid. Stays inside the insurance contract liability.
2123;Insurance acquisition cash flows (commission, brokerage, medical fees);2120;LIABILITY;DR;AUTO;Y;Directly attributable selling costs. Reduce the LRC; recognised over time through 4140 and 5300.
2124;Investment components transferred to LIC;2120;LIABILITY;DR;AUTO;Y;Amounts repaid to policyholders in all circumstances (surrender value, fund value, maturity). No profit or loss.
2125;Policy loans – principal;2120;LIABILITY;DR;AUTO;Y;Debit sub-account. Policy loans are generally part of the insurance contract cash flows – confirm with auditors.
2126;Policy loan interest and charges receivable;2120;LIABILITY;DR;AUTO;Y;
2127;Automatic premium loans (APL);2120;LIABILITY;DR;AUTO;Y;
2128;Premiums received in advance;2120;LIABILITY;CR;AUTO;Y;Paid before the due date.
2130;Insurance contract liabilities – VFA unit-linked and participating;2100;LIABILITY;CR;MAN;N;
2131;Unit fund value – policyholders' share of underlying items;2130;LIABILITY;CR;AUTO;Y;Must equal units × unit price in the unit sub-ledger. Use fund-level sub-accounts.
2132;Charges deducted from units (entity's share, cleared at period end);2130;LIABILITY;CR;AUTO;Y;Allocation, administration, fund management, mortality, surrender and switch charges.
2140;Insurance contract liabilities – LRC under PAA (group life, credit life, short-term);2100;LIABILITY;CR;MAN;N;
2141;LRC (PAA) – premiums received less revenue recognised;2140;LIABILITY;CR;AUTO;Y;Simple 'unearned premium' style measurement.
2142;LRC (PAA) – premiums due from group policyholders;2140;LIABILITY;DR;AUTO;Y;
2143;LRC (PAA) – loss component;2140;LIABILITY;CR;MAN;Y;If facts indicate a group is onerous.
2144;LRC (PAA) – acquisition cash flows (if not expensed);2140;LIABILITY;DR;AUTO;Y;
2190;Presentation reclass – insurance portfolios in net asset position;2100;LIABILITY;CR;MAN;Y;Auto-reversing reporting-date reclass to 1510.
2200;Insurance contract liabilities – liability for incurred claims (LIC);2000;LIABILITY;CR;MAN;N;
2210;LIC – claims reported, under assessment;2200;LIABILITY;CR;AUTO;Y;
2211;LIC – death and rider claims admitted, payable;2200;LIABILITY;CR;AUTO;Y;
2212;LIC – maturities payable;2200;LIABILITY;CR;AUTO;Y;
2213;LIC – surrenders and withdrawals payable;2200;LIABILITY;CR;AUTO;Y;
2214;LIC – survival benefits and income instalments payable;2200;LIABILITY;CR;AUTO;Y;
2215;LIC – annuity instalments payable;2200;LIABILITY;CR;AUTO;Y;
2216;LIC – IBNR and discounting adjustment;2200;LIABILITY;CR;MAN;Y;From the actuary / engine.
2217;LIC – risk adjustment for incurred claims;2200;LIABILITY;CR;MAN;Y;
2218;LIC – claims handling costs (ULAE);2200;LIABILITY;CR;MAN;Y;
2219;LIC – unclaimed policy benefits;2200;LIABILITY;CR;BOTH;Y;Benefits due but not collected.
2300;Investment contract liabilities (IFRS 9) and IFRS 15 balances;2000;LIABILITY;CR;MAN;N;
2310;Unit-linked investment contracts – FVTPL;2300;LIABILITY;CR;AUTO;Y;Unit-linked policies WITHOUT significant insurance risk.
2320;Deposit administration / pension accumulation – amortised cost;2300;LIABILITY;CR;AUTO;Y;Pension savings before retirement where there is no significant insurance risk.
2330;Deferred front-end fees (IFRS 15 contract liability);2300;LIABILITY;CR;AUTO;Y;Upfront fees for services to be given in future periods.
2340;Investment contract withdrawals and benefits payable;2300;LIABILITY;CR;AUTO;Y;
2400;Pre-recognition and cash-management liabilities;2000;LIABILITY;CR;MAN;N;
2410;Proposal deposits (before the contract is recognised);2400;LIABILITY;CR;AUTO;Y;
2420;Unallocated receipts (suspense);2400;LIABILITY;CR;AUTO;Y;
2430;Refunds payable (declined proposals, free-look cancellations);2400;LIABILITY;CR;AUTO;Y;
2500;Intermediaries and reinsurance;2000;LIABILITY;CR;MAN;N;
2510;Commission payable – tied agents;2500;LIABILITY;CR;AUTO;Y;
2520;Brokerage payable – brokers;2500;LIABILITY;CR;AUTO;Y;
2530;Commission payable – bancassurance and partners;2500;LIABILITY;CR;AUTO;Y;
2540;Reinsurance contract liabilities – portfolios in liability position;2500;LIABILITY;CR;MAN;Y;Reporting-date reclass from 1490.
2550;Funds withheld from reinsurers;2500;LIABILITY;CR;MAN;Y;
2600;Taxes and statutory payables;2000;LIABILITY;CR;MAN;N;
2610;Withholding tax payable – commissions;2600;LIABILITY;CR;AUTO;Y;
2615;Withholding tax payable – benefits and annuities;2600;LIABILITY;CR;AUTO;Y;Where tax law requires.
2620;Withholding tax payable – dividends;2600;LIABILITY;CR;MAN;Y;
2630;PAYE and payroll levies payable;2600;LIABILITY;CR;MAN;Y;
2640;Social security contributions payable (e.g. NSSF);2600;LIABILITY;CR;MAN;Y;
2650;Regulatory levies payable (e.g. TIRA levy);2600;LIABILITY;CR;AUTO;Y;
2660;Corporate income tax payable;2600;LIABILITY;CR;MAN;Y;
2670;Deferred tax liability;2600;LIABILITY;CR;MAN;Y;
2680;Stamp duty payable;2600;LIABILITY;CR;AUTO;Y;
2700;Other payables, leases and borrowings;2000;LIABILITY;CR;MAN;N;
2710;Trade creditors;2700;LIABILITY;CR;MAN;Y;
2720;Accrued expenses;2700;LIABILITY;CR;MAN;Y;
2730;Fund manager and custodian fees payable;2700;LIABILITY;CR;MAN;Y;
2740;Dividends payable;2700;LIABILITY;CR;MAN;Y;
2750;Borrowings;2700;LIABILITY;CR;MAN;Y;
2760;Lease liabilities (IFRS 16);2700;LIABILITY;CR;MAN;Y;
2770;Unclaimed monies (not linked to any policy);2700;LIABILITY;CR;MAN;Y;
3000;Equity;;EQUITY;CR;MAN;N;The owners' stake in the company
3100;Share capital;3000;EQUITY;CR;MAN;N;
3105;Authorised share capital (memorandum only – not posted);3100;EQUITY;CR;MAN;N;Memorandum only – not posted.
3110;Ordinary share capital – issued and fully paid;3100;EQUITY;CR;MAN;Y;
3115;Ordinary share capital – called up (partly paid);3100;EQUITY;CR;MAN;Y;
3120;Share premium;3100;EQUITY;CR;MAN;Y;
3130;Preference share capital;3100;EQUITY;CR;MAN;Y;
3140;Share application money pending allotment;3100;EQUITY;CR;MAN;Y;
3200;Reserves;3000;EQUITY;CR;MAN;N;
3210;Retained earnings;3200;EQUITY;CR;MAN;Y;
3215;IFRS 17 transition adjustment (within retained earnings);3200;EQUITY;CR;MAN;Y;Effect of adopting IFRS 17 at the transition date.
3220;Statutory / contingency reserve;3200;EQUITY;CR;MAN;Y;
3230;Revaluation reserve;3200;EQUITY;CR;MAN;Y;
3240;Fair value reserve – debt instruments at FVOCI;3200;EQUITY;CR;MAN;Y;Recycled to profit or loss on sale.
3245;Fair value reserve – equity instruments at FVOCI;3200;EQUITY;CR;MAN;Y;Never recycled.
3250;Insurance finance reserve (OCI option, para 88(b));3200;EQUITY;CR;MAN;Y;Only if the company disaggregates insurance finance income/expense.
3255;Reinsurance finance reserve (OCI option);3200;EQUITY;CR;MAN;Y;
3260;Capital contribution;3200;EQUITY;CR;MAN;Y;
3300;Profit for the year and distributions;3000;EQUITY;CR;MAN;N;
3310;Current year profit or loss (system-calculated);3300;EQUITY;CR;AUTO;Y;
3320;Dividends declared;3300;EQUITY;DR;MAN;Y;
4000;Insurance revenue (IFRS 17);;INCOME;CR;MAN;N;Revenue earned as the company provides insurance cover
4100;Insurance revenue – contracts not measured under PAA (GMM / VFA);4000;INCOME;CR;MAN;N;
4110;Expected incurred claims released (excluding investment components);4100;INCOME;CR;MAN;Y;Engine output.
4115;Expected directly attributable expenses released;4100;INCOME;CR;MAN;Y;
4120;Change in risk adjustment for non-financial risk (release);4100;INCOME;CR;MAN;Y;
4130;CSM recognised for services provided;4100;INCOME;CR;MAN;Y;Based on coverage units.
4140;Allocation of premiums to recover insurance acquisition cash flows;4100;INCOME;CR;MAN;Y;Mirrors 5300.
4150;Experience adjustments – premiums for current and past service;4100;INCOME;CR;MAN;Y;
4160;Insurance revenue – PAA contracts;4100;INCOME;CR;AUTO;Y;Earned evenly over the cover period (or by expected claims pattern).
5000;Insurance service expenses (IFRS 17);;EXPENSE;DR;MAN;N;Actual claims and attributable costs of providing cover
5100;Incurred claims (excluding investment components);5000;EXPENSE;DR;MAN;N;
5110;Incurred claims – death;5100;EXPENSE;DR;AUTO;Y;Only the part above any investment component.
5115;Incurred claims – maturity and survival benefits above investment component;5100;EXPENSE;DR;AUTO;Y;
5120;Incurred claims – annuity and income payments;5100;EXPENSE;DR;AUTO;Y;
5125;Incurred claims – rider benefits (accident, CI, disability, hospital, funeral, waiver);5100;EXPENSE;DR;AUTO;Y;
5130;Incurred claims – IBNR and risk adjustment for the current period;5100;EXPENSE;DR;MAN;Y;
5200;Other insurance service expenses;5000;EXPENSE;DR;MAN;N;
5210;Directly attributable expenses – policy maintenance (allocated);5200;EXPENSE;DR;MAN;Y;From expense allocation (P-19).
5215;Directly attributable expenses – claims handling (allocated);5200;EXPENSE;DR;MAN;Y;
5220;Premium-based levies and taxes;5200;EXPENSE;DR;AUTO;Y;
5300;Amortisation of insurance acquisition cash flows;5000;EXPENSE;DR;MAN;Y;Mirrors 4140.
5310;Acquisition cash flows expensed when incurred (PAA option, para 59(a));5000;EXPENSE;DR;AUTO;Y;Only for contracts with cover of one year or less.
5400;Losses on onerous contracts;5000;EXPENSE;DR;MAN;Y;
5410;Reversal of losses / allocation of loss component;5000;EXPENSE;CR;MAN;Y;
5500;Changes relating to past service (changes in LIC fulfilment cash flows);5000;EXPENSE;DR;MAN;Y;
5600;Impairment of acquisition cash flows asset;5000;EXPENSE;DR;MAN;Y;
6000;Net result from reinsurance contracts held;;EXPENSE;DR;MAN;N;Cost of reinsurance less what is recovered
6100;Reinsurance contracts held;6000;EXPENSE;DR;MAN;N;
6110;Allocation of reinsurance premiums paid;6100;EXPENSE;DR;MAN;Y;Cost of reinsurance cover received in the period (net of non-contingent commission).
6120;Amounts recovered from reinsurers – incurred claims;6100;INCOME;CR;AUTO;Y;Including profit commission contingent on claims.
6125;Amounts recovered – changes relating to past service;6100;INCOME;CR;MAN;Y;
6130;Loss-recovery component income;6100;INCOME;CR;MAN;Y;
6140;Effect of changes in reinsurer non-performance risk;6100;EXPENSE;DR;MAN;Y;
7000;Finance and investment result;;INCOME;CR;MAN;N;Insurance finance income/expenses (IFRS 17) and investment income (IFRS 9)
7100;Insurance finance income and expenses (IFIE);7000;INCOME;CR;MAN;N;
7110;Interest accreted on insurance contracts;7100;EXPENSE;DR;MAN;Y;Unwinding of discount on PVFCF and RA; CSM accretion at locked-in rates applies to GMM groups only.
7120;Effect of changes in interest rates and financial assumptions;7100;EXPENSE;DR;MAN;Y;Or to 3250 if the OCI option is used.
7130;Change in fair value of underlying items – VFA contracts;7100;EXPENSE;DR;AUTO;Y;Mirrors 7235 so there is no accounting mismatch.
7140;Foreign exchange on insurance contracts;7100;EXPENSE;DR;MAN;Y;
7150;Reinsurance finance income and expenses;7100;INCOME;CR;MAN;Y;
7200;Investment income (IFRS 9);7000;INCOME;CR;MAN;N;
7210;Interest income – amortised cost (effective interest method);7200;INCOME;CR;BOTH;Y;
7215;Interest income – FVOCI debt instruments;7200;INCOME;CR;BOTH;Y;
7220;Dividend income;7200;INCOME;CR;MAN;Y;
7230;Fair value gains and losses – FVTPL (company assets);7200;INCOME;CR;MAN;Y;
7235;Fair value gains and losses – underlying items (unit and participating funds);7200;INCOME;CR;AUTO;Y;
7240;Realised gains and losses – amortised cost and FVOCI debt;7200;INCOME;CR;MAN;Y;
7250;Rental income – investment property;7200;INCOME;CR;MAN;Y;
7260;Net impairment (expected credit losses) on financial assets;7200;EXPENSE;DR;MAN;Y;
7300;Investment contracts (IFRS 9 and IFRS 15);7000;INCOME;CR;MAN;N;
7310;Fee income from investment contracts (IFRS 15);7300;INCOME;CR;AUTO;Y;Charges on unit-linked and pension contracts without significant insurance risk.
7320;Change in investment contract liabilities (fair value / returns credited);7300;EXPENSE;DR;AUTO;Y;
7400;Investment expenses;7000;EXPENSE;DR;MAN;N;
7410;Fund manager fees;7400;EXPENSE;DR;MAN;Y;
7420;Custodian fees;7400;EXPENSE;DR;MAN;Y;
7430;Investment transaction costs;7400;EXPENSE;DR;MAN;Y;
7500;Other income and gains;7000;INCOME;CR;MAN;N;
7510;Foreign exchange gains and losses (non-insurance);7500;INCOME;CR;BOTH;Y;
7520;Gain on disposal of property and equipment;7500;INCOME;CR;MAN;Y;
7590;Miscellaneous income;7500;INCOME;CR;MAN;Y;
8000;Other operating expenses and tax;;EXPENSE;DR;MAN;N;Expenses by nature; the attributable part is moved into insurance service expenses at period end (8490)
8100;Expenses by nature (expense pool);8000;EXPENSE;DR;MAN;N;
8110;Salaries and wages;8100;EXPENSE;DR;MAN;Y;
8120;Staff benefits and employer pension contributions;8100;EXPENSE;DR;MAN;Y;
8130;Skills development levy and payroll levies;8100;EXPENSE;DR;MAN;Y;
8210;Short-term rent and service charges;8000;EXPENSE;DR;MAN;Y;Leases over 12 months go through 1715 / 2760.
8220;Utilities and communication;8000;EXPENSE;DR;MAN;Y;
8230;IT, software licences and hosting;8000;EXPENSE;DR;MAN;Y;
8240;Printing, stationery and policy documents;8000;EXPENSE;DR;MAN;Y;
8250;Marketing and advertising (not attributable to a portfolio);8000;EXPENSE;DR;MAN;Y;
8260;Travel and transport;8000;EXPENSE;DR;MAN;Y;
8270;Training;8000;EXPENSE;DR;MAN;Y;
8310;Actuarial fees;8000;EXPENSE;DR;MAN;Y;
8320;Audit fees;8000;EXPENSE;DR;MAN;Y;
8330;Legal and consultancy fees;8000;EXPENSE;DR;MAN;Y;
8340;Regulatory licence fees (not premium-based);8000;EXPENSE;DR;MAN;Y;
8350;Directors' fees;8000;EXPENSE;DR;MAN;Y;
8410;Bank charges and mobile money fees;8000;EXPENSE;DR;BOTH;Y;
8420;Interest on borrowings and lease liabilities;8000;EXPENSE;DR;MAN;Y;
8430;Depreciation;8000;EXPENSE;DR;BOTH;Y;
8440;Amortisation of intangible assets;8000;EXPENSE;DR;BOTH;Y;
8450;Impairment of receivables and write-offs;8000;EXPENSE;DR;MAN;Y;
8490;Attributable expenses allocated to insurance contracts;8000;EXPENSE;CR;MAN;Y;Moves the attributable part of 81xx–84xx into 5210, 5215 and 2123.
8800;Taxation;8000;EXPENSE;DR;MAN;N;
8810;Current income tax expense;8800;EXPENSE;DR;MAN;Y;
8820;Deferred tax expense / (credit);8800;EXPENSE;DR;MAN;Y;
9000;Clearing and suspense;;CLEARING;DR;MAN;N;Temporary accounts that must return to ZERO
9100;Clearing and suspense;9000;CLEARING;DR;MAN;N;
9110;Mobile money / payment gateway clearing;9100;CLEARING;DR;AUTO;Y;
9120;Bank reconciliation suspense;9100;CLEARING;DR;BOTH;Y;
9130;Inter-fund clearing (shareholder / policyholder / unit funds);9100;CLEARING;DR;AUTO;Y;
9140;Unit transaction clearing;9100;CLEARING;DR;AUTO;Y;
9150;Benefit payment clearing;9100;CLEARING;DR;AUTO;Y;
9160;IFRS 17 engine upload clearing;9100;CLEARING;DR;AUTO;Y;Engine results must fully post; any balance means an unmapped line.
9190;Opening balance / data migration suspense;9100;CLEARING;DR;MAN;Y;
```

- [ ] **Step 2: Write the failing test** `Ifrs17ChartTest` (plain JUnit, no Spring):

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The guide's chart (2.5), exactly: codes, types, normal balances and modes, parents before children. */
class Ifrs17ChartTest {

    private final List<ChartOfAccountBlueprint.Seed> chart = ChartOfAccountBlueprint.accounts();
    private final Map<String, ChartOfAccountBlueprint.Seed> byCode =
        chart.stream().collect(Collectors.toMap(ChartOfAccountBlueprint.Seed::code, Function.identity()));

    @Test
    void everyParentComesBeforeItsChildrenAndSharesItsPrefix() {
        Set<String> seen = new HashSet<>();
        for (ChartOfAccountBlueprint.Seed s : chart) {
            if (s.parentCode() != null) {
                assertThat(seen).as("parent of %s", s.code()).contains(s.parentCode());
                assertThat(s.code()).startsWith(ChartOfAccount.significantPrefix(s.parentCode()));
            }
            assertThat(seen.add(s.code())).as("duplicate %s", s.code()).isTrue();
        }
        assertThat(chart.stream().filter(s -> s.parentCode() == null).map(ChartOfAccountBlueprint.Seed::code))
            .containsExactly("1000", "2000", "3000", "4000", "5000", "6000", "7000", "8000", "9000");
    }

    @Test
    void theGuidesAccountsCarryTheGuidesTreatment() {
        assertThat(byCode.get("2110").name()).isEqualTo("LRC – present value of future cash flows (PVFCF)");
        assertThat(byCode.get("2122").normalBalance()).isEqualTo(PostingDirection.DR);   // contra-liability
        assertThat(byCode.get("2122").type()).isEqualTo(AccountType.LIABILITY);
        assertThat(byCode.get("2121").mode()).isEqualTo(PostingMode.AUTO);
        assertThat(byCode.get("1110").mode()).isEqualTo(PostingMode.BOTH);
        assertThat(byCode.get("3110").mode()).isEqualTo(PostingMode.MAN);
        assertThat(byCode.get("9160").type()).isEqualTo(AccountType.CLEARING);
        assertThat(byCode.get("6120").type()).isEqualTo(AccountType.INCOME);
        assertThat(byCode.get("5410").normalBalance()).isEqualTo(PostingDirection.CR);  // contra-expense
        assertThat(byCode.get("3105").postingAllowed()).isFalse();                       // memorandum only
    }

    @Test
    void theChartHasEveryAccountOfTheGuide() {
        assertThat(chart.stream().filter(ChartOfAccountBlueprint.Seed::postingAllowed)).hasSize(203);
        assertThat(chart).hasSize(246);   // 203 posting accounts + 43 headings
    }

    @Test
    void aUsedForNoteMayContainSemicolons() {
        assertThat(byCode.get("9160").usedFor()).isEqualTo("Engine results must fully post; any balance means an unmapped line.");
    }
}
```

The counts are the CSV's own (verified when this plan was written: 246 rows, 203 with `Y`, 43 with `N`); the test guards against an accidental edit.

- [ ] **Step 3:** Run `./mvnw -B -o test -Dtest=Ifrs17ChartTest` — FAIL (no `PostingMode`, no new `Seed` shape).
- [ ] **Step 4: Implement.** `PostingMode.java`:

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/** The guide's posting modes (2.3): AUTO by the system or engine only, MAN by staff, BOTH either. */
public enum PostingMode { AUTO, MAN, BOTH }
```

`AccountType.java`: `public enum AccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE, CLEARING }` (javadoc: "CLEARING: class 9, must return to zero").

`ChartOfAccountBlueprint.java` — replace the `ACCOUNTS` list and `legacyRemap()` with a CSV loader (keep the class javadoc short: "The guide's chart (IFRS 17 Chart of Accounts and Posting Guide, 2.5), exactly, from `finaccounting/ifrs17-chart.csv`"):

```java
public record Seed(String code, String name, String parentCode, AccountType type, PostingDirection normalBalance,
                   PostingMode mode, boolean postingAllowed, String usedFor) {}

private static final List<Seed> ACCOUNTS = load();

private static List<Seed> load() {
    try (var in = ChartOfAccountBlueprint.class.getResourceAsStream("/finaccounting/ifrs17-chart.csv")) {
        if (in == null) throw new IllegalStateException("finaccounting/ifrs17-chart.csv is missing");
        List<Seed> seeds = new ArrayList<>();
        List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] c = line.split(";", 8);   // at most 8: some "used for" notes contain semicolons
            seeds.add(new Seed(c[0], c[1], c[2].isEmpty() ? null : c[2], AccountType.valueOf(c[3]),
                PostingDirection.valueOf(c[4]), PostingMode.valueOf(c[5]), "Y".equals(c[6]),
                c[7].isEmpty() ? null : c[7]));
        }
        return List.copyOf(seeds);
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
}

public static List<Seed> accounts() { return ACCOUNTS; }
public static final String SEED_CURRENCY = "TZS";
```

- [ ] **Step 5:** Run `Ifrs17ChartTest` — PASS (main code that used `Seed.controlOf()` or `legacyRemap()` will not compile yet; Task 2 fixes the seeder; run with `-Dmaven.main.skip` is not possible, so do Task 2 before running, or temporarily run after Task 2 Step 4).
- [ ] **Step 6:** Commit with Task 2.

---

### Task 2: Chart entity, seeder and API derivations

**Files:**
- Modify: `domain/ChartOfAccount.java`, `infrastructure/ChartOfAccountSeeder.java`, `domain/PostingRule.java` (only `accountTypeFor`/`normalBalanceFor`), `application/FinaccountingApiImpl.java` (`ACCOUNT_CODE_PATTERN`, `createAccount`), `api/ChartOfAccountView.java`, `infrastructure/ChartOfAccountResponseDto.java`
- Test: modify `ChartOfAccountBlueprintTest`, `ChartOfAccountTest`, `ChartOfAccountMigrationV5Test`

**Interfaces — Consumes:** Task 1's `Seed`. **Produces:** `ChartOfAccount.seeded(UUID tenantId, Seed seed, ChartOfAccount parentOrNull, String currency, String createdBy)`, `ChartOfAccount.getMode() -> PostingMode`; `ChartOfAccountView` gains `PostingMode mode` (last component); `PostingRule.accountTypeFor(String)` and `normalBalanceFor(String)` cover classes 1–9.

- [ ] **Step 1: Failing test.** In `ChartOfAccountBlueprintTest` replace the old assertions with: the seeder creates exactly `ChartOfAccountBlueprint.accounts().size()` rows for a new tenant, `2122` has normal balance DR and mode AUTO, and a second call seeds nothing. In `ChartOfAccountTest` add: `ChartOfAccount.childOf(root("6000"…), "6125", …)` derives INCOME/CR from a class map is NOT what we want — instead assert an API-created child **inherits** its parent's type and normal balance and gets mode MAN by default (`childOf(parent 1200, "1299", …).getAccountType() == ASSET`, `getMode() == MAN`).
- [ ] **Step 2:** Move the old 36-account list into the test tree as `ChartOfAccountV5Legacy.java` (a `List<String[]>` of code/name/parent copied from the current `ChartOfAccountBlueprint.ACCOUNTS`) and point `ChartOfAccountMigrationV5Test` at it: V5 is history and must still match what it shipped.
- [ ] **Step 3: Implement.** `ChartOfAccount`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "posting_mode", nullable = false)
private PostingMode mode = PostingMode.MAN;

/** A guide account as the seeder writes it: every attribute explicit (spec R4). */
public static ChartOfAccount seeded(UUID tenantId, ChartOfAccountBlueprint.Seed seed, ChartOfAccount parent,
                                    String currency, String createdBy) {
    ChartOfAccount a = new ChartOfAccount(tenantId, seed.code(), seed.name(), seed.type(), seed.normalBalance(), createdBy);
    a.parentCode = parent == null ? null : parent.accountCode;
    a.level = parent == null ? 1 : (short) (parent.level + 1);
    a.postingAllowed = seed.postingAllowed();
    a.currency = currency;
    a.mode = seed.mode();
    a.description = seed.usedFor();
    return a;
}
```

`childOf` (API path): keep the prefix rule; take `accountType` and `normalBalance` **from the parent**, `mode` MAN. `root` (API path, no parent): use `PostingRule.accountTypeFor`/`normalBalanceFor`, which become a class map:

```java
public static AccountType accountTypeFor(String accountCode) {
    return switch (accountCode.charAt(0)) {
        case '1' -> AccountType.ASSET;
        case '2' -> AccountType.LIABILITY;
        case '3' -> AccountType.EQUITY;
        case '4', '7' -> AccountType.INCOME;
        case '5', '6', '8' -> AccountType.EXPENSE;
        case '9' -> AccountType.CLEARING;
        default -> throw new IllegalArgumentException("Unrecognised account code block: " + accountCode);
    };
}

public static PostingDirection normalBalanceFor(String accountCode) {
    return switch (accountTypeFor(accountCode)) {
        case LIABILITY, EQUITY, INCOME -> PostingDirection.CR;
        default -> PostingDirection.DR;
    };
}
```

`ChartOfAccountSeeder.seedIfAbsent`: build each row with `ChartOfAccount.seeded(tenantId, seed, byCode.get(seed.parentCode()), SEED_CURRENCY, seededBy)`; delete the `controlOf` use. `FinaccountingApiImpl.ACCOUNT_CODE_PATTERN = Pattern.compile("^[1-9]\\d{3}$")`. `ChartOfAccountView` and its DTO gain `mode`.

- [ ] **Step 4:** Run `./mvnw -B -o test-compile` then `-Dtest=Ifrs17ChartTest,ChartOfAccountBlueprintTest,ChartOfAccountTest,ChartOfAccountMigrationV5Test` — the Spring-backed ones need Task 3's columns; run the plain ones now (`Ifrs17ChartTest`, `ChartOfAccountTest`) — PASS.
- [ ] **Step 5:** Check `scripts/migrate.sh` and `.github/workflows/ci-cd.yml`: if they enumerate finaccounting migrations, add V10 (MigrationScriptCoverageTest enforces this).
- [ ] **Step 6: Commit** — `feat(finaccounting): the guide's IFRS 17 chart, exactly, with types, normal balances and posting modes`.

---

### Task 3: V10 — clean start, schema and database guards

**Files:**
- Create: `db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql`
- Modify: every test migration list containing `finaccounting/V5__chart_of_account_hierarchy.sql` (find them: `grep -rl "finaccounting/V5__chart_of_account_hierarchy.sql" src/test/java`) — append `"db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql"` after the last finaccounting entry.
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/LedgerGuardsIntegrationTest.java`

**Interfaces — Produces:** tables/columns used by Tasks 4, 6, 7: `chart_of_account.posting_mode`; `journal_entry.{source_type, preparer, approver, reason, reason_code, document_refs, reverses_journal_id, auto_reverse_on, policy_register_version, rule_version, engine_run_id}`; `gl_posting.{ifrs17_group, measurement_model, movement_type, product_id, portfolio, channel, branch, fund, reference_type, reference}`; `accounting_period(tenant_id, period, status, …)`; `accounting_policy_election(…)`.

- [ ] **Step 1: Write the migration.**

```sql
-- db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql
-- IFRS 17 I1 (spec 2026-10-05 §5): the guide's chart replaces the placeholder chart, the development ledger
-- starts clean (D2), every journal gains the guide's header and line dimensions, and the ledger's invariants are
-- enforced here rather than only in Java.

-- 1. Clean start: ledger rows only. No other schema's data is touched.
TRUNCATE finaccounting.gl_posting, finaccounting.journal_entry;
DELETE FROM finaccounting.chart_of_account;
-- M1's unused measurement ledgers: the engine results of I5 replace them (spec §5.6).
DROP TABLE IF EXISTS finaccounting.csm_ledger, finaccounting.lrc_ledger, finaccounting.lic_ledger;

-- 2. Chart: CLEARING type, the account's posting mode.
ALTER TABLE finaccounting.chart_of_account DROP CONSTRAINT IF EXISTS chart_of_account_account_type_check;
ALTER TABLE finaccounting.chart_of_account ADD CONSTRAINT chart_of_account_account_type_check
    CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE','CLEARING'));
ALTER TABLE finaccounting.chart_of_account ADD COLUMN posting_mode VARCHAR(4) NOT NULL DEFAULT 'MAN'
    CHECK (posting_mode IN ('AUTO','MAN','BOTH'));

-- 3. Journal header.
ALTER TABLE finaccounting.journal_entry
    ADD COLUMN source_type VARCHAR(10) NOT NULL DEFAULT 'EVENT'
        CHECK (source_type IN ('EVENT','SYSTEM','ENGINE_RUN','MANUAL')),
    ADD COLUMN preparer VARCHAR(100),
    ADD COLUMN approver VARCHAR(100),
    ADD COLUMN reason VARCHAR(500),
    ADD COLUMN reason_code VARCHAR(40),
    ADD COLUMN document_refs TEXT,
    ADD COLUMN reverses_journal_id UUID REFERENCES finaccounting.journal_entry(journal_entry_id),
    ADD COLUMN auto_reverse_on DATE,
    ADD COLUMN policy_register_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rule_version VARCHAR(40),
    ADD COLUMN engine_run_id UUID;
ALTER TABLE finaccounting.journal_entry ADD CONSTRAINT journal_manual_has_people
    CHECK (source_type <> 'MANUAL' OR (preparer IS NOT NULL AND approver IS NOT NULL AND approver <> preparer
                                       AND reason IS NOT NULL));

-- 4. Journal line dimensions (guide 2.2). Nullable: I2/I3 fill them; a non-policy line has no group.
ALTER TABLE finaccounting.gl_posting
    ADD COLUMN ifrs17_group VARCHAR(40),
    ADD COLUMN measurement_model VARCHAR(5) CHECK (measurement_model IN ('GMM','VFA','PAA','IFRS9')),
    ADD COLUMN movement_type VARCHAR(20),
    ADD COLUMN product_id UUID,
    ADD COLUMN portfolio VARCHAR(10),
    ADD COLUMN channel VARCHAR(15),
    ADD COLUMN branch VARCHAR(10),
    ADD COLUMN fund VARCHAR(30),
    ADD COLUMN reference_type VARCHAR(20),
    ADD COLUMN reference VARCHAR(100);

-- 5. Accounting periods (spec §5.4). A period with no row is OPEN.
CREATE TABLE finaccounting.accounting_period (
    tenant_id              UUID NOT NULL,
    period                 VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    status                 VARCHAR(7) NOT NULL CHECK (status IN ('OPEN','CLOSING','LOCKED')),
    closing_started_by     VARCHAR(100),
    closing_started_at     TIMESTAMPTZ,
    locked_by              VARCHAR(100),
    locked_at              TIMESTAMPTZ,
    reopen_requested_by    VARCHAR(100),
    reopen_requested_at    TIMESTAMPTZ,
    reopen_reason          VARCHAR(500),
    reopened_by            VARCHAR(100),
    reopened_at            TIMESTAMPTZ,
    version                BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, period),
    CHECK (reopened_by IS NULL OR reopened_by <> reopen_requested_by)
);

-- 6. The accounting policy register (spec §3, D7). Elections are never updated once approved (trigger below).
CREATE TABLE finaccounting.accounting_policy_election (
    election_id         UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    election_key        VARCHAR(40) NOT NULL,
    scope               VARCHAR(20) NOT NULL DEFAULT '*',
    election_value      VARCHAR(100) NOT NULL,
    effective_from      DATE NOT NULL,
    status              VARCHAR(9) NOT NULL CHECK (status IN ('PROPOSED','APPROVED','REJECTED')),
    rationale           VARCHAR(1000),
    sign_off_ref        VARCHAR(200),
    proposed_by         VARCHAR(100) NOT NULL,
    proposed_at         TIMESTAMPTZ NOT NULL,
    decided_by          VARCHAR(100),
    decided_at          TIMESTAMPTZ,
    register_version    INTEGER,
    version             BIGINT NOT NULL DEFAULT 0,
    CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CHECK (status <> 'APPROVED' OR (sign_off_ref IS NOT NULL AND register_version IS NOT NULL))
);
CREATE UNIQUE INDEX ux_policy_election_version
    ON finaccounting.accounting_policy_election (tenant_id, register_version) WHERE register_version IS NOT NULL;
CREATE UNIQUE INDEX ux_policy_election_one_approved
    ON finaccounting.accounting_policy_election (tenant_id, election_key, scope, effective_from) WHERE status = 'APPROVED';

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['accounting_period','accounting_policy_election'] LOOP
        EXECUTE format('ALTER TABLE finaccounting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON finaccounting.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON finaccounting.accounting_period, finaccounting.accounting_policy_election TO app_role;

-- 7. Guards. Every function raises with a stable prefix the Java side maps to a 409/422.

-- 7a. Posted lines and journals are never changed (app_role already lacks UPDATE/DELETE; this binds the owner too).
CREATE OR REPLACE FUNCTION finaccounting.refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'LEDGER_IMMUTABLE: % rows are never updated or deleted; post a reversal', TG_TABLE_NAME;
END $$;
CREATE TRIGGER trg_journal_entry_immutable BEFORE UPDATE OR DELETE ON finaccounting.journal_entry
    FOR EACH ROW EXECUTE FUNCTION finaccounting.refuse_change();
CREATE TRIGGER trg_gl_posting_immutable BEFORE UPDATE OR DELETE ON finaccounting.gl_posting
    FOR EACH ROW EXECUTE FUNCTION finaccounting.refuse_change();

-- 7b. A line joins only a journal created in this same transaction (R5), on an account whose mode accepts the
--     journal's source (spec §5.3), in a period that is not locked (and not CLOSING for an event).
CREATE OR REPLACE FUNCTION finaccounting.guard_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    j finaccounting.journal_entry%ROWTYPE;
    acct_mode text;
    acct_allowed boolean;
    period_status text;
BEGIN
    SELECT * INTO j FROM finaccounting.journal_entry WHERE journal_entry_id = NEW.journal_entry_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'LEDGER_NO_JOURNAL: line for unknown journal %', NEW.journal_entry_id;
    END IF;
    IF (SELECT xmin FROM finaccounting.journal_entry WHERE journal_entry_id = NEW.journal_entry_id)::text
           <> (pg_current_xact_id()::text::bigint % 4294967296)::text THEN
        RAISE EXCEPTION 'LEDGER_SEALED: journal % was posted in an earlier transaction; lines cannot be added', NEW.journal_entry_id;
    END IF;
    SELECT posting_mode, posting_allowed INTO acct_mode, acct_allowed
      FROM finaccounting.chart_of_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.account_code;
    IF NOT acct_allowed THEN
        RAISE EXCEPTION 'LEDGER_HEADING: % is a heading and takes no postings', NEW.account_code;
    END IF;
    IF (acct_mode = 'AUTO' AND j.source_type = 'MANUAL')
       OR (acct_mode = 'MAN' AND j.source_type = 'EVENT') THEN
        RAISE EXCEPTION 'LEDGER_MODE: account % is % and refuses a % journal', NEW.account_code, acct_mode, j.source_type;
    END IF;
    IF acct_mode = 'BOTH' AND j.source_type = 'MANUAL' AND j.reason_code IS NULL THEN
        RAISE EXCEPTION 'LEDGER_MODE: a manual line on BOTH account % needs a reason code', NEW.account_code;
    END IF;
    SELECT status INTO period_status FROM finaccounting.accounting_period
     WHERE tenant_id = NEW.tenant_id AND period = j.period;
    IF period_status = 'LOCKED' THEN
        RAISE EXCEPTION 'LEDGER_PERIOD_LOCKED: period % is locked', j.period;
    END IF;
    IF period_status = 'CLOSING' AND j.source_type = 'EVENT' THEN
        RAISE EXCEPTION 'LEDGER_PERIOD_CLOSING: period % is closing and takes no event postings', j.period;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_gl_posting_guard BEFORE INSERT ON finaccounting.gl_posting
    FOR EACH ROW EXECUTE FUNCTION finaccounting.guard_posting();

-- 7c. Every journal balances, checked at commit (deferred, R5).
CREATE OR REPLACE FUNCTION finaccounting.check_journal_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE dr numeric; cr numeric; n integer;
BEGIN
    SELECT coalesce(sum(amount) FILTER (WHERE direction = 'DR'), 0),
           coalesce(sum(amount) FILTER (WHERE direction = 'CR'), 0), count(*)
      INTO dr, cr, n
      FROM finaccounting.gl_posting WHERE journal_entry_id = NEW.journal_entry_id;
    IF n = 0 OR dr <> cr OR dr = 0 THEN
        RAISE EXCEPTION 'LEDGER_UNBALANCED: journal % has DR % and CR % over % lines', NEW.journal_entry_id, dr, cr, n;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced AFTER INSERT ON finaccounting.journal_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION finaccounting.check_journal_balanced();

-- 7d. An approved election is never edited; a proposal may only be decided once.
CREATE OR REPLACE FUNCTION finaccounting.guard_election() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'PROPOSED'
       OR NEW.election_key <> OLD.election_key OR NEW.scope <> OLD.scope
       OR NEW.election_value <> OLD.election_value OR NEW.effective_from <> OLD.effective_from
       OR NEW.proposed_by <> OLD.proposed_by THEN
        RAISE EXCEPTION 'POLICY_ELECTION_IMMUTABLE: an election is decided once and never edited; propose a new version';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_policy_election_guard BEFORE UPDATE OR DELETE ON finaccounting.accounting_policy_election
    FOR EACH ROW EXECUTE FUNCTION finaccounting.guard_election();
```

Before relying on it, confirm on Postgres 16 that the `chart_of_account_account_type_check` name is right: `SELECT conname FROM pg_constraint WHERE conrelid = 'finaccounting.chart_of_account'::regclass AND contype = 'c'` against the dev DB; use the real name.

- [ ] **Step 2: Failing test** `LedgerGuardsIntegrationTest` (Testcontainers + the UnitLinked/Funeral migration chain + V10; it posts through JDBC as the table owner, the way `UnitLinkedSchemaIntegrationTest` does, after `seeder.seedIfAbsent(tenant, "test")`). One test per guard:

```java
@Test void aJournalWhoseLinesDoNotBalanceIsRefusedAtCommit() {
    // in one TransactionTemplate: insert journal (EVENT), DR 2122 100, CR 2121 90 -> commit throws LEDGER_UNBALANCED
}
@Test void aPostedLineIsNeverUpdatedOrDeleted() {
    // post a balanced EVENT journal; UPDATE gl_posting SET amount = 1 -> LEDGER_IMMUTABLE; DELETE journal_entry -> LEDGER_IMMUTABLE
}
@Test void aLineCannotBeAddedToAJournalPostedEarlier() {
    // post a balanced journal; new transaction: insert a line for it -> LEDGER_SEALED
}
@Test void anAccountsModeDecidesWhoMayPostToIt() {
    // MANUAL journal (preparer a, approver b, reason) on 2121 (AUTO) -> LEDGER_MODE
    // EVENT journal on 3110 (MAN) -> LEDGER_MODE
    // MANUAL on 1110 (BOTH) without reason_code -> LEDGER_MODE; with reason_code 'CORRECTION' -> posts
    // SYSTEM journal on 3210 (MAN) and 2121 (AUTO) -> posts
}
@Test void aHeadingTakesNoPostings() { /* EVENT journal on 2120 -> LEDGER_HEADING */ }
@Test void aLockedPeriodTakesNothingAndAClosingOneTakesNoEvents() {
    // INSERT accounting_period (tenant, '2026-01', 'LOCKED') -> any journal in 2026-01 -> LEDGER_PERIOD_LOCKED
    // '2026-02' CLOSING: EVENT -> LEDGER_PERIOD_CLOSING; SYSTEM -> posts
}
@Test void aManualJournalNeedsTwoPeopleAndAReason() {
    // MANUAL with approver = preparer -> journal_manual_has_people
}
@Test void anApprovedElectionIsNeverEdited() {
    // insert APPROVED election; UPDATE election_value -> POLICY_ELECTION_IMMUTABLE; DELETE -> same
}
```

Write each body in full with `jdbc` against the table owner connection (`jdbcTemplate` in the test context connects as the container's owner) and `TransactionTemplate` for the commit-time check. Example for the first:

```java
@Test
void aJournalWhoseLinesDoNotBalanceIsRefusedAtCommit() {
    UUID tenant = UUID.randomUUID();
    seeder.seedIfAbsent(tenant, "test");
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
        UUID j = journal(tenant, "EVENT", "2026-10", null, null, null);
        line(tenant, j, "2122", "DR", "100.00");
        line(tenant, j, "2121", "CR", "90.00");
    })).hasMessageContaining("LEDGER_UNBALANCED");
}

private UUID journal(UUID tenant, String source, String period, String preparer, String approver, String reasonCode) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO finaccounting.journal_entry (journal_entry_id, tenant_id, source_event, source_ref, period,"
        + " source_type, preparer, approver, reason, reason_code) VALUES (?,?,?,?,?,?,?,?,?,?)",
        id, tenant, "test.Event", id.toString(), period, source, preparer, approver,
        preparer == null ? null : "test", reasonCode);
    return id;
}

private void line(UUID tenant, UUID journal, String account, String direction, String amount) {
    jdbc.update("INSERT INTO finaccounting.gl_posting (tenant_id, journal_entry_id, account_code, direction, amount,"
        + " currency, period, posting_type) SELECT ?, ?, ?, ?, ?::numeric, 'TZS', period, 'IFRS17'"
        + " FROM finaccounting.journal_entry WHERE journal_entry_id = ?",
        tenant, journal, account, direction, amount, journal);
}
```

(`gl_posting.group_id` is already nullable since V2; `posting_type` is NOT NULL without a default, hence the literal.)

- [ ] **Step 3:** Add V10 to every test migration list (Files above).
- [ ] **Step 4:** Run `./mvnw -B -o test -Dtest=LedgerGuardsIntegrationTest` — FAIL before V10 exists in the list, PASS after. If the `xmin`/`pg_current_xact_id()` comparison misbehaves under the test's connection, replace §7b's seal check with a transaction-local marker: `journal_entry` gets `created_xid xid8 NOT NULL DEFAULT pg_current_xact_id()` and the guard compares `j.created_xid = pg_current_xact_id()` — same rule, plainer.
- [ ] **Step 5: Commit** — `feat(finaccounting): V10 -- clean ledger, IFRS 17 header and line dimensions, periods, policy register tables, database guards`.

---

### Task 4: Journal header and line in the domain

**Files:** Modify `domain/JournalEntry.java`, `domain/GlPosting.java`, `application/FinaccountingApiImpl.java`, `api/JournalEntryView.java`, `api/GlPostingView.java`, their DTOs; create `api/JournalSource.java`.
**Interfaces — Consumes:** V10 columns. **Produces:** `JournalSource { EVENT, SYSTEM, ENGINE_RUN, MANUAL }`; `JournalEntry` getters/setters for header fields (`withSource(JournalSource)`, `asManual(String preparer, String approver, String reason, String reasonCode, String documentRefs)`, `reversing(UUID journalId)`, `autoReverseOn(LocalDate)`); `JournalEntry.Leg` gains an optional `LineDimensions dims` (`record LineDimensions(String ifrs17Group, String measurementModel, String movementType, UUID productId, String portfolio, String channel, String branch, String fund, String referenceType, String reference)` with `LineDimensions.NONE`); `addLeg(account, dir, amount, currency)` keeps working (dims NONE) and `addLeg(account, dir, amount, currency, LineDimensions)` is new; `postEntry` stamps `policy_register_version` from `PolicyRegister.currentVersion(tenantId)` (Task 7 — until then a package-private `RegisterVersionSource` bean returning 0, replaced in Task 7).

- [ ] **Step 1: Failing test** in `FinaccountingApiIntegrationTest`: posting an event journal records `source_type = 'EVENT'`, `policy_register_version = 0`, and a leg posted with `LineDimensions(… movementType "PRM_REN" …)` reads back with that movement type through `getJournalEntry(...)`.
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3: Implement** the columns (`@Column` mappings, `@Enumerated(EnumType.STRING)` for the source), the `Leg` record extension, the views/DTOs gaining `sourceType`, `policyRegisterVersion` (header) and the ten dimensions (line). `GlPosting`'s constructor gains a `LineDimensions dims` parameter; `postEntry` passes the leg's dims.
- [ ] **Step 4:** Run `FinaccountingApiIntegrationTest,JournalEntryBalanceTest` — PASS.
- [ ] **Step 5: Commit** — `feat(finaccounting): journals carry their source and the guide's line dimensions`.

---

### Task 5: Interim remap of today's postings onto the guide's accounts (R2)

**Files:** Modify `domain/PostingRule.java` (constants + javadoc), `application/UnitLinkedEventListener.java`, `infrastructure/UnitLinkedReconciliationController.java`; the code-coupled tests listed in File structure.
**Interfaces:** constants keep their names (callers unchanged); only codes change. Every target is AUTO or BOTH (EVENT may post there).

| Constant | Old | New | Guide account |
|---|---|---|---|
| CASH | 1120 | **1140** | Mobile money wallets (every money path is mobile money today) |
| PREMIUM_RECEIVABLE | 1210 | **2122** | Premiums due from policyholders |
| UNEARNED_PREMIUM | 2140 | **2121** | Premiums and other contract inflows |
| CLAIMS_EXPENSE | 5100 | **5110** | Incurred claims – death (interim catch-all; I3 splits by benefit) |
| CLAIMS_PAYABLE | 2110 | **2211** | LIC – death and rider claims admitted, payable |
| POLICYHOLDER_BENEFITS_PAYABLE | 2130 | **2213** | LIC – surrenders and withdrawals payable |
| OTHER_RECEIVABLES | 1230 | **2122** | owed by the policyholder (inside the contract) |
| POLICY_LOAN_RECEIVABLE | 1250 | **2125** | Policy loans – principal |
| REINSURANCE_RECOVERABLE | 1240 | **1420** | Asset for incurred claims – recoveries |
| REINSURANCE_PAYABLE | 2220 | **1430** | Reinsurance premiums payable (operational) |
| REINSURANCE_CEDED_PREMIUM | 5500 | **1436** | Reinsurance premiums ceded – actual cash flows (amount still wrong until I3) |
| COMMISSION_EXPENSE | 5200 | **2123** | Insurance acquisition cash flows |
| WITHHOLDING_TAX_PAYABLE | 2230 | **2615** | Withholding tax payable – benefits and annuities |
| UNIT_LINKED_LIABILITY | 2150 | **2131** | Unit fund value |
| UNIT_LINKED_CHARGES_INCOME | 4310 | **2132** | Charges deducted from units |
| CHANGE_IN_UNIT_LINKED_LIABILITY | 5600 | **7130** | Change in fair value of underlying items – VFA |

Rewrite `PostingRule`'s class javadoc to say exactly this: an interim remap onto the guide's chart, every rule still a single pair, replaced wholesale by I3's rules file; the known wrong amount on `reinsurance.CessionRecorded` stays until I3.

- [ ] **Step 1: Failing test:** add to `ChartOfAccountBlueprintTest` — every `PostingRule` constant names a seeded posting account whose mode is AUTO or BOTH:

```java
@Test
void everyInterimRuleTargetsAnAccountAnEventMayPostTo() throws Exception {
    Map<String, ChartOfAccountBlueprint.Seed> byCode = ChartOfAccountBlueprint.accounts().stream()
        .collect(Collectors.toMap(ChartOfAccountBlueprint.Seed::code, s -> s));
    for (Field f : PostingRule.class.getFields()) {
        if (f.getType() != String.class) continue;
        String code = (String) f.get(null);
        assertThat(byCode).as(f.getName()).containsKey(code);
        assertThat(byCode.get(code).postingAllowed()).as(f.getName()).isTrue();
        assertThat(byCode.get(code).mode()).as(f.getName()).isIn(PostingMode.AUTO, PostingMode.BOTH);
    }
}
```

- [ ] **Step 2:** Run — FAIL (old codes are not in the guide chart).
- [ ] **Step 3: Implement** the table. `UnitLinkedReconciliationController`: replace the literal `"2150"` with `PostingRule.UNIT_LINKED_LIABILITY`.
- [ ] **Step 4: Update the coupled tests** by the same table: every literal old code in an assertion becomes its new code (`credit(tenant, "2150")` → `"2131"`, `"4310"` → `"2132"`, `"5100"` → `"5110"`, `"2140"` → `"2121"`, `"1230"` → `"2122"`, `"1210"` → `"2122"` …). Where a test sums two old accounts that now share one code (1210 and 1230 both become 2122), restate its expected value as the sum and say so in a comment. `AppRolePrivilegesIntegrationTest` keeps "permission denied" for app_role (privileges unchanged).
- [ ] **Step 5:** Run by name: `ChartOfAccountBlueprintTest,FinaccountingApiIntegrationTest,FinaccountingContractTest,EftDisbursementIntegrationTest,WithholdingIntegrationTest,UnitLinkedAccountingIntegrationTest,UnitLinkedU2AccountingIntegrationTest,PremiumPostingEndToEndTest,ClaimAndCommissionPostingEndToEndTest,ReinsuranceAndLoanPostingEndToEndTest,PayoutPaymentEndToEndTest,FuneralEndorsementIntegrationTest,MainMemberDeathIntegrationTest,AppRolePrivilegesIntegrationTest,RowLevelSecurityIntegrationTest` — all PASS. Compare the class count reported with the 15 named.
- [ ] **Step 6: Commit** — `refactor(finaccounting): today's postings land on the guide's accounts (interim, until the rules engine)`.

---

### Task 6: Accounting periods

**Files:** Create `api/PeriodStatus.java`, `api/AccountingPeriodView.java`, `domain/AccountingPeriod.java`, `infrastructure/AccountingPeriodRepository.java`, `application/AccountingPeriods.java`, `infrastructure/AccountingPeriodController.java`; modify `api/FinaccountingApi.java`, `application/FinaccountingApiImpl.java`, `infrastructure/FinaccountingExceptionHandler.java`. Test: `finaccounting/AccountingPeriodIntegrationTest.java`.

**Interfaces — Produces:** `PeriodStatus { OPEN, CLOSING, LOCKED }`; `record AccountingPeriodView(String period, PeriodStatus status, String closingStartedBy, Instant closingStartedAt, String lockedBy, Instant lockedAt, String reopenRequestedBy, Instant reopenRequestedAt, String reopenReason)`; on `FinaccountingApi`: `AccountingPeriodView period(String period)`, `List<AccountingPeriodView> periods()`, `AccountingPeriodView startClosing(String period, String by)`, `AccountingPeriodView lock(String period, String by)`, `AccountingPeriodView requestReopen(String period, String reason, String by)`, `AccountingPeriodView approveReopen(String period, String by)`; endpoints `GET /finance/periods`, `GET /finance/periods/{period}`, `POST /finance/periods/{period}/closing`, `POST /finance/periods/{period}/lock`, `POST /finance/periods/{period}/reopen-request`, `POST /finance/periods/{period}/reopen-approval` (all `finance_officer`/`admin`).

Rules (messages exact):
- `startClosing`: OPEN → CLOSING; "Period P is not open; it is S".
- `lock`: CLOSING → LOCKED, refused while any 9xxx account nets non-zero in P: "Clearing account 9110 holds 1,200.00 TZS in P; clearing accounts must return to zero before the period locks". Also refused while an earlier period is not LOCKED and has postings: "Period P-1 must be locked first".
- `requestReopen`: LOCKED only, reason required ("Reopening a locked period needs a reason").
- `approveReopen`: approver ≠ requester ("A second person approves reopening a period") → OPEN, clearing the closing/lock stamps but keeping the reopen record.
- A period with no row reads as OPEN; the first transition creates the row.

- [ ] **Step 1: Failing test** — each rule above as one test (create postings with `FinaccountingApiImpl.postEntry` for the 9xxx case: a SYSTEM journal DR 9110 / CR 2121).
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3: Implement** `AccountingPeriod` (JPA entity, `@IdClass` on tenant + period, `@Version`), transitions as methods that throw `FinaccountingValidationException` (→ 422) or a new `PeriodStateException` (→ 409) with the messages above; `AccountingPeriods` service does the 9xxx check with `glPostingRepository.netByAccountPrefix(tenantId, period, "9")` (new query: `select account_code, sum(case direction when 'DR' then amount else -amount end) … group by account_code having … <> 0`). Map the database guard prefixes in `FinaccountingExceptionHandler`: `LEDGER_PERIOD_LOCKED`, `LEDGER_PERIOD_CLOSING`, `LEDGER_MODE`, `LEDGER_HEADING` → 409 with the guard's message.
- [ ] **Step 4:** Run `AccountingPeriodIntegrationTest` — PASS.
- [ ] **Step 5: Commit** — `feat(finaccounting): accounting periods -- open, closing, locked, reopened by a second person`.

---

### Task 7: The accounting policy register

**Files:** Create `api/PolicyElectionView.java`, `api/PolicyElectionInput.java`, `domain/PolicyElection.java`, `infrastructure/PolicyElectionRepository.java`, `application/PolicyRegister.java`, `application/PolicyRegisterBaseline.java`, `infrastructure/PolicyRegisterController.java`; modify `ChartOfAccountSeeder` (also seeds the baseline), `FinaccountingApi`/`Impl`, Task 4's version source. Test: `finaccounting/PolicyRegisterIntegrationTest.java`.

**Interfaces — Produces:**
- Election keys (enum `ElectionKey`): `MEASUREMENT_MODEL` (scope = portfolio), `INVESTMENT_COMPONENT_RULE` (scope = portfolio), `MODEL_OVERRIDE_ALLOWED` (scope = portfolio, value = comma list of models or `NONE`), `POLICY_LOANS`, `ACQUISITION_CASH_FLOWS` (scope = model), `OCI_OPTION`, `RIDERS`, `PREMIUM_BILLING`, `CONTRACT_RECOGNITION`, `COHORT`.
- `record PolicyElectionInput(String key, String scope, String value, LocalDate effectiveFrom, String rationale)`.
- `record PolicyElectionView(UUID electionId, String key, String scope, String value, LocalDate effectiveFrom, String status, String rationale, String signOffRef, String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt, Integer registerVersion)`.
- `PolicyRegister`: `PolicyElectionView propose(PolicyElectionInput, String by)`, `PolicyElectionView approve(UUID id, String signOffRef, String by)`, `PolicyElectionView reject(UUID id, String reason, String by)`, `Optional<PolicyElectionView> inForce(String key, String scope, LocalDate on)` (scope falls back to `*`), `List<PolicyElectionView> list(LocalDate asOf)` (the elections in force on `asOf` plus every PROPOSED one), `int currentVersion(UUID tenantId)` (max approved `register_version`, 0 if none).
- Endpoints: `GET /finance/accounting-policies?asOf=`, `POST /finance/accounting-policies` (propose), `POST /finance/accounting-policies/{id}/approval` (`{signOffRef}`), `POST /finance/accounting-policies/{id}/rejection` (`{reason}`) — `finance_officer`/`admin`.

Rules (messages exact): unknown key → "Unknown accounting policy election K"; value not allowed for the key → "V is not a permitted value for K" (allowed values per key in `ElectionKey`: models `GMM, VFA, PAA, IFRS9`; IC rules `NONE, PREMIUMS_RETURNED, SURRENDER_VALUE, SURRENDER_VALUE_WITH_BONUSES, FUND_VALUE, WHOLE_BALANCE`; loans `INSIDE_CONTRACT, OUTSIDE_CONTRACT`; acquisition `SPREAD, EXPENSE_WHEN_INCURRED`; OCI `OFF, ON`; riders `HOST_GROUP, SEPARATE`; billing `ACCRUAL_AT_INVOICE`; recognition `ISSUE_DATE`; cohort `ANNUAL`); `effectiveFrom` before today (EAT) → "An election applies from today or later; a past change is a restatement, made through journals"; approve by the proposer → "A second person approves an accounting policy election"; approve without sign-off → "An approval names its sign-off reference"; deciding a non-PROPOSED election → "Election E is S, not awaiting a decision". Approving assigns `register_version = currentVersion + 1` under a `SELECT … FOR UPDATE` on the tenant's highest version row (or an advisory lock `pg_advisory_xact_lock(hashtext(tenant))`).

`PolicyRegisterBaseline` — the spec §3 baseline as APPROVED elections, effective 2020-01-01, proposed by `system:baseline-proposer`, decided by `system:baseline`, sign-off `"Baseline per IFRS 17 spec 2026-10-05 §3 -- pending actuary and auditor sign-off"`, register versions 1..n, seeded with the chart (inserted directly, bypassing the 'from today' rule, which applies to proposals): MEASUREMENT_MODEL TERM→GMM, CRL→GMM, GRPL→PAA, FUN→PAA, WL→GMM, END→GMM, MB→GMM, PAR→VFA, ULIP→VFA, SAV→IFRS9, DEP→IFRS9, IANN→GMM, DANN→IFRS9, PEN→IFRS9; INVESTMENT_COMPONENT_RULE TERM→PREMIUMS_RETURNED (applies only to a return-of-premium benefit), CRL/GRPL/FUN/IANN→NONE, WL/END/MB→SURRENDER_VALUE, PAR→SURRENDER_VALUE_WITH_BONUSES, ULIP→FUND_VALUE, SAV/DEP/DANN/PEN→WHOLE_BALANCE; MODEL_OVERRIDE_ALLOWED SAV→GMM, DEP→GMM, DANN→GMM, PEN→GMM, CRL→PAA (a scheme's credit life), all others→NONE; POLICY_LOANS→INSIDE_CONTRACT; ACQUISITION_CASH_FLOWS GMM→SPREAD, VFA→SPREAD, PAA→EXPENSE_WHEN_INCURRED; OCI_OPTION→OFF; RIDERS→HOST_GROUP; PREMIUM_BILLING→ACCRUAL_AT_INVOICE; CONTRACT_RECOGNITION→ISSUE_DATE; COHORT→ANNUAL. The two auditor flags of spec §3 go in each relevant row's `rationale`.

- [ ] **Step 1: Failing test** — baseline seeded with the chart (`inForce("MEASUREMENT_MODEL","ULIP", today)` = VFA; `currentVersion` = the baseline count); propose + approve by a second person makes the new value in force from its date while the old one stays in force before it; the proposer cannot approve; an approved election cannot be edited (the DB trigger, through the repository); a past effective date is refused; a journal posted after an approval records the new `policy_register_version`.
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3: Implement** as specified; replace Task 4's version source with `PolicyRegister.currentVersion`.
- [ ] **Step 4:** Run `PolicyRegisterIntegrationTest,FinaccountingApiIntegrationTest` — PASS.
- [ ] **Step 5: Commit** — `feat(finaccounting): the effective-dated accounting policy register, baseline seeded, maker-checker`.

---

### Task 8: API documents and the console

**Files:** Modify `backend/api/openapi/openapi-finaccounting.yaml` (chart `mode`, `accountType` + `CLEARING`; journal `sourceType`, `policyRegisterVersion`; posting dimensions; the period and accounting-policy paths and schemas); run `cd frontend && npm run generate:api`; modify `src/api/types.ts`, `src/api/finaccounting.ts`, the chart of accounts page (type/normal balance/mode columns and a mode filter), the journal detail (source, policy version, line dimensions); create `src/features/finance/PeriodsPage.tsx`, `src/features/finance/PolicyRegisterPage.tsx`, `src/features/finance/registerForms.ts` + `registerForms.test.ts`; routes `/staff/finance/periods`, `/staff/finance/accounting-policies` and nav entries in the Finance group (finance and admin only, like GL postings).

- **Periods page:** a table of the last 12 periods with status; per row the next action allowed (Start closing / Lock / Request reopen / Approve reopen) through a `ConfirmAct`; the server's refusal shown inline (`InlineError`); the reopen approval shows a `GatePanel` "A second person approves" from the requester and the viewer.
- **Accounting policies page:** elections in force today grouped by key (key, scope, value, effective from, register version, sign-off); a "Propose a change" form (key select, scope, value select from the key's allowed values, effective from ≥ today, rationale) validated by `registerForms.ts` with the server's messages; the PROPOSED list with Approve (sign-off reference required) and Reject (reason) for a second person, gated like the reopen.

- [ ] **Step 1:** Write `registerForms.test.ts` (value allowed for key; effective-from not in the past; sign-off required) — FAIL; implement `registerForms.ts` — PASS.
- [ ] **Step 2:** Implement the API layer, pages, routes and nav.
- [ ] **Step 3:** `npx tsc -b; npx eslint src e2e; npx vitest run src/features/finance src/features/finaccounting` — green (the chart screen's folder name: check `src/features` for the existing chart page and run its folder).
- [ ] **Step 4:** Update `e2e/staff-finaccounting.spec.ts` to the guide's names and codes: "Reinsurance Recoverable" → "Asset for incurred claims – recoveries on claims incurred"; "Petty Cash" → "Petty cash"; descending sort leads with `9190`; any posting-detail assertion on old codes → the Task 5 table.
- [ ] **Step 5:** Create `e2e/staff-ifrs17-foundation.spec.ts`: finance proposes `OCI_OPTION = ON` effective tomorrow; the same user's Approve is disabled with the gate; admin approves with sign-off "Test memo"; it appears with the next register version. Finance starts closing a past empty period (e.g. two years ago, Jan) and locks it; requests a reopen with a reason; admin approves; the period reads OPEN.
- [ ] **Step 6: Commit** — `feat(console): periods and the accounting policy register; the chart shows the guide's types and modes`.

---

### Task 9: Dev database, seed and gate

- [ ] **Step 1: Dev DB.** Stop the dev backend (and orphan JVMs — identify by command line). Apply `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q -1 < db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql`. Restart the backend from this worktree; post one event (e.g. collect a premium through the console) and confirm `chart_of_account` re-seeded (`SELECT count(*) …` = the CSV's count) and the journal landed on 2122/2121/1140 with `source_type = 'EVENT'`.
- [ ] **Step 2: Backend gate.** `./mvnw -B -o clean test-compile`; then run by name every finaccounting test class (`find src/test/java/tz/co/nlolo/lifeplatform/finaccounting -name "*Test.java"`), the Task 5 list, `MigrationScriptCoverageTest`, `ModularityTests`, `SpecTypeConformanceTest`, and every class whose migration list Task 3 edited. Compare the class count reported with the count named; grep the log for `OutOfMemoryError`.
- [ ] **Step 3: Frontend gate.** `npx tsc -b; npx eslint src e2e; npx vitest run` — green.
- [ ] **Step 4: Full e2e** `npx playwright test --reporter=line` with nothing else running; any timeout re-run alone before diagnosing.
- [ ] **Step 5:** Whole-branch self-review against the spec §5 and this plan's Global Constraints; fix what it finds.
- [ ] **Step 6:** Merge `--no-ff` to main and `git push origin main` (the user's standing instruction is to proceed without confirmation); record the deviations in the merge message and memory.
