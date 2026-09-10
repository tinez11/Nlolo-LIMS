package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Auto-issuance trigger -- openapi-policy.yaml's own description: "normal issuance is
 * system-triggered by consuming UnderwritingDecisionMade internally." AFTER_COMMIT, mirroring
 * audit.DomainEventAuditListener's established pattern: underwriting's decision must actually
 * be durable before policy acts on it.
 *
 * The event payload alone (caseId, outcome, loadingPercent, decidedAt) is NOT enough to issue a
 * policy -- it carries none of applicantPartyId/productId/productVersionId/sumAssured. This
 * listener calls UnderwritingApi.getCase(caseId) synchronously to pull the full decided case
 * (made possible by this task's UnderwritingCaseView extension), exercising the
 * policy -> underwriting allowedDependencies edge the design docs provision but never spell out
 * a concrete use for.
 *
 * <p>The call into {@code policyApi.issuePolicy(...)} MUST run inside a brand-new transaction
 * (PROPAGATION_REQUIRES_NEW), not the plain {@code @Transactional} REQUIRED that
 * {@code PolicyApiImpl.issuePolicy} declares on its own -- for exactly the reason
 * {@code DomainEventAuditListener}'s own javadoc documents: at AFTER_COMMIT time the
 * producer's (underwriting's) transaction has physically committed but Spring's
 * TransactionSynchronizationManager hasn't unbound its resources yet, so a plain REQUIRED
 * call here would silently "join" that already-committed transaction instead of opening a new
 * one -- issuePolicy's writes would run, throw nothing, and never actually be committed
 * anywhere (this exact failure mode was caught empirically: this listener's first draft found
 * 0 policy rows after a real end-to-end submitAssessment call, with no exception anywhere).
 */
@Component
public class UnderwritingDecisionEventListener {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingDecisionEventListener.class);

    private final UnderwritingApi underwritingApi;
    private final PolicyApi policyApi;
    private final ReferenceDataApi referenceDataApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public UnderwritingDecisionEventListener(UnderwritingApi underwritingApi, PolicyApi policyApi, ReferenceDataApi referenceDataApi,
                                              PlatformTransactionManager transactionManager) {
        this.underwritingApi = underwritingApi;
        this.policyApi = policyApi;
        this.referenceDataApi = referenceDataApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * How many premium instalments a year, for turning an annual premium into one.
     *
     * <p>Mirrors {@code policy.policy}'s own CHECK, which admits exactly MONTHLY, QUARTERLY and
     * ANNUALLY. An unrecognised value falls back to monthly rather than throwing: this runs in
     * an AFTER_COMMIT listener that swallows its exceptions to a log line, so throwing here
     * would silently issue no policy at all — a far worse outcome than an instalment computed
     * on the commonest frequency. The CHECK refuses the write anyway if the value is genuinely
     * bad, which surfaces it loudly instead.
     */
    private static int instalmentsPerYear(String premiumFrequency) {
        return switch (premiumFrequency) {
            case "QUARTERLY" -> 4;
            case "ANNUALLY" -> 1;
            default -> 12;
        };
    }

    /**
     * The proposal's nominations, in the shape {@code policy} names beneficiaries.
     *
     * <p>A mapping and not an interpretation: {@code underwriting.api.BeneficiaryNomination} and
     * {@code policy.api.BeneficiaryInput} are field-for-field identical, and exist separately
     * only because underwriting must not depend on policy — policy already depends on
     * underwriting, and the cycle would be immediate. This listener is on the policy side, so
     * it is the one place that may see both.
     */
    private static List<PolicyApi.BeneficiaryInput> nominationsAsBeneficiaries(UnderwritingCaseView decidedCase) {
        return decidedCase.beneficiaries().stream()
            .map(n -> new PolicyApi.BeneficiaryInput(
                BeneficiaryType.valueOf(n.type().name()),
                n.partyId(), n.freeformDesignee(), n.sharePercent(), n.revocable()))
            .toList();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"underwriting.UnderwritingDecisionMade".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        Object outcome = payload.get("outcome");
        // Per docs/03-aggregate-design.md: ACCEPT and LOADED (rated-up-but-accepted) both
        // result in issuance; DECLINED/POSTPONED never do.
        if (!DecisionOutcome.ACCEPT.name().equals(outcome) && !"LOADED".equals(outcome)) {
            return;
        }
        UUID caseId = (UUID) payload.get("caseId");
        // AFTER_COMMIT listeners run synchronously on the SAME thread as the original caller
        // (Spring registers this as a same-thread TransactionSynchronization, not a hand-off to
        // another thread) -- so this is NOT necessarily an otherwise-empty ThreadLocal. Save
        // whatever TenantContext the calling thread already had (its own ambient tenant, most
        // often none in production but frequently something in an integration test that issues
        // further calls on the same thread right after submitAssessment returns) and restore it
        // in finally, rather than unconditionally clearing -- an unconditional clear() here was
        // caught wiping out a caller's own still-in-use TenantContext immediately after
        // submitAssessment returned, breaking every subsequent same-thread call that assumed it
        // was still set.
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                UnderwritingCaseView decidedCase = underwritingApi.getCase(caseId);
                // No actuarial rating engine exists anywhere in this codebase (M2's
                // SimpleRulesEngine is a deliberate placeholder). Global Constraints (M4):
                // annualPremium = sumAssured * (baseRatePerMille/1000) * ratingMultiplier
                //                            * (1 + loadingPercent/100),
                // divided into MONTHLY instalments for the automatic-issuance path -- manual
                // issuance (POST /policies/manual-issue) instead accepts staff's own agreed
                // premium directly, since that path already represents a human override.
                // decisionLoadingPercent is already a BigDecimal (UnderwritingCaseView's real
                // declared type, confirmed by reading the file -- not the Integer/boxed-wrapper
                // the brief's own sketch assumed), so only a null-guard is needed here, no
                // BigDecimal.valueOf(...) conversion.
                BigDecimal baseRatePerMille = new BigDecimal(referenceDataApi.getValue("TZ_BASE_PREMIUM_RATE_PER_MILLE", "TZ"));
                BigDecimal loadingPercent = decidedCase.decisionLoadingPercent() != null ? decidedCase.decisionLoadingPercent() : BigDecimal.ZERO;
                BigDecimal loadingMultiplier = BigDecimal.ONE.add(loadingPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                // THE RATING TABLE, which until now reached no premium at all.
                //
                // product.rating_table has carried real per-band multipliers since M1, and
                // underwriting resolves the applicant's age band and sum assured band against
                // it on every assessment -- then dropped the result on the floor. The formula
                // here was sumAssured x baseRate x (1 + loading), and the only way any of that
                // rating could show up in a price was as a loading, because SimpleRulesEngine
                // used to express the multiplier AS one. Which meant that on an ACCEPT -- no
                // loading by definition -- a 25-year-old and a 55-year-old buying identical
                // cover were charged an identical premium, and the rating table's entire
                // contribution to this platform was choosing a decision outcome.
                //
                // The multiplier and the loading are now separate terms, and neither
                // double-counts the other: the engine no longer derives loading from the
                // multiplier (it derives it from the assessment risk scores), so the rating
                // sits in exactly one place in this product.
                //
                // NULL for a case last assessed before underwriting V8. Neutral, not an error:
                // 1.0 is precisely what those cases were priced at, so an old case reissued
                // today prices the same way it always did rather than failing.
                BigDecimal ratingMultiplier = decidedCase.ratingMultiplier() != null
                    ? decidedCase.ratingMultiplier() : BigDecimal.ONE;
                BigDecimal annualPremium = decidedCase.sumAssuredAmount()
                    .multiply(baseRatePerMille).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                    .multiply(ratingMultiplier)
                    .multiply(loadingMultiplier);

                // The instalment follows the frequency the applicant asked for.
                //
                // This used to divide by 12 unconditionally and hardcode "MONTHLY" below, which
                // was harmless only because nothing captured a frequency. Now that a proposal
                // can say QUARTERLY, dividing by twelve anyway would bill a quarterly payer a
                // monthly figure -- a defect introduced by capturing the field rather than by
                // ignoring it.
                String premiumFrequency = decidedCase.premiumFrequency() != null
                    ? decidedCase.premiumFrequency() : "MONTHLY";
                BigDecimal instalmentPremium = annualPremium.divide(
                    BigDecimal.valueOf(instalmentsPerYear(premiumFrequency)), 2, RoundingMode.HALF_UP);
                // agentOfRecordId now comes from the case (underwriting V2). It used to be
                // hardcoded null here, with the note that no such field existed on the
                // aggregate -- which was true and was a money bug: distribution's
                // PolicyEventListener returns early on a null agentOfRecordId ("sold direct --
                // no commission to accrue"), so NO commission ever accrued on an automatically
                // issued policy. Commission fired only on POST /policies/manual-issue, the
                // staff exception path, which is backwards from how the business works.
                //
                // Still null for a genuine direct sale, which is a real state and the reason
                // the bug was silent rather than loud.
                //
                // beneficiaries stay empty: designation genuinely happens post-issuance via
                // PUT .../beneficiaries, and an underwriting case carries none.
                // The case has carried lifeAssuredPartyId and proposedCommencementDate since
                // underwriting V4, and this call used the pre-Build-2 ELEVEN-argument
                // constructor, which fills the last four with nulls. Two consequences, both
                // silent:
                //
                // Every automatically issued policy recorded the POLICYHOLDER as the life
                // assured -- wrong for exactly the business lifeAssuredPartyId was added for.
                // ProposalDetails' own javadoc says group business and credit life "are
                // structurally impossible to express without this", and a parent insuring a
                // child is the everyday case. IssueRequest.resolveLifeAssured() then resolved
                // the null to the policyholder, so the column looked answered and was wrong.
                //
                // And no commencement date reached the policy, so it had no term and no
                // maturity date either -- Policy.applyTerm derives maturity from commencement.
                //
                // The term and the nominations come from the proposal now (underwriting V6).
                // This comment used to read "nothing on a case records a requested term yet",
                // which was true and meant a policy issued on the NORMAL path had no term, no
                // maturity date -- Policy.applyTerm derives it from commencement plus term --
                // and nobody nominated, while the staff exception path collected all three.
                // That is very likely why staff reached for manual issue.
                PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
                    decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                    decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(),
                    instalmentPremium, decidedCase.sumAssuredCurrency(), premiumFrequency,
                    decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
                    "Automatic issuance on underwriting decision " + outcome,
                    decidedCase.proposedCommencementDate(),
                    decidedCase.requestedTermMonths(), decidedCase.premiumPayingTermMonths(),
                    decidedCase.lifeAssuredPartyId());
                policyApi.issuePolicy(caseId, request, "system:underwriting-decision-listener");
            });
        } catch (Exception e) {
            // AFTER_COMMIT -- underwriting's own transaction already committed; there is
            // nothing left to roll back here. audit.DomainEventAuditListener has already
            // durably recorded the raw UnderwritingDecisionMade event regardless of whether
            // this listener succeeds, so the decision itself is never lost -- only automatic
            // issuance needs a manual retry (via /policies/manual-issue) if this path fails. No
            // dead-letter queue is built for this listener specifically in M3.
            log.error("Automatic policy issuance failed for underwriting case {}", caseId, e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
