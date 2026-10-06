package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * The `reinsurance` module's public surface: treaty authoring (staff/finance-only) plus the read
 * paths and the one imperative action -- confirming a recovery -- that back-office staff need.
 *
 * <p>Cession and recovery CALCULATION is not here: both are event-driven
 * ({@code PolicyEventListener}, {@code ClaimEventListener}), because `reinsurance` may call only
 * `refdata` synchronously and therefore learns about policies and claims exclusively by event.
 */
public interface ReinsuranceApi {

    /**
     * {@code commissionPercent} (IFRS 17 I3c, K-02): the reinsurer's commission not contingent on claims, stated on
     * every treaty -- 0 is a real answer, null is refused. {@code xolAnnualPremium}: an XOL treaty's flat yearly
     * premium, charged 1/12 a month; XOL only, optional.
     */
    record CreateTreatyRequest(String reinsurerName, TreatyType treatyType,
                                BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                                BigDecimal cessionPercent, BigDecimal commissionPercent, BigDecimal xolAnnualPremium,
                                LocalDate effectiveFrom, LocalDate effectiveTo) {

        /** A treaty with no reinsurance commission and no XOL premium -- what a caller predating I3c meant. */
        public CreateTreatyRequest(String reinsurerName, TreatyType treatyType, BigDecimal retentionLimitAmount,
                                   String retentionLimitCurrency, BigDecimal cessionPercent, LocalDate effectiveFrom,
                                   LocalDate effectiveTo) {
            this(reinsurerName, treatyType, retentionLimitAmount, retentionLimitCurrency, cessionPercent,
                BigDecimal.ZERO, null, effectiveFrom, effectiveTo);
        }
    }

    TreatyView createTreaty(CreateTreatyRequest request, String createdBy);
    TreatyView getTreaty(UUID treatyId);
    List<TreatyView> listTreaties(TreatyStatus status);

    List<CessionView> listCessionsForPolicy(String policyNumber);

    /**
     * What has been ceded TO one treaty, newest first.
     *
     * <p>Cessions could previously be reached only through the policy they were made on, so a
     * treaty could not show its own book: the platform held 267 cessions, each carrying a
     * {@code treatyId}, and no query joined them to the treaty that accepted them.
     *
     * <p>Paged, unlike {@link #listCessionsForPolicy}. A policy has a handful of cessions; a
     * treaty gains one per policy it covers for as long as it runs.
     */
    Page<CessionView> listCessionsForTreaty(UUID treatyId, Pageable pageable);

    /**
     * The treaty's totals, summed in the database.
     *
     * <p>Separate from the paged list on purpose: a caller must never sum the page in hand and
     * call it the treaty's utilisation. That number is wrong in the way nobody notices -- always
     * too small, always plausible.
     */
    TreatyUtilisationView getTreatyUtilisation(UUID treatyId);
    /**
     * A claim's recoveries. Since IFRS 17 I3c each is calculated and posted when the claim is approved (B-05); there
     * is no Confirm -- the amount is agreed with the reinsurer on its statement.
     */
    List<ClaimRecoveryView> listRecoveriesForClaim(UUID claimId);

    /** The treaty's monthly bordereaux, newest month first (IFRS 17 I3c). */
    List<BordereauView> listBordereaux(UUID treatyId);

    /** One bordereau with its lines. */
    BordereauView getBordereau(UUID bordereauId);

    /**
     * What a month's bordereau for this treaty would hold, computed now and not stored -- the current month before
     * it closes, or any month. A month already posted returns the posted one.
     */
    BordereauView previewBordereau(UUID treatyId, java.time.YearMonth period);

    // ---- the quarterly statement (IFRS 17 I3d) ---------------------------------------------------------------------

    /**
     * A DRAFT settling the treaty's {@code quarter} (YYYY-Qn) from its bordereaux and recoveries. Refused (409) before
     * the quarter ends, while a month's bordereau is unwritten, or when the quarter already has a live statement.
     */
    StatementView prepareStatement(UUID treatyId, String quarter, String preparer);

    /** What the reinsurer's statement states, and why; a DRAFT, by its preparer. */
    StatementView updateStatement(UUID statementId, BigDecimal fundsWithheld, BigDecimal profitCommission, String reason,
                                  String by);

    StatementView submitStatement(UUID statementId, String by);

    StatementView withdrawStatement(UUID statementId, String by);

    /** Checked again, then approved -- never by its preparer -- and published as reinsurance.StatementApproved. */
    StatementView approveStatement(UUID statementId, String approver);

    StatementView rejectStatement(UUID statementId, String reason, String by);

    StatementView getStatement(UUID statementId);

    /** Newest first; null filters are ignored. At most 500. */
    List<StatementView> listStatements(String status, UUID treatyId);

    /** Refuses unless {@code by} may still change it -- asked before a document is stored. */
    void requireStatementEditable(UUID statementId, String by);

    /** The reinsurer's statement, already stored (document::api), recorded on a DRAFT. */
    StatementView attachStatementDocument(UUID statementId, String documentRef, String by);
}
