package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.policy.api.NotASingleLifeProductException;
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
import java.time.LocalDate;
import java.time.Period;
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

    /**
     * The column is TEXT, so this is about the reader rather than the database. A stack-trace
     * message long enough to fill a screen is one nobody finishes, and the part that names the
     * product and the band comes first.
     */
    private static final int MAX_FAILURE_REASON_LENGTH = 1000;

    /** "Today" is the civil date, never UTC's (the UTC-vs-civil day bug). */
    private static final java.time.ZoneId CIVIL_ZONE = java.time.ZoneId.of("Africa/Dar_es_Salaam");

    private final UnderwritingApi underwritingApi;
    private final PolicyApi policyApi;
    private final ReferenceDataApi referenceDataApi;
    /** Reads the life assured's age, sex and smoker status -- the key to a base rate cell. */
    private final PartyApi partyApi;
    /** Reads the product's own rate table, which automatic issuance never used before. */
    private final ProductApi productApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public UnderwritingDecisionEventListener(UnderwritingApi underwritingApi, PolicyApi policyApi, ReferenceDataApi referenceDataApi,
                                              PartyApi partyApi, ProductApi productApi,
                                              PlatformTransactionManager transactionManager) {
        this.underwritingApi = underwritingApi;
        this.policyApi = policyApi;
        this.referenceDataApi = referenceDataApi;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * How many premium instalments a year, for turning an annual premium into one.
     *
     * <p>Mirrors {@code policy.policy}'s own CHECK, which admits MONTHLY, QUARTERLY, ANNUALLY
     * and SINGLE. An unrecognised value falls back to monthly rather than throwing: this runs
     * in an AFTER_COMMIT listener that swallows its exceptions to a log line, so throwing here
     * would silently issue no policy at all — a far worse outcome than an instalment computed
     * on the commonest frequency. The CHECK refuses the write anyway if the value is genuinely
     * bad, which surfaces it loudly instead.
     *
     * <p>SINGLE returns 0, matching {@code PremiumFrequency.SINGLE}, and the caller MUST branch
     * on it rather than divide. It is not folded into the {@code default} because a single
     * premium divided by twelve is a twelfth of the price, written to a live contract, with
     * nothing anywhere to notice.
     */
    private static int instalmentsPerYear(String premiumFrequency) {
        return switch (premiumFrequency) {
            case "QUARTERLY" -> 4;
            case "ANNUALLY" -> 1;
            case "SINGLE" -> 0;
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

    /**
     * Turn a decided group case into a scheme offer.
     *
     * <p>This method is the ONE place allowed to see both {@code underwriting.api}'s group
     * types and {@code policy.api}'s. They are deliberately two identical sets of records —
     * underwriting may not depend on policy, because policy already depends on underwriting
     * and the cycle would be immediate — so the mapping below is a boundary crossing, not
     * duplication to be tidied away. The same arrangement already exists for
     * {@code BeneficiaryNomination} / {@code BeneficiaryInput}, a few lines down.
     *
     * <p>Null issuance basis: an ordinary offer. The employer accepts by paying the first
     * premium, exactly as an individual customer does.
     */
    private void issueSchemeFromProposal(UnderwritingCaseView decidedCase) {
        GroupProposal proposal = decidedCase.groupProposal();
        policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
            decidedCase.agentOfRecordId(),
            BenefitBasis.valueOf(proposal.benefitBasis().name()),
            proposal.flatBenefitAmount(), proposal.salaryMultiple(), proposal.fclAmount(),
            proposal.currency(),
            proposal.grades().stream()
                .map(g -> new PolicyApi.GradeInput(g.gradeCode(), g.benefitAmount())).toList(),
            proposal.openingSchedule().stream()
                // joinedOn null: every life on an OPENING schedule joins when the scheme
                // commences, which issueGroupScheme resolves from the commencement date.
                .map(m -> new PolicyApi.MemberInput(m.memberPartyId(), m.gradeCode(), m.salaryAmount(), null))
                .toList(),
            // The premium AGREED with the employer, verbatim. Not computed: see the branch
            // that sent us here.
            proposal.premiumAmount(), proposal.premiumCurrency(), proposal.premiumFrequency(),
            proposal.commencementDate(), proposal.policyTermMonths(),
            "Issued on underwriting decision " + decidedCase.caseId(),
            null),
            // The case itself, not only its id in a sentence: the scheme records the decision
            // that put it on risk, and one case can issue one scheme.
            "system:underwriting-decision-listener", decidedCase.caseId(), null);
    }

    /**
     * THE PRODUCT'S OWN RATE, not one flat number for the whole platform.
     *
     * <p>Every automatically issued policy used to be priced from a single
     * {@code TZ_BASE_PREMIUM_RATE_PER_MILLE} in reference data. An actuary could author a full
     * mortality table — age bands, sex, smoker status, real rates — publish it, see it on the
     * product screen, and watch it change no premium at all. {@code quotePremium} read the table;
     * nothing that issued a contract did. So an illustration and the policy the customer actually
     * got were priced by two different mechanisms, which is the defect the quote breakdown exists
     * to prevent.
     *
     * <p><b>A priced version with no cell for this life is REFUSED, not defaulted.</b> A hole in
     * the table means the actuary did not price that combination, and filling it with a platform
     * default sells cover nobody costed. The refusal now lands on the case where an underwriter
     * can read it, rather than in a log — so "we never priced women under 56" surfaces as a
     * sentence naming the age and sex.
     *
     * <p>An UNPRICED version still uses the flat reference rate. Most of this platform's products
     * carry no base rate table at all, and that is a real product shape rather than a gap.
     */
    private BigDecimal baseRatePerMilleFor(UnderwritingCaseView decidedCase) {
        if (!productApi.isPriced(decidedCase.productVersionId())) {
            return new BigDecimal(referenceDataApi.getValue("TZ_BASE_PREMIUM_RATE_PER_MILLE", "TZ"));
        }

        // The LIFE ASSURED's mortality is what a rate table prices, and on a parent insuring a
        // child that is not the applicant. Null means the applicant insures themselves.
        UUID lifeAssuredId = decidedCase.lifeAssuredPartyId() != null
            ? decidedCase.lifeAssuredPartyId() : decidedCase.applicantPartyId();
        PartyDetailView life = partyApi.getPartyDetail(lifeAssuredId);

        if (life.dateOfBirth() == null) {
            throw new IllegalStateException("Product version " + decidedCase.productVersionId()
                + " is priced from a base rate table, but the life assured on case "
                + decidedCase.caseId() + " has no recorded date of birth, so there is no age to"
                + " price. Record a date of birth on the client, then issue by hand.");
        }
        int ageAtEntry = Period.between(life.dateOfBirth(), LocalDate.now()).getYears();

        // party.api.Sex -> product.api.Sex, by name. Two enums for one concept, because neither
        // module may depend on the other -- the same boundary crossing as BeneficiaryNomination
        // above, and mapped in the same place for the same reason: policy is allowed to see both.
        return productApi.resolveBaseRatePerMille(decidedCase.productVersionId(), ageAtEntry,
                life.sex() != null
                    ? tz.co.nlolo.lifeplatform.product.api.Sex.valueOf(life.sex().name()) : null,
                // An unrecorded smoker status is UNKNOWN, not absent. SmokerStatus.UNKNOWN exists
                // precisely so a product can price the undeclared case deliberately, and passing
                // null made that cell unreachable from the only path that issues a contract --
                // authored, visible on the product screen, and pricing nothing.
                //
                // Sex has no such value on purpose: a third value there would be a unisex rate,
                // which is a different actuarial object needing its own table and its own
                // sign-off. So an unrecorded sex still refuses below.
                life.smokerStatus() != null
                    ? tz.co.nlolo.lifeplatform.product.api.SmokerStatus.valueOf(life.smokerStatus().name())
                    : tz.co.nlolo.lifeplatform.product.api.SmokerStatus.UNKNOWN,
                // The term the applicant asked for, so a term-banded version prices this contract on
                // the right band (V16). Null for a product that does not term; a term-banded version
                // then finds no cell and refuses, which is correct -- it was not priced for "no term".
                decidedCase.requestedTermMonths())
            .orElseThrow(() -> new IllegalStateException("Product version "
                + decidedCase.productVersionId() + " has no base rate for age " + ageAtEntry
                + ", sex " + life.sex() + ", smoker status "
                + (life.smokerStatus() != null ? life.smokerStatus().name() : "UNKNOWN (never recorded)")
                + ". The rate table does not cover this life, so there is no price to charge —"
                + " either the table has a gap, the client's sex was never recorded, or this"
                + " product does not price the undeclared smoker case. All three are fixable;"
                + " guessing a rate is not."));
    }

    /**
     * Write the failure onto the case, and never let doing so hide the original failure.
     *
     * <p>The nested try is the whole point. If underwriting is unreachable — which is a very
     * plausible reason issuance failed in the first place — then recording WHY it failed will
     * fail too, and an exception thrown out of a catch block would replace the real cause with
     * the bookkeeping error. The log keeps both.
     *
     * <p>The reason is the exception's own message, not a summary: the message is what names the
     * product, the band and the constraint, and a tidied version of it would send the reader back
     * to the log this exists to replace. Truncated only because the column is TEXT but a screen
     * is not.
     */
    private void recordIssuanceFailure(UUID caseId, Exception cause) {
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        if (reason.length() > MAX_FAILURE_REASON_LENGTH) {
            reason = reason.substring(0, MAX_FAILURE_REASON_LENGTH - 3) + "...";
        }
        try {
            underwritingApi.recordIssuanceFailure(caseId, reason);
        } catch (Exception recordingFailure) {
            log.error("Could not record the issuance failure on underwriting case {} -- the case "
                + "will not show that its policy was never created", caseId, recordingFailure);
        }
    }

    /** @see #recordIssuanceFailure(UUID, Exception) for why this cannot be allowed to throw. */
    private void clearIssuanceFailure(UUID caseId) {
        try {
            underwritingApi.recordIssuanceFailure(caseId, null);
        } catch (Exception e) {
            log.warn("Could not clear the issuance-failure marker on underwriting case {}", caseId, e);
        }
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
        // result in issuance; DECLINED/POSTPONED never do. A DECLINE still matters for one kind
        // of case -- a scheme member's evidence case, where it is the answer the member's record
        // is waiting for -- so it is let through and dropped below if the case is anything else.
        boolean issuesOnDecision = DecisionOutcome.ACCEPT.name().equals(outcome) || "LOADED".equals(outcome);
        boolean declined = DecisionOutcome.DECLINED.name().equals(outcome);
        if (!issuesOnDecision && !declined) {
            return;
        }
        UUID caseId = (UUID) payload.get("caseId");
        String decidedBy = payload.get("decidedBy") instanceof String s ? s : "system:underwriting-decision-listener";
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

                // A MEMBER'S EVIDENCE, NOT A PROPOSAL. Its decision grants or refuses one scheme
                // member's excess over the free cover limit. It used to fall through to the
                // single-life path below and issue that member a separate policy on the scheme's
                // product, while their own record stayed EVIDENCE_REQUIRED and a decline recorded
                // nothing. Named on the case since underwriting V11; found from the member's side
                // for a case opened before that.
                //
                // Only a scheme product can carry an evidence case, so the member-side lookup for
                // an older case is made on those alone -- not on every individual decision.
                ProductCategory category = productApi.getSnapshotByVersionId(decidedCase.productVersionId()).category();
                boolean schemeProduct = category == ProductCategory.GROUP_LIFE || category == ProductCategory.CREDIT_LIFE;
                PolicyApi.MemberRef evidenceFor = decidedCase.evidenceForPolicyNumber() != null
                    ? new PolicyApi.MemberRef(decidedCase.evidenceForPolicyNumber(), decidedCase.evidenceForMemberId())
                    : schemeProduct && !decidedCase.groupScheme()
                        ? policyApi.findMemberAwaitingEvidence(caseId).orElse(null)
                        : null;
                if (evidenceFor != null) {
                    PolicyApi.MemberEvidenceResult result = policyApi.recordMemberEvidenceDecision(
                        evidenceFor.policyNumber(), evidenceFor.policyMemberId(), caseId, issuesOnDecision, decidedBy);
                    if (result == PolicyApi.MemberEvidenceResult.NOT_NEEDED) {
                        log.warn("Evidence case {} was decided after member {} of {} stopped waiting on it "
                            + "(exited, or a raised limit covers them); nothing to change",
                            caseId, evidenceFor.policyMemberId(), evidenceFor.policyNumber());
                    }
                    return;
                }
                if (!issuesOnDecision) {
                    return; // an ordinary decline issues nothing
                }

                if (decidedCase.groupScheme()) {
                    // A SCHEME, NOT A POLICY. Everything below this line prices ONE LIFE from
                    // an age band and a sum assured band, and neither means anything for a
                    // company: the age it would read is the employer's, and a scheme's sum
                    // assured is five hundred people's cover added together -- which is why a
                    // group case carries none at all until this issuance derives it.
                    //
                    // A scheme's premium was agreed with the employer and is carried on the
                    // proposal, so it is used verbatim.
                    issueSchemeFromProposal(decidedCase);
                    return;
                }
                // THE BACKSTOP. Everything below issues ONE life, and a group or credit-life
                // product issued that way is a contract covering nobody. Underwriting refuses to
                // open such a case now, and an evidence case was dealt with above -- so reaching
                // here on a scheme product means a case older than both, and it is refused onto
                // the case as an issuance failure rather than turned into a policy.
                if (schemeProduct) {
                    throw new NotASingleLifeProductException(category.name());
                }
                // A FAMILY, NOT A LIFE (family funeral cover). The premium is the one funeral quote's
                // instalment for the whole family, never the per-mille formula below; the sum assured is
                // the main member's benefit (R3). Its lives are written in this same transaction, so a
                // funeral policy can never exist without the family it was sold to.
                if (category == ProductCategory.FUNERAL) {
                    tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication application =
                        underwritingApi.funeralApplication(caseId).orElseThrow(() -> new IllegalStateException(
                            "Funeral case " + caseId + " was accepted with no application"));
                    if (application.quote() == null) {
                        throw new IllegalStateException("The family on funeral case " + caseId
                            + " no longer prices on plan " + application.planCode() + "; re-record the application");
                    }
                    tz.co.nlolo.lifeplatform.product.api.FuneralQuote quote = application.quote();
                    tz.co.nlolo.lifeplatform.policy.api.PolicyView issued = policyApi.issuePolicy(caseId, new PolicyApi.IssueRequest(
                        decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                        quote.mainMemberBenefit(), decidedCase.sumAssuredCurrency(),
                        quote.instalment(), decidedCase.sumAssuredCurrency(), quote.frequency().name(),
                        decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
                        "Automatic issuance on underwriting decision " + outcome,
                        decidedCase.proposedCommencementDate(), null, null, decidedCase.lifeAssuredPartyId()),
                        "system:underwriting-decision-listener");
                    policyApi.recordCoveredLives(issued.policyNumber(), application, "system:underwriting-decision-listener");
                    return;
                }
                // A deferred annuity saves first (product step 5 D2): an account policy whose
                // contributions run to the target date, with no policy term -- after vesting it pays
                // for life, so it has no maturity (plan R2). The case's sum assured is the
                // contribution per payment (plan R3).
                AnnuityPlan annuityPlan = productApi.resolveAnnuityPlan(decidedCase.productVersionId());
                if (annuityPlan.deferred()) {
                    LocalDate commencement = decidedCase.proposedCommencementDate() != null
                        ? decidedCase.proposedCommencementDate() : LocalDate.now(CIVIL_ZONE);
                    LocalDate target = underwritingApi.deferredAnnuityChoice(caseId).orElseThrow().targetDate();
                    String frequency = decidedCase.premiumFrequency() != null ? decidedCase.premiumFrequency() : "MONTHLY";
                    Integer payingMonths = "SINGLE".equals(frequency) ? null
                        : (int) java.time.temporal.ChronoUnit.MONTHS.between(commencement, target);
                    policyApi.issuePolicy(caseId, new PolicyApi.IssueRequest(
                        decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(), frequency,
                        decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
                        "Automatic issuance on underwriting decision " + outcome,
                        commencement, null, payingMonths, decidedCase.lifeAssuredPartyId()),
                        "system:underwriting-decision-listener");
                    return;
                }
                // An annuity is bought, not rated (product step 5): its premium is the purchase
                // price, paid once, and it has no term. The income is priced by the annuity module
                // when the money arrives -- underwriting already proved it prices at acceptance.
                if (annuityPlan.annuity()) {
                    policyApi.issuePolicy(caseId, new PolicyApi.IssueRequest(
                        decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(), "SINGLE",
                        decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
                        "Automatic issuance on underwriting decision " + outcome,
                        decidedCase.proposedCommencementDate(), null, null,
                        decidedCase.lifeAssuredPartyId()),
                        "system:underwriting-decision-listener");
                    return;
                }
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
                BigDecimal baseRatePerMille = baseRatePerMilleFor(decidedCase);
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

                // What the product charges for paying in instalments, applied exactly as
                // quotePremium applies it -- through FrequencyLoading.applyTo, which is the one
                // place this arithmetic lives. Dividing the annual premium exactly, as this did,
                // charged a monthly payer the same total as an annual one, which no real life
                // insurer does. An illustration and the first invoice for the same life must be
                // one number, and they were not.
                FrequencyLoading frequencyLoading =
                    productApi.resolveFrequencyLoading(decidedCase.productVersionId());
                BigDecimal loadedAnnualPremium = frequencyLoading.applyTo(
                    annualPremium, PremiumFrequency.valueOf(premiumFrequency));

                // SINGLE is charged once, so the instalment IS the loaded annual figure. It
                // branches rather than dividing by the 0 that says "this contract has no
                // instalments", which would throw inside an AFTER_COMMIT listener and issue
                // nothing at all.
                int instalments = instalmentsPerYear(premiumFrequency);
                BigDecimal instalmentPremium = instalments == 0
                    ? loadedAnnualPremium.setScale(2, RoundingMode.HALF_UP)
                    : loadedAnnualPremium.divide(BigDecimal.valueOf(instalments), 2, RoundingMode.HALF_UP);
                // A fixed-term deposit's premium is the deposit itself: the formula above prices
                // risk, and a deposit is not priced. issuePolicy refuses it unless SINGLE.
                if (productApi.resolveDepositPlan(decidedCase.productVersionId()).isDeposit()) {
                    instalmentPremium = decidedCase.sumAssuredAmount();
                }

                // THE FORMULA CHECKS ITS OWN OUTPUT, because one of its inputs was nil and the
                // only thing that noticed was a CHECK constraint three layers down.
                //
                // A product went out with its AGE band multiplier at 0.0000. The premium came to
                // 0.00, chk_premium_amount_positive refused the insert, and the exception
                // surfaced as a constraint violation from inside an AFTER_COMMIT listener --
                // which says nothing about which product, which band, or why. Refused here
                // instead, naming the terms, so whoever reads it knows what to correct.
                //
                // Deliberately checked on the OUTPUT rather than on the rating multiplier alone:
                // a nil base rate does the same damage, and so would a sum assured small enough
                // to round to zero at two decimal places.
                if (instalmentPremium.signum() <= 0) {
                    throw new IllegalStateException("Automatic issuance computed a premium of "
                        + instalmentPremium.toPlainString() + " " + decidedCase.sumAssuredCurrency()
                        + " for case " + caseId + ", which is not a premium anyone can be billed."
                        + " Sum assured " + decidedCase.sumAssuredAmount().toPlainString()
                        + ", base rate per mille " + baseRatePerMille.toPlainString()
                        + ", rating multiplier " + ratingMultiplier.toPlainString()
                        + ", loading multiplier " + loadingMultiplier.toPlainString()
                        + ". Check the product version's rating table: a multiplier of zero in the"
                        + " applicant's band prices every policy in it at nothing.");
                }
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
            // Issued. Clear any failure this case is still carrying from an earlier attempt, so
            // a warning that has been dealt with stops being shown. A stale one teaches people
            // to scroll past the field, which is exactly how the log line below came to be
            // ignored for as long as it was.
            clearIssuanceFailure(caseId);
        } catch (Exception e) {
            // AFTER_COMMIT -- underwriting's own transaction already committed; there is
            // nothing left to roll back here. audit.DomainEventAuditListener has already
            // durably recorded the raw UnderwritingDecisionMade event regardless of whether
            // this listener succeeds, so the decision itself is never lost -- only automatic
            // issuance needs a manual retry (via /policies/manual-issue) if this path fails.
            log.error("Automatic policy issuance failed for underwriting case {}", caseId, e);
            // AND ON THE CASE ITSELF, which is the half that was missing.
            //
            // This used to end at the log line above, with a comment noting that no dead-letter
            // queue existed "for this listener specifically in M3". The consequence was found in
            // production rather than in review: a case decided ACCEPT whose premium computed to
            // zero left a stack trace in a log file and a case on screen that looked exactly like
            // a successful one. Nobody was told, no retry was triggered, and the customer had no
            // policy. A retry nobody is instructed to perform is not a recovery path.
            //
            // Still not a dead-letter queue and still not an automatic retry -- reissuing on a
            // timer would hammer a product misconfiguration that no amount of retrying fixes.
            // What this does is make the failure visible to the person who caused the decision,
            // where they are already looking.
            recordIssuanceFailure(caseId, e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
