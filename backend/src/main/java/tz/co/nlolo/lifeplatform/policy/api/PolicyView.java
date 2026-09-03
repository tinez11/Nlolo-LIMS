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
                          UUID lifeAssuredPartyId) {}
