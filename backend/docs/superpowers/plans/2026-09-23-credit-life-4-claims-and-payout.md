# Credit Life Plan 4: The Claim and the Payout — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A borrower dies, the insurer pays the lender what the borrower still owed, and the loan comes off cover — with the exclusions the client confirmed actually enforced, and the money leaving by a rail fit for a multi-million-shilling payout.

**Architecture:** Five tasks. The first two make a credit-life claim *registrable and correct* — the claimant is the bank, and the life assured stops being a name on a spreadsheet. The third enforces the only anti-selection control this product has, since below the free cover limit nobody is underwritten. The fourth gives the money a way out that is not a mock. The fifth closes the loop: a capped claim pays the cap, the claim closes, the loan exits, and the residual is stated as the lender's problem rather than silently lost.

**Tech Stack:** Java 21, Spring Boot, Spring Modulith, JPA/Hibernate, Flyway (per-module dirs under `backend/db-migrations/`, applied by `backend/scripts/migrate.sh`, **not** on boot), Postgres 16 with RLS, Testcontainers, JUnit 5.

---

## Global Constraints

Copied from the spec, the client's answers, and constraints this codebase has already paid for. Every task's requirements implicitly include this section.

- **The claimant IS the bank**, which is also the policyholder (spec §2.9). Claimant and payee are the same entity, so no payee-redirection concept is needed or wanted.
- **Cover on the date of event is what `PolicyApi.claimableCover` returns** — the stored covered amount MIN the straight-line declining balance. It is already correct and already called by `ClaimsApiImpl`. **Do not compute cover a second time anywhere.**
- **Exclusions, confirmed by the client 2026-09-22 (answer 3.4):** 12-month suicide, 12-month pre-existing conditions, **no general waiting period**. Below the FCL there is no underwriting, so these are the only anti-selection control that exists.
- **Payout is EFT**: the instruction is recorded and posted to the GL, and finance executes the transfer out-of-band. **The mobile-money gateway must not be called for a credit-life payout** — it is a mock with no authentication, which is not where a multi-million-shilling lender payout belongs (spec §2.9).
- **A settled claim refunds no premium and claws back no commission.** Already built and asserted; `ExitReason.CLAIM_SETTLED` is written only by `dischargeForSettledClaim` and is refused from `exitMember` and from the exits file. **Do not weaken this.**
- **Money is `BigDecimal` with an explicit currency string.** Follow the `Map.of("amount", …, "currencyCode", …)` event payload shape.
- **RLS predicates use `NULLIF(current_setting('app.current_tenant_id', true), '')::uuid`.** A bare cast raises on a RESET GUC.
- **An AFTER_COMMIT listener must never throw** — it retries for ever. Log and return. And note `withTenant` catches `Exception`, not `Error`.
- **After any record/DTO signature change, run plain `./mvnw clean test-compile` and look for `BUILD SUCCESS`.** Never verify a compile with a narrow grep.
- **Stop the dev backend before any `clean`**, and never run two Maven builds at once — the tell is `Could not initialize plugin: MockMaker`, never an assertion failure.
- **`Unresolved compilation problem` in a stack trace is the IDE's compiler telling the truth about your source.** Fix the source; do not dismiss it.
- **Baseline: 1388 tests green on `main` at `d44c1be`.**

---

## What does not exist yet, verified 2026-09-23

Each was checked against the code, not assumed.

**No freeform→party promotion.** `PolicyApi` has no promote method at all. Spec §2.2 says a freeform member becomes a real party "only at claim", and that moment has never been built. A credit-life claim today would pay out against a member who is a name and a date of birth.

**No exclusion enforcement anywhere.** Nothing in `src/main/java` or `db-migrations` mentions suicide, a waiting period or a pre-existing condition. The client's confirmed exclusions are, today, prose in a spec.

**One payment rail.** `PaymentGatewayPort` exposes `submitDisbursement`/`submitCollection` and nothing else; `DisbursementInstruction` has no method or rail column. Every disbursement on the platform goes to `MobileMoneyGatewayAdapter`. There is no way to say "record this instruction, post it, and let finance move the money".

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `db-migrations/policy/V22__member_promoted_party.sql` | Records that a freeform member became a party, and when |
| `db-migrations/product/V15__exclusion_periods.sql` | Suicide and pre-existing windows on `product_version` |
| `db-migrations/payment/V5__disbursement_method.sql` | `method` on `disbursement_instruction`; EFT needs no gateway reference |
| `db-migrations/claims/V6__exclusion_decline.sql` | The decline reason and the window it failed |
| `policy/api/PromoteMemberRequest.java` | The identity a freeform member is promoted with |
| `claims/domain/ExclusionRules.java` | Pure: does this date of event fall inside an exclusion window |
| `claims/api/ClaimDeclineReason.java` | `SUICIDE_WITHIN_EXCLUSION`, `PRE_EXISTING_WITHIN_EXCLUSION` |
| `payment/api/DisbursementMethod.java` | `MOBILE_MONEY`, `EFT` |
| Tests | `ExclusionRulesTest`, `MemberPromotionIntegrationTest`, `CreditLifeClaimEndToEndTest`, `EftDisbursementIntegrationTest` |

**Modified:** `ClaimsApiImpl` (claimant rule, exclusions, decline), `PolicyApiImpl` (`promoteMember`), `PaymentApiImpl` + `PaymentRequestListener` (method routing), `finaccounting/PostingRule` (EFT payout posting).

---

## Task 1: The claimant of a credit-life claim is the bank

**Files:**
- Modify: `claims/application/ClaimsApiImpl.java` (`registerClaim`)
- Test: `claims/CreditLifeClaimEndToEndTest.java` (new)

**Interfaces:** Consumes `PolicyApi.getPolicy(...).policyholderPartyId()`. Produces no new types.

- [ ] **Step 1: Write the failing test**

```java
@Test
void aCreditLifeClaimIsRegisteredWithTheBankAsClaimant() {
    // Spec 2.9: the payout extinguishes a debt, so the money is owed to the lender. The
    // claimant of record and the payee are the same entity, which is why this product needs
    // no payee-redirection concept at all.
    ClaimView claim = claimsApi.registerClaim(deathRequest(scheme, borrowerMemberId, bankPartyId),
        "idem-" + UUID.randomUUID(), "claims.clerk");

    assertThat(claim.claimantPartyId()).isEqualTo(bankPartyId);
}

@Test
void aCreditLifeClaimNamingAnyoneButTheLenderIsRefused() {
    // The failure this prevents is paying a borrower's family for a debt the family does not
    // hold, while the lender's loan stays unpaid and the insurer believes it has settled.
    UUID theFamily = person("Next Of Kin");

    assertThatThrownBy(() -> claimsApi.registerClaim(
        deathRequest(scheme, borrowerMemberId, theFamily), "idem-" + UUID.randomUUID(), "clerk"))
        .isInstanceOf(ClaimValidationException.class)
        .hasMessageContaining("policyholder");
}

@Test
void anEmployerSchemeClaimStillNamesWhoeverTheSchemeSays() {
    // The rule is credit-life only. A group-life death benefit is owed to the member's
    // beneficiary, not to the employer, and narrowing that would be a serious regression.
    ...
}
```

- [ ] **Step 2: Run to verify it fails.** `./mvnw test -Dtest=CreditLifeClaimEndToEndTest`

- [ ] **Step 3: Implement.** In `registerClaim`, when the policy's category is `CREDIT_LIFE`, require `claimantPartyId` to equal the policy's `policyholderPartyId`, with a message naming both.

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit.**

---

## Task 2: The life assured stops being a name on a spreadsheet

A freeform member carries a name and a date of birth. At claim they become a real party — the one moment the spec says promotion happens (§2.2), and the moment it matters, because the platform is about to pay out against them.

**Files:**
- Create: `db-migrations/policy/V22__member_promoted_party.sql`, `policy/api/PromoteMemberRequest.java`
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `policy/domain/PolicyMember.java`
- Test: `policy/MemberPromotionIntegrationTest.java`

**Interfaces:** Produces `PolicyApi.promoteMember(String policyNumber, UUID policyMemberId, PromoteMemberRequest identity, String promotedBy) -> PolicyMemberView`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void promotingAFreeformMemberGivesThemARealPartyWithoutLosingTheirLoan() {
    PolicyMemberView before = enrolledBorrower();
    assertThat(before.memberType()).isEqualTo(MemberType.FREEFORM);

    PolicyMemberView after = policyApi.promoteMember(scheme, before.policyMemberId(),
        new PromoteMemberRequest("19880314-12345-00001-14", "+255712345678", Sex.F), "claims.clerk");

    assertThat(after.memberType()).isEqualTo(MemberType.PARTY);
    assertThat(after.memberPartyId()).isNotNull();
    // The loan is what the cover is measured against. Losing it here would silently make the
    // member unvaluable -- the exact shape of the getLoanTerms defect plan 3 closed.
    assertThat(after.memberReference()).isEqualTo(before.memberReference());
    assertThat(policyApi.claimableCover(scheme, before.policyMemberId(), LocalDate.now(),
        BenefitType.DEATH.name()).amount()).isEqualByComparingTo(coverBefore);
}

@Test
void promotingTwiceReturnsTheSamePartyRatherThanRegisteringASecondPerson() {
    // A claim can be registered, reopened and settled. Promotion must not mint a duplicate
    // person each time somebody touches the claim.
    ...
}

@Test
void anExistingPartyIsREUSEDWhenTheNationalIdAlreadyNamesSomebody() {
    // The borrower may already be a customer. Two party rows for one national ID is the
    // duplicate-person problem the party module exists to avoid.
    ...
}

@Test
void anOrdinaryPartyMemberCannotBePromotedAgain() { ... }
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Write `V22__member_promoted_party.sql`.** `promoted_party_at TIMESTAMPTZ`, and a CHECK that a PARTY member with a promotion timestamp still carries `member_name` — the name the lender used is how their file reconciles to our roll, and it must survive promotion.

- [ ] **Step 4: Implement `promoteMember`.** Resolve or register the party through `PartyApi`, flip `member_type` to PARTY, set `member_party_id`, keep every loan column and the reference untouched. Idempotent: promoting an already-promoted member returns it unchanged.

- [ ] **Step 5: Run, then commit.**

---

## Task 3: The exclusions the client confirmed, enforceable without pretending to diagnose

Below the free cover limit nobody is underwritten — and the client's free cover limit of
600,000,000 TZS against loans of 10–20M means **in practice nobody is ever underwritten at
all**. These two exclusions are the entire anti-selection control for this product.

**Read this before writing any code, because the obvious design is wrong.**

`DeathClaimDetails.causeOfDeath` is a **free-text String**, and a credit-life borrower has no
health record anywhere on the platform — they were never underwritten, and the enrolment file
carries a name, a date of birth, a principal, a term and a disbursement date. Nothing else.

So **the platform cannot decide whether a death was suicide, and cannot know about a
pre-existing condition.** A rule of the shape `declineReasonFor(…, ClaimDetails details, …)`
that inspects the details and returns a decline is either dead code or — far worse — a
substring match on free prose deciding a multi-million-shilling payout. A claim narrative
containing the word "suicide" in a sentence ruling it out would decline the claim.

**The split this task is built on: the platform owns the DATES, the assessor owns the
FINDING.** Neither can do the other's job, and the value is in making each one's job
impossible to get wrong.

1. **Compute and surface the window.** At assessment the claim states plainly which exclusion
   windows the date of event falls inside — *"6 months into a 12-month suicide exclusion,
   measured from cover start 2026-08-03"*. A **flag for the assessor, never a decision.**
2. **Gate the decline.** A decline citing `SUICIDE_WITHIN_EXCLUSION` is permitted **only**
   while that window is open. Outside it the platform refuses the reason — which is what stops
   a claim being declined on an exclusion that had already expired, the error that costs a
   lender real money and is invisible in a spreadsheet.
3. **Record which window was invoked**, with the dates it was computed from, so a disputed
   decline can be reconstructed years later from the row rather than from memory.

**Files:**
- Create: `db-migrations/product/V15__exclusion_periods.sql`, `db-migrations/claims/V6__exclusion_decline.sql`,
  `claims/domain/ExclusionWindows.java`, `claims/api/ClaimDeclineReason.java`, `claims/api/OpenExclusionView.java`
- Modify: `claims/application/ClaimsApiImpl.java` (assessment surfaces windows; settlement gates the reason),
  `claims/api/ClaimsApi.java` (`decideSettlement` gains a decline reason), `claims/api/ClaimView.java`
- Test: `claims/ExclusionWindowsTest.java` (pure), and cases in `CreditLifeClaimEndToEndTest`

**Interfaces:**
- Produces `ExclusionWindows.openAt(LocalDate coverStart, LocalDate dateOfEvent, ExclusionPeriods periods) -> Set<ClaimDeclineReason>`
  — **which windows are OPEN on that date.** It never sees `ClaimDetails` and never returns a
  decision; that is the whole point.
- Produces `ClaimDeclineReason` = `SUICIDE_WITHIN_EXCLUSION`, `PRE_EXISTING_WITHIN_EXCLUSION`.
- Consumes `ProductApi` for the two window lengths, and `PolicyMemberView.joinedOn()` as cover
  start — which on credit life is the **disbursement date**, not the date the file arrived.

- [ ] **Step 1: Write the failing pure test**

```java
@Test
void aDeathSixMonthsIntoATwelveMonthSuicideWindowLeavesThatWindowOpen() {
    // OPEN means "the assessor may decline for this reason", NOT "this is suicide".
    assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(6), TWELVE_AND_TWELVE))
        .containsExactlyInAnyOrder(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
                                   ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
}

@Test
void aDeathAfterThirteenMonthsLeavesNoWindowOpen() {
    // Measured from COVER START -- on credit life the disbursement date, not the date the
    // enrolment file reached us, which may be weeks later and would shorten every window.
    assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(13), TWELVE_AND_TWELVE))
        .isEmpty();
}

@Test
void theAnniversaryItselfIsOUTSIDETheWindow() {
    // A boundary somebody will argue in writing one day. Twelve months means twelve months;
    // the first day of the thirteenth is not "within twelve".
    assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(12), TWELVE_AND_TWELVE))
        .isEmpty();
}

@Test
void anOpenWindowIsPERMISSIONToDeclineAndNothingMore() {
    // On day one BOTH windows are open, and that must never be read as "nothing is payable
    // yet". openAt reports which reasons are AVAILABLE to an assessor; it declines nothing,
    // and an ordinary death matches neither reason.
    //
    // The client confirmed there is NO general waiting period (answer 3.4), and the test that
    // actually proves it is end to end: anOrdinaryDeathOnDayOneIsPaidInFull, below. It has to
    // live there, because "is it paid?" is a question about settlement, not about dates.
    assertThat(ExclusionWindows.openAt(COVER_START, COVER_START, TWELVE_AND_TWELVE))
        .containsExactlyInAnyOrder(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
                                   ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
}

@Test
void twoWindowsOfDifferentLengthsCloseIndependently() {
    // The client's answer happens to set both to twelve, but nothing may assume that.
    ExclusionPeriods sixAndTwentyFour = new ExclusionPeriods(6, 24);
    assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(9), sixAndTwentyFour))
        .containsExactly(ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
}

@Test
void aProductWithNoExclusionPeriodsOpensNoWindows() {
    // Every product that exists today. Employer group life and individual business keep
    // exactly their current behaviour, and no decline reason becomes available on them.
    assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusDays(1), ExclusionPeriods.none()))
        .isEmpty();
}
```

- [ ] **Step 2: Run to verify it fails.**

```
cd backend && ./mvnw test -Dtest=ExclusionWindowsTest
```
Expected: FAIL — `ExclusionWindows` does not exist.

- [ ] **Step 3: Write the migrations**

`product/V15`: `suicide_exclusion_months` and `pre_existing_exclusion_months` on
`product_version`, both nullable and both null for every existing row — absent means no
exclusion, which is every product on the platform today.

`claims/V6`: `decline_reason VARCHAR(40)` plus `exclusion_cover_start DATE` and
`exclusion_window_months INTEGER`, with a CHECK that all three are present together or all
absent. A decline that cannot say which window it invoked, measured from when, is a decline
nobody can defend.

- [ ] **Step 4: Implement `ExclusionWindows`** — pure, taking dates and lengths, returning the
open reasons. It must not import `ClaimDetails`.

- [ ] **Step 5: Surface the open windows at assessment**, on `ClaimView`, so the assessor sees
them while deciding rather than after.

- [ ] **Step 6: Gate the decline in `decideSettlement`.** A decline citing an exclusion is
refused unless that window is open on the date of event, and the window's cover start and
length are written onto the claim with it.

```java
@Test
void anExclusionDeclineIsRefusedOnceTheWindowHasClosed() {
    // The error this prevents: declining a fourteen-month-old claim for suicide, on a
    // twelve-month exclusion, which is simply wrong and costs the lender the whole loan.
    assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, false, null, null,
            "assessor believes suicide", ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
            null, idem(), "assessor"))
        .isInstanceOf(ClaimValidationException.class)
        .hasMessageContaining("closed on");
}

@Test
void anOrdinaryDeclineNeedsNoExclusionAndIsUnaffected() {
    // Fraud, non-disclosure, a claim outside cover -- every existing decline path keeps
    // working with no exclusion reason at all.
    ...
}
```

- [ ] **Step 7: Run, then commit.**

---

## Task 4: The money leaves by a rail fit for the amount

**Files:**
- Create: `db-migrations/payment/V5__disbursement_method.sql`, `payment/api/DisbursementMethod.java`
- Modify: `payment/application/PaymentApiImpl.java`, `payment/application/PaymentRequestListener.java`, `finaccounting/domain/PostingRule.java`
- Test: `payment/EftDisbursementIntegrationTest.java`

**Interfaces:** Produces `DisbursementMethod`; `DisbursementInstruction` gains `method`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void anEftDisbursementIsRecordedAndPostedButNeverSentToTheGateway() {
    // THE POINT OF THIS TASK. The only existing rail is a mobile-money gateway against a mock
    // with no authentication. A multi-million-shilling lender payout must not go near it.
    UUID instructionId = paymentApi.requestDisbursement(eftRequest(BANK_ACCOUNT, MILLIONS));

    assertThat(gateway.submittedDisbursements()).isEmpty();
    assertThat(instruction(instructionId).getStatus()).isEqualTo(DisbursementStatus.AWAITING_EXECUTION);
    assertThat(glPostingsFor(instructionId)).isNotEmpty();
}

@Test
void financeMarksAnEftExecutedAndThatIsWhatCompletesIt() {
    // The transfer happens in a bank portal, out of band. What the platform records is that a
    // person confirmed it, and when -- the same shape as reconciling a field receipt.
    ...
}

@Test
void aMobileMoneyDisbursementStillGoesToTheGatewayExactlyAsBefore() {
    // The regression guard. Every existing payout path must be untouched.
    ...
}

@Test
void anEftInstructionNeedsABankAccountAndRefusesAPhoneNumber() { ... }
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement.** `method` on the instruction, defaulted to `MOBILE_MONEY` for every existing row (that is what they were). `PaymentRequestListener` branches: `MOBILE_MONEY` calls the gateway as today; `EFT` records `AWAITING_EXECUTION`, publishes, and calls nothing. A new `markEftExecuted(instructionId, bankReference, executedBy)` closes it.

- [ ] **Step 4: Add the GL posting rule** for an EFT claim payout, mirroring the existing settlement posting.

- [ ] **Step 5: Run, then commit.**

---

## Task 5: A capped claim pays the cap, and the loan comes off cover

**Files:**
- Modify: `claims/application/ClaimsApiImpl.java` (settlement), `CreditLifeClaimEndToEndTest`
- Test: the end-to-end chain

- [ ] **Step 1: Write the failing test**

```java
@Test
void anOrdinaryDeathOnDayOneIsPaidInFull() {
    // THE TEST THAT PROVES THERE IS NO GENERAL WAITING PERIOD (client answer 3.4). Both
    // exclusion windows are open on day one, and an ordinary death is still paid: an open
    // window is permission for an assessor to cite a reason, never a bar on settlement.
    //
    // Getting this wrong is the expensive direction. A platform that quietly treated an open
    // window as "not yet covered" would refuse every early death on a book where nobody is
    // underwritten -- and the lender would be told their borrower was insured.
    ...
    assertThat(claim.status()).isEqualTo(ClaimStatus.SETTLED);
    assertThat(claim.approvedAmount()).isEqualByComparingTo(fullOutstandingOnDayOne);
}

@Test
void aDeathClaimPaysWhatTheBorrowerStillOwedOnTheDayTheyDied() {
    // 2,400,000 over 18 months, disbursed 2026-08-03. Death at month 6 leaves twelve of
    // eighteen months outstanding: 1,600,000 -- not the 2,400,000 they borrowed.
    ...
    assertThat(claim.approvedAmount()).isEqualByComparingTo("1600000.00");
}

@Test
void settlingAClaimTakesTheLoanOffCoverAndRefundsNoPremium() {
    // Already true and already asserted in plan 3; restated here because THIS is the path
    // that produces it, and a regression would be silent money.
    assertThat(memberOf(policyMemberId).status()).isEqualTo(MemberStatus.EXITED);
    assertThat(memberOf(policyMemberId).exitReason()).isEqualTo(ExitReason.CLAIM_SETTLED);
    assertThat(creditsFor(policyMemberId)).isEmpty();
}

@Test
void aCappedBorrowerIsPaidTheCapAndTheResidualIsTheLendersCreditRisk() {
    // Spec 2.7: cover above the free cover limit is capped. The claim pays the cap, closes,
    // and the member exits -- the shortfall is the lender's, which is the entire purpose of
    // a free cover limit. The platform must not silently pay the full debt.
    ...
}
```

- [ ] **Step 2–4: Run, implement what is missing, run again.** Much of this chain already exists — `claimableCover` values the claim and `dischargeForSettledClaim` exits the member. This task is about proving the whole chain end to end on a credit-life scheme and closing whatever the proof exposes.

- [ ] **Step 5: Run the full suite, then commit.**

```bash
cd backend && ./mvnw -q test
```
Expected: **≥ 1388 + the new tests, 0 failures, 0 errors.**

---

## Self-review against the spec

**Spec coverage.** §2.9 claimant and payout → Tasks 1, 4, 5. §2.2 promotion at claim → Task 2. Client answer 3.4 exclusions → Task 3. §2.7 capped cover at claim → Task 5.

**Deliberately NOT in this plan:**
- **Disability and retrenchment.** Death only (§7). `BenefitType.DISABILITY` exists, but temporary disability pays *instalments over time*, which is a claim shape the platform does not have.
- **Reporting and notices** — plan 5, and note the client answered that the borrower is never told anything (answer 3.6), so there is no borrower-facing notification to build.
- **Group cession of the payout.** Reinsurance recovery on a scheme is held for the same reason cession is: the treaty model cannot express group provisions. A credit-life claim will recover nothing until that build happens.
- **Premium netting** — settling the bank's premium against the insurer's claims through one monthly account (§7). Not before the first claim is paid.

**One thing a reviewer should push back on if they disagree:** Task 3 lets the platform
*refuse* an exclusion decline once the window has closed. That is the platform overruling a
human assessor, which it does nowhere else, and someone could reasonably argue an assessor
should be free to decline for any reason they can defend and the platform should only record
it.

The argument for the gate is that an expired exclusion is a **factual error, not a
judgement**: the death was fourteen months after cover started and the exclusion ran twelve,
and no amount of assessor expertise changes those dates. It is also the error most likely to
go unnoticed — it costs the lender a whole loan, produces a plausible-looking declined claim,
and nothing downstream would ever question it. If the gate is removed, the window must at
least still be surfaced and recorded, or a disputed decline cannot be reconstructed at all.

**What changed from the first draft of this plan, and why:** Task 3 originally had
`declineReasonFor(…, ClaimDetails details, …)` returning a decline — the platform deciding
whether a death was suicide. That cannot work. `causeOfDeath` is free text and a credit-life
borrower has no health record anywhere, because nobody is underwritten. The rule would have
been dead code, or a substring match on prose deciding a multi-million-shilling payout, where
a narrative *ruling out* suicide contains the word and declines the claim. The platform owns
the dates; the assessor owns the finding.
