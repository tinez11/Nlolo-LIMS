package tz.co.nlolo.lifeplatform.reinsurance.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The IFRS 17 group of a reinsurance contract held (IFRS 17 I5a, para 61): one per treaty and the year it began,
 * {@code RI-<first 8 of the treaty id>-<year>}, e.g. {@code RI-AB12CD34-2026}. Measured apart from the policy groups it
 * covers; the engine's P-16/P-17 lines and the bordereau, recovery and statement postings are attributed to it.
 */
public final class ReinsuranceGroupKey {

    public static final String PREFIX = "RI-";

    private ReinsuranceGroupKey() {}

    public static String of(UUID treatyId, LocalDate effectiveFrom) {
        return PREFIX + treatyId.toString().substring(0, 8).toUpperCase() + "-" + effectiveFrom.getYear();
    }

    public static boolean isReinsurance(String groupKey) {
        return groupKey != null && groupKey.startsWith(PREFIX);
    }
}
