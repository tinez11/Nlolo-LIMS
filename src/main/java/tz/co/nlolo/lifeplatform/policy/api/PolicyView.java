package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PolicyView(String policyNumber, UUID policyholderPartyId, UUID productId, UUID productVersionId,
                          UUID agentOfRecordId, PolicyStatus status, LocalDate issueDate,
                          BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                          BigDecimal cashValueAmount, String cashValueCurrency,
                          List<BeneficiaryView> beneficiaries) {}
