package tz.co.nlolo.lifeplatform.party.api;

import java.time.Instant;

/**
 * One KYC decision in this party's history, newest first.
 *
 * <p>Answers the question the client record could not: not "is this client verified" — the party's
 * own {@code kycStatus} says that — but <b>who decided it, against which document, and when</b>,
 * including the decisions that were later overturned. {@code Party.applyKycDecision} assigns the
 * new status with no guard on the old one, so a client may be rejected on Monday and verified on
 * Friday, and the party row remembers only Friday.
 *
 * <p>Called {@code decidedBy} / {@code decidedAt} rather than the table's own
 * {@code verified_by} / {@code verified_at}: a REJECTED row is a decision and is not a
 * verification, and a reader should not have to translate.
 *
 * @param status what was decided
 * @param evidenceDocumentRef the document the decision was recorded against. Always present —
 *     the platform refuses a decision without one — and fetchable through
 *     {@code GET /documents/{documentRef}} by staff, which is who may read this list.
 * @param decidedBy the deciding login's Keycloak subject. The identity, not the label.
 * @param decidedByName how to show that person, captured from their token at the time (V7), or
 *     null for a decision taken before V7 or without a token. Null means "not recorded" and
 *     never "nobody".
 * @param decidedAt when the decision was recorded
 */
public record KycDecisionView(
    KycStatus status,
    String evidenceDocumentRef,
    String decidedBy,
    String decidedByName,
    Instant decidedAt) {}
