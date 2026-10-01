package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One cost the insurer withheld from a free-look refund.
 *
 * @param documentId the invoice or report that justifies it, where one exists
 */
public record FreeLookDeductionInput(String description, BigDecimal amount, UUID documentId) {}
