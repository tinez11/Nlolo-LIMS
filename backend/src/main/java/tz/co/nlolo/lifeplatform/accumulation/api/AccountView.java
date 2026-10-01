package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AccountView(String policyNumber, UUID productId, UUID productVersionId, AccountStatus status,
                          BigDecimal balance, String currency, LocalDate openedOn, String closedReason,
                          LocalDate closedOn) {}
