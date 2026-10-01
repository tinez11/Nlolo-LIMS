package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day's batch, as the person releasing it sees it.
 *
 * <p>{@code total} is computed by the server. The console never sums the instalments itself: the
 * figure someone approves has to be the figure the server will pay, and two independent additions
 * of the same list are two chances to disagree.
 */
public record PaymentRunView(UUID paymentRunId, LocalDate runDate, String status, String approvedBy,
                             int instalmentCount, BigDecimal total, String currency) {}
