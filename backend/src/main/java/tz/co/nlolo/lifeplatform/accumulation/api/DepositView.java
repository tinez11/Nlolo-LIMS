package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A fixed-term deposit as the console shows it. {@code periods} oldest first; {@code instruction}
 * the current one for the running term, or null; {@code interestSoFar} earned and not posted;
 * {@code termsOffered} those of the version active for new business today -- what a reinvestment
 * may choose.
 */
public record DepositView(String policyNumber, String currency, List<DepositPeriodView> periods,
                          MaturityInstructionView instruction, BigDecimal interestSoFar, String defaultPayeeRef,
                          boolean awaitingPayee, List<Integer> termsOffered,
                          /** The running term worked forward (2026-10-09); null when no term runs. */
                          DepositScheduleView schedule) {}
