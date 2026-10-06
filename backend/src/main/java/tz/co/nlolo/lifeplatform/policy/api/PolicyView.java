package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PolicyView(String policyNumber, UUID underwritingCaseId, UUID policyholderPartyId, UUID productId, UUID productVersionId,
                          UUID agentOfRecordId, PolicyStatus status, LocalDate issueDate,
                          BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                          BigDecimal cashValueAmount, String cashValueCurrency,
                          BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                          List<BeneficiaryView> beneficiaries,
                          // The policy term (V6). Null on every policy issued before it, and on
                          // products that do not term at all -- whole life, annuities, an annually
                          // renewable group scheme. maturityDate is derived at issue and stored, so
                          // a reader never has to do date arithmetic to answer "when does this
                          // mature", and a sweep can index it.
                          LocalDate commencementDate, Integer policyTermMonths,
                          Integer premiumPayingTermMonths, LocalDate maturityDate,
                          // Who is insured, as opposed to who owns the contract (V7). Equal to
                          // policyholderPartyId on a self-insured policy, which is the common case
                          // but not the only one -- and a death claim is assessed against this one.
                          UUID lifeAssuredPartyId,
                          // What kind of contract this is, copied from the product at
                          // issuance and stored since M3 -- but never exposed, so nothing
                          // reading a policy could tell an individual term-life contract
                          // from a 500-life group scheme. A reader has to know: a GROUP_LIFE
                          // policy's lives are its member schedule, and its "life assured" is
                          // deliberately null rather than missing.
                          String productCategory,
                          // How it came to be issued (V24): the basis and reason on an exception
                          // route, and who issued it by name -- the underwriter of record for a
                          // scheme set up from agreed terms. All null on ordinary business.
                          String issuanceBasis, String issuanceReason, String issuedByName,
                          // IFRS 17 I2 (V37): classified at sale and never changed. Null only on a policy
                          // issued before I2 that the development backfill has not reached.
                          String portfolioCode, Integer cohortYear, String profitabilityBucket,
                          String measurementModelOverride, String salesChannel, String branchCode) {}
