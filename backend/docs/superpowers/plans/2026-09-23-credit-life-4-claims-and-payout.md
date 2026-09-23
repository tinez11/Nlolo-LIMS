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

## Task 3: The exclusions the client confirmed, actually enforced

Below the free cover limit nobody is underwritten — and the client's answer of 600,000,000 TZS against loans of 10–20M means **in practice nobody is ever underwritten at all**. These two exclusions are the entire anti-selection control for this product.

**Files:**
- Create: `db-migrations/product/V15__exclusion_periods.sql`, `db-migrations/claims/V6__exclusion_decline.sql`, `claims/domain/ExclusionRules.java`, `claims/api/ClaimDeclineReason.java`
- Modify: `claims/application/ClaimsApiImpl.java`
- Test: `claims/ExclusionRulesTest.java` (pure), and cases in `CreditLifeClaimEndToEndTest`

**Interfaces:** Produces `ExclusionRules.declineReasonFor(LocalDate coverStart, LocalDate dateOfEvent, ClaimDetails details, ExclusionPeriods periods) -> Optional<ClaimDeclineReason>`.

- [ ] **Step 1: Write the failing pure test**

```java
@Test
void suicideInsideTheTwelveMonthWindowIsDeclined() {
    // Cover started 2026-08-03; death by suicide 2027-02-03, six months in.
    assertThat(ExclusionRules.declineReasonFor(COVER_START, COVER_START.plusMonths(6),
            suicide(), TWELVE_AND_TWELVE))
        .contains(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION);
}

@Test
void suicideAfterTheWindowIsCovered() {
    // The window is measured from COVER START, which on credit life is the disbursement date
    // -- not from the claim, and not from when the file reached us.
    assertThat(ExclusionRules.declineReasonFor(COVER_START, COVER_START.plusMonths(13),
            suicide(), TWELVE_AND_TWELVE)).isEmpty();
}

@Test
void suicideExactlyOnTheAnniversaryIsCovered() {
    // A boundary somebody will argue about in writing one day. Twelve months means twelve
    // months; the thirteenth month is not "within twelve".
    assertThat(ExclusionRules.declineReasonFor(COVER_START, COVER_START.plusMonths(12),
            suicide(), TWELVE_AND_TWELVE)).isEmpty();
}

@Test
void aPreExistingConditionInsideItsWindowIsDeclined() { ... }

@Test
void thereIsNoGENERALWaitingPeriod() {
    // Explicitly confirmed by the client. An ordinary death on day one is COVERED, and a
    // platform that quietly applied a general waiting period would decline it.
    assertThat(ExclusionRules.declineReasonFor(COVER_START, COVER_START,
            ordinaryDeath(), TWELVE_AND_TWELVE)).isEmpty();
}

@Test
void aProductWithNoExclusionPeriodsDeclinesNothing() {
    // Employer group life and individual products keep exactly their current behaviour.
    assertThat(ExclusionRules.declineReasonFor(COVER_START, COVER_START.plusDays(1),
            suicide(), ExclusionPeriods.none())).isEmpty();
}
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Write the migrations.** `suicide_exclusion_months` and `pre_existing_exclusion_months` on `product_version`, both nullable (absent = no exclusion, which is every product that exists today). `claim` gains `decline_reason` and the window it failed, so a declined claim can say *why* in words the lender can act on.

- [ ] **Step 4: Implement `ExclusionRules` and call it from the assessment path**, not from registration: a claim that is going to be declined must still be *registered*, assessed and recorded. Declining at registration would leave no trace that the claim was ever made, which is the one thing a disputed decline must be able to prove.

- [ ] **Step 5: Run, then commit.**

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

**One thing a reviewer should push back on if they disagree:** Task 3 declines at assessment rather than at registration. The argument is that a declined claim must still exist as a record — but it does mean a claim that is certain to fail still consumes an assessment step.
