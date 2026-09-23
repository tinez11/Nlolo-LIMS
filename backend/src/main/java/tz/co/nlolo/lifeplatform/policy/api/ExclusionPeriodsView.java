package tz.co.nlolo.lifeplatform.policy.api;

import java.time.LocalDate;

/**
 * When a life went on risk, and how long this policy's exclusion windows run from that date.
 *
 * <p>Exposed by {@code policy} rather than read from {@code product} because <b>claims may not
 * depend on product</b> — its allowed dependencies are policy, underwriting, party, document
 * and refdata, and one product type in claims' bytecode is a module violation
 * {@code ModularityTests} rejects. Policy already depends on product, and is the only module
 * that knows both the product version a policy was issued on and the date a given member
 * joined.
 *
 * <p>Cover start and the window lengths travel together because they are useless apart: a
 * window is a length measured FROM something, and getting that something wrong is the error
 * with real money attached.
 *
 * @param coverStart on a scheme, the member's own join date — for credit life the loan's
 *     DISBURSEMENT date, not when the enrolment file arrived, which is weeks later and would
 *     silently shorten every window in the lender's favour. On individual business, the
 *     policy's commencement.
 * @param suicideMonths null where the product has no such exclusion — every product before
 *     credit life, whose behaviour is unchanged.
 */
public record ExclusionPeriodsView(LocalDate coverStart,
                                    Integer suicideMonths,
                                    Integer preExistingMonths) {}
