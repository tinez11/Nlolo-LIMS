package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a unit-linked case is being sold (product step 6, U1): how each premium is split across the version's
 * funds (whole percents totalling 100), the premium the customer chose and how often, and their sum assured.
 * Issuance uses the premium and sum assured verbatim -- a unit-linked policy is not rated (plan R3).
 */
public record UnitLinkedChoice(List<Split> split, BigDecimal premium, String frequency, BigDecimal sumAssured) {

    public record Split(String fundCode, int percent) {}

    public UnitLinkedChoice {
        split = split == null ? List.of() : List.copyOf(split);
    }
}
