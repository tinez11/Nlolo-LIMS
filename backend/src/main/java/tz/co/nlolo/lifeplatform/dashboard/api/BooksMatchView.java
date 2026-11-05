package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;

/**
 * Do the books match? (money movement step 7, design §12.) Each check sets what the ledger says beside what the records
 * it summarises say; {@code allMatch} only when every difference is zero.
 *
 * @param checks in a fixed order, so a script reading them can name the one that failed
 */
public record BooksMatchView(boolean allMatch, List<Check> checks) {

    /**
     * One comparison. Amounts are decimal strings in TZS, the working currency, except where {@code unit} says it is
     * a count.
     */
    public record Check(String name, String ledger, String records, String difference, String unit, boolean matches) {}
}
