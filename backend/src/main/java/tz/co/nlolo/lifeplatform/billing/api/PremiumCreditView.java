package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Premium given back off an invoice because a member left before the cover it paid for ran out.
 *
 * <p>Its own record, never a reduction of the invoice: the invoice says what was charged, the
 * credit what came back off it -- the trail a lender argument is settled with.
 *
 * @param originalInvoiceId the invoice it is credited against -- the one raised by the file
 *     that charged this member
 * @param exitReason why the member left, as policy recorded it (e.g. SETTLED_EARLY)
 */
public record PremiumCreditView(UUID creditId, String policyNumber, UUID policyMemberId, UUID originalInvoiceId,
                                BigDecimal amount, String currency, String exitReason, LocalDate exitDate,
                                Instant createdAt) {}
