package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One movement settling an approved reinsurance statement (2026-11-08): money received from the reinsurer, money paid to
 * it, profit commission received, or funds withheld released to it. Recorded by one finance person, confirmed by a
 * second -- or by the recorder holding reinsurance.approve-own, then {@code actedAlone}.
 *
 * <p>Status: AWAITING_APPROVAL; then POSTED (in the ledger), or for PAID, REQUESTED (an EFT under "Payments to make")
 * then PAID or FAILED; or REFUSED, saying why.
 */
public record SettlementView(UUID settlementId, UUID statementId, UUID treatyId, String reinsurerName, String quarter,
                             String kind, BigDecimal amount, String currency, String method, String reference,
                             LocalDate receivedOn, String payeeBank, String payeeBranch, String payeeAccountName,
                             String payeeAccountNumber, String note, String status, String recordedBy, Instant recordedAt,
                             String decidedBy, Instant decidedAt, String decisionReason, boolean actedAlone) {

    /**
     * What an approved statement still has open, after every settlement not refused or failed -- a waiting one counts,
     * so the same money is never recorded twice -- and the settlements themselves, newest first.
     *
     * @param theyOweUs           the current account's balance in our favour: at most this may be received
     * @param weOweThem           the current account's balance in theirs: at most this may be paid
     * @param profitCommissionDue profit commission the statement booked and the reinsurer has not yet paid
     * @param withheldHeld        funds withheld on the statement not yet released
     */
    public record Open(String statementStatus, String currency, BigDecimal theyOweUs, BigDecimal weOweThem,
                       BigDecimal profitCommissionDue, BigDecimal withheldHeld, List<SettlementView> settlements) {}

    /** What a finance person records; which fields count depends on the kind. */
    public record NewSettlement(String kind, BigDecimal amount, String method, String reference, LocalDate receivedOn,
                                String payeeBank, String payeeBranch, String payeeAccountName, String payeeAccountNumber,
                                String note) {}
}
