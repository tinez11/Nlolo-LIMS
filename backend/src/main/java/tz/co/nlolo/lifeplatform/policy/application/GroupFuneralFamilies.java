package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.GroupFuneralFamilyView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi.GroupFuneralLifeInput;
import tz.co.nlolo.lifeplatform.policy.domain.CoveredLife;
import tz.co.nlolo.lifeplatform.policy.domain.FuneralPolicy;
import tz.co.nlolo.lifeplatform.policy.infrastructure.FuneralPolicyRepository;
import tz.co.nlolo.lifeplatform.policy.domain.GroupFuneralMember;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMember;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMemberBenefit;
import tz.co.nlolo.lifeplatform.policy.infrastructure.CoveredLifeRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.GroupFuneralMemberRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyMemberBenefitRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyMemberRepository;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The families on a group funeral scheme (2026-10-07): checked against the plan's role rules, then written as one
 * member per main member (what the bill counts), the main member's beneficiary beside it, and every life of the family
 * a covered life linked to that member at its plan benefit. Its own component, as {@link CoveredLives} is, so
 * PolicyApiImpl does not grow; called only from inside PolicyApiImpl's transactions.
 */
@Component
class GroupFuneralFamilies {

    private final PolicyMemberRepository members;
    private final PolicyMemberBenefitRepository memberBenefits;
    private final GroupFuneralMemberRepository funeralMembers;
    private final CoveredLifeRepository lives;
    private final FuneralPolicyRepository funeralPolicies;
    private final ProductApi productApi;
    private final CoveredLives coveredLives;
    private final ApplicationEventPublisher eventPublisher;

    GroupFuneralFamilies(PolicyMemberRepository members, PolicyMemberBenefitRepository memberBenefits,
                         GroupFuneralMemberRepository funeralMembers, CoveredLifeRepository lives,
                         FuneralPolicyRepository funeralPolicies, ProductApi productApi, CoveredLives coveredLives,
                         ApplicationEventPublisher eventPublisher) {
        this.members = members;
        this.memberBenefits = memberBenefits;
        this.funeralMembers = funeralMembers;
        this.lives = lives;
        this.funeralPolicies = funeralPolicies;
        this.productApi = productApi;
        this.coveredLives = coveredLives;
        this.eventPublisher = eventPublisher;
    }

    String planCode(Policy policy) {
        return coveredLives.funeralPolicy(policy).getPlanCode();
    }

    /** The scheme's plan, on the row an individual funeral policy keeps its plan in. */
    void recordPlan(String policyNumber, String planCode) {
        funeralPolicies.saveAndFlush(new FuneralPolicy(TenantContext.get(), policyNumber, planCode));
    }

    /** One family: the association's number for it and its lives, the main member among them. */
    record Family(String reference, List<GroupFuneralLifeInput> lives) {
        GroupFuneralLifeInput mainMember() {
            return lives.stream().filter(l -> l.role() == FuneralRole.MAIN_MEMBER).findFirst().orElseThrow();
        }
    }

    /** The lives grouped by member reference, in the order each reference first appears. */
    static List<Family> families(List<GroupFuneralLifeInput> lives) {
        Map<String, List<GroupFuneralLifeInput>> byReference = new LinkedHashMap<>();
        for (GroupFuneralLifeInput life : lives) {
            if (life.memberReference() == null || life.memberReference().isBlank()) {
                throw new InvalidPolicyStateException((life.fullName() != null ? life.fullName() : "A life")
                    + " has no member reference, so it belongs to no family");
            }
            byReference.computeIfAbsent(life.memberReference().trim(), k -> new ArrayList<>()).add(life);
        }
        return byReference.entrySet().stream().map(e -> new Family(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    /**
     * Every family checked by the plan's role rules on {@code asOf}, every problem named with its member, before
     * anything is written. A schedule with one bad family is refused whole, so nothing half-joins.
     */
    void requireAdmissible(UUID productVersionId, String planCode, LocalDate asOf, List<Family> families) {
        List<String> problems = new ArrayList<>();
        for (Family family : families) {
            List<FuneralLifeInput> inputs = family.lives().stream()
                .map(l -> new FuneralLifeInput(l.role(), l.fullName(), l.dateOfBirth(), l.student())).toList();
            productApi.funeralFamilyProblems(productVersionId, planCode, asOf, inputs)
                .forEach(p -> problems.add("Member " + family.reference() + ": " + p));
        }
        if (!problems.isEmpty()) {
            throw new InvalidPolicyStateException(String.join("; ", problems));
        }
    }

    /**
     * One family's problems joining {@code policy} on {@code asOf}, in the plan's words: its role rules, and a member
     * reference already on the scheme. Empty when it may join.
     */
    List<String> problems(Policy policy, String planCode, LocalDate asOf, Family family) {
        List<String> problems = new ArrayList<>();
        if (funeralMembers.findByTenantIdAndPolicyNumberAndAssociationReference(TenantContext.get(),
                policy.getPolicyNumber(), family.reference()).isPresent()) {
            problems.add("Member " + family.reference() + " is already on scheme " + policy.getPolicyNumber());
        }
        problems.addAll(productApi.funeralFamilyProblems(policy.getProductVersionId(), planCode, asOf,
            family.lives().stream().map(l -> new FuneralLifeInput(l.role(), l.fullName(), l.dateOfBirth(), l.student()))
                .toList()));
        return problems;
    }

    /** What the family is covered for in all: each life's plan benefit, summed. */
    static BigDecimal familyCover(FuneralPlan plan, String planCode, Family family) {
        return family.lives().stream().map(l -> benefitOf(plan, planCode, l.role()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Writes one family joining on {@code joinedOn}: its main member's member row and benefit (the family's cover),
     * the association's number and the beneficiary, and a covered life per person. Returns the member's id.
     */
    UUID record(Policy policy, FuneralPlan plan, String planCode, Family family, LocalDate joinedOn, boolean announce,
                String by) {
        UUID tenantId = TenantContext.get();
        String policyNumber = policy.getPolicyNumber();
        if (funeralMembers.findByTenantIdAndPolicyNumberAndAssociationReference(tenantId, policyNumber, family.reference())
                .isPresent()) {
            throw new InvalidPolicyStateException("Member " + family.reference() + " is already on scheme " + policyNumber);
        }
        GroupFuneralLifeInput main = family.mainMember();
        PolicyMember member = members.saveAndFlush(PolicyMember.freeform(tenantId, policyNumber, main.fullName(),
            main.dateOfBirth(), null, joinedOn, MemberUnderwritingStatus.WITHIN_FCL, by));
        BigDecimal cover = familyCover(plan, planCode, family);
        memberBenefits.save(new PolicyMemberBenefit(tenantId, member.getPolicyMemberId(), joinedOn, null, cover, cover, by));
        funeralMembers.save(new GroupFuneralMember(tenantId, policyNumber, member.getPolicyMemberId(), family.reference(),
            blankToNull(main.beneficiaryName()), blankToNull(main.beneficiaryRelationship()),
            blankToNull(main.beneficiaryPhone())));
        // The main member first, then the dependants in the order given -- the order the scheme page lists them.
        List<GroupFuneralLifeInput> ordered = new ArrayList<>(family.lives());
        ordered.sort((a, b) -> Boolean.compare(b.role() == FuneralRole.MAIN_MEMBER, a.role() == FuneralRole.MAIN_MEMBER));
        for (GroupFuneralLifeInput life : ordered) {
            // The yearly premium is nil on every scheme life: the association pays per member, not per life, and
            // the individual policy's restatement sums exactly this column.
            CoveredLife saved = lives.save(newLife(policyNumber, plan, planCode, life, joinedOn, member.getPolicyMemberId(), by));
            if (announce) {
                coveredLives.publishLife("policy.CoveredLifeAdded", policy, saved, Map.of("coverStart", joinedOn.toString()));
            }
        }
        return member.getPolicyMemberId();
    }

    private static CoveredLife newLife(String policyNumber, FuneralPlan plan, String planCode, GroupFuneralLifeInput life,
                                       LocalDate coverStart, UUID memberId, String by) {
        return new CoveredLife(TenantContext.get(), policyNumber, life.role().name(), life.fullName(), life.dateOfBirth(),
            blankToNull(life.sex()), blankToNull(life.idNumber()), life.student(), null,
            benefitOf(plan, planCode, life.role()), BigDecimal.ZERO,
            Period.between(life.dateOfBirth(), coverStart).getYears(), coverStart, by)
            .inFamilyOf(memberId);
    }

    // ---- an issued scheme's families ----

    /**
     * A life joins a member's family from {@code today}: its entry rules and its role's count beside the family's
     * lives still covered, the family's cover restated from today. Never the main member.
     */
    tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView addLife(Policy policy, UUID policyMemberId, GroupFuneralLifeInput life,
                                                                LocalDate today, String by) {
        PolicyMember member = activeMember(policy, policyMemberId);
        if (life == null || life.fullName() == null || life.fullName().isBlank()) {
            throw new InvalidPolicyStateException("A covered life needs a full name");
        }
        if (life.role() == null || life.dateOfBirth() == null) {
            throw new InvalidPolicyStateException(life.fullName() + " needs a role and a date of birth");
        }
        int alreadyInRole = (int) familyOn(policy.getPolicyNumber(), policyMemberId, today).stream()
            .filter(l -> l.getRole().equals(life.role().name())).count();
        String planCode = planCode(policy);
        List<String> problems = productApi.funeralJoinerProblems(policy.getProductVersionId(), planCode, today,
            new FuneralLifeInput(life.role(), life.fullName(), life.dateOfBirth(), life.student()), alreadyInRole);
        if (!problems.isEmpty()) {
            throw new InvalidPolicyStateException(String.join("; ", problems));
        }
        FuneralPlan plan = productApi.resolveFuneralPlan(policy.getProductVersionId());
        CoveredLife added = lives.save(newLife(policy.getPolicyNumber(), plan, planCode, life, today,
            member.getPolicyMemberId(), by));
        coveredLives.publishLife("policy.CoveredLifeAdded", policy, added, Map.of("coverStart", today.toString()));
        restateFamilyCover(policy.getPolicyNumber(), policyMemberId, today, by);
        return CoveredLives.toView(added, plan);
    }

    /**
     * A dependant comes off at the end of this month -- covered for the month the association has paid -- and the
     * family's cover falls from then. The main member leaves only with the member (an exit).
     */
    tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView removeLife(Policy policy, UUID coveredLifeId, LocalDate today,
                                                                   String by) {
        CoveredLife life = coveredLives.lifeOn(policy, coveredLifeId);
        if (life.getPolicyMemberId() == null) {
            throw new InvalidPolicyStateException("Covered life " + coveredLifeId + " belongs to no member's family");
        }
        if (FuneralRole.MAIN_MEMBER.name().equals(life.getRole())) {
            throw new InvalidPolicyStateException("The main member cannot be removed from their family; the member leaves the scheme instead");
        }
        if (!life.isActive() || life.getCoverEnd() != null) {
            throw new InvalidPolicyStateException(life.getFullName() + " is already coming off cover");
        }
        LocalDate effective = today.with(java.time.temporal.TemporalAdjusters.firstDayOfNextMonth());
        life.scheduleEnd(effective, "REMOVED");
        lives.save(life);
        restateFamilyCover(policy.getPolicyNumber(), life.getPolicyMemberId(), effective, by);
        return CoveredLives.toView(life, productApi.resolveFuneralPlan(policy.getProductVersionId()));
    }

    /** Every life of a leaving member's family comes off cover on {@code effective}, unless it already ends sooner. */
    void endFamily(Policy policy, UUID policyMemberId, LocalDate effective) {
        activeMember(policy, policyMemberId);
        for (CoveredLife life : lives.findByPolicy(TenantContext.get(), policy.getPolicyNumber())) {
            if (policyMemberId.equals(life.getPolicyMemberId()) && life.isActive()
                    && (life.getCoverEnd() == null || life.getCoverEnd().isAfter(effective))) {
                life.scheduleEnd(effective, "REMOVED");
                lives.save(life);
            }
        }
    }

    /**
     * The bill (R3): the plan's group rate for every member covered on {@code effective} -- a billing date -- published
     * for billing to restate from then, with "N members x rate" as its reason. Nothing when unchanged, and nothing
     * when nobody is left: the scheme closes with its last member.
     */
    void restateBill(Policy policy, LocalDate effective) {
        FuneralPlan plan = productApi.resolveFuneralPlan(policy.getProductVersionId());
        String planCode = planCode(policy);
        BigDecimal rate = plan.groupMonthlyRate(planCode).orElseThrow(() -> new InvalidPolicyStateException(
            "Plan " + planCode + " has no group rate, so scheme " + policy.getPolicyNumber() + " cannot be billed"));
        long count = members.countCoveredOn(TenantContext.get(), policy.getPolicyNumber(), effective);
        if (count == 0) {
            return;
        }
        BigDecimal premium = rate.multiply(BigDecimal.valueOf(count)).setScale(2, java.math.RoundingMode.HALF_UP);
        if (premium.compareTo(policy.getPremiumAmount()) == 0) {
            return;
        }
        policy.restateFuneralPremium(premium);
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", policy.getPolicyNumber());
        payload.put("policyholderPartyId", policy.getPolicyholderPartyId());
        payload.put("productVersionId", policy.getProductVersionId());
        payload.put("premiumAmount", Map.of("amount", premium.toPlainString(), "currencyCode", policy.getPremiumCurrency()));
        payload.put("effectiveFrom", effective.toString());
        payload.put("reason", count + (count == 1 ? " member x " : " members x ") + rate.toPlainString());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PremiumRestated", TenantContext.get(), payload));
    }

    // ---- claims on a scheme life ----

    /** Whose death it is and who stands to be paid: the family's main member and the beneficiary they named. */
    tz.co.nlolo.lifeplatform.policy.api.FuneralClaimFacts claimFacts(Policy policy, UUID coveredLifeId) {
        CoveredLife life = coveredLives.lifeOn(policy, coveredLifeId);
        UUID memberId = life.getPolicyMemberId();
        CoveredLife main = lives.findByPolicy(TenantContext.get(), policy.getPolicyNumber()).stream()
            .filter(l -> memberId != null && memberId.equals(l.getPolicyMemberId())
                && FuneralRole.MAIN_MEMBER.name().equals(l.getRole()))
            // The main member who died is ENDED; a spouse who took over is ACTIVE. The current head first.
            .sorted(java.util.Comparator.comparing((CoveredLife l) -> !l.isActive()))
            .findFirst().orElse(null);
        GroupFuneralMember facts = memberId != null ? funeralMembers.findById(memberId).orElse(null) : null;
        FuneralPlan plan = productApi.resolveFuneralPlan(policy.getProductVersionId());
        return new tz.co.nlolo.lifeplatform.policy.api.FuneralClaimFacts(life.getRole(), policy.getPolicyholderPartyId(),
            plan.dependantClaimPayee().name(), List.of(), true, main != null ? main.getPartyId() : null,
            main != null ? main.getFullName() : null, facts != null ? facts.getBeneficiaryName() : null);
    }

    // ---- a settled death claim on a scheme life ----

    enum DeathOutcome { ALREADY_DISCHARGED, DEPENDANT_ENDED, SPOUSE_TOOK_OVER, MEMBER_LEAVES }

    record Discharge(DeathOutcome outcome, UUID policyMemberId) {}

    /**
     * A settled death claim on one scheme life. A dependant ends and the family's cover falls; the bill does not move.
     * The main member ends and the version's rule decides the family: the spouse takes it over -- the member row now
     * names them, cover goes on, the bill is unchanged -- when the rule says so and a spouse is covered; otherwise
     * every other life of the family comes off at the end of the month of death, and the caller takes the member off.
     */
    Discharge dischargeDeath(Policy policy, UUID coveredLifeId, LocalDate dateOfEvent, String by) {
        CoveredLife life = coveredLives.lifeOn(policy, coveredLifeId);
        UUID memberId = life.getPolicyMemberId();
        if (memberId == null) {
            throw new InvalidPolicyStateException("Covered life " + coveredLifeId + " belongs to no member's family");
        }
        if (!life.isActive()) {
            return new Discharge(DeathOutcome.ALREADY_DISCHARGED, memberId); // a redelivered settlement
        }
        LocalDate today = CoveredLives.today();
        life.end("DECEASED", dateOfEvent);
        lives.save(life);
        coveredLives.publishLife("policy.CoveredLifeEnded", policy, life,
            Map.of("endReason", "DECEASED", "endedOn", dateOfEvent.toString()));
        if (!FuneralRole.MAIN_MEMBER.name().equals(life.getRole())) {
            restateFamilyCover(policy.getPolicyNumber(), memberId, today, by);
            return new Discharge(DeathOutcome.DEPENDANT_ENDED, memberId);
        }
        FuneralPlan plan = productApi.resolveFuneralPlan(policy.getProductVersionId());
        if (plan.onMainMemberDeath() == tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule.SPOUSE_TAKES_OVER) {
            java.util.Optional<CoveredLife> spouse = familyOn(policy.getPolicyNumber(), memberId, today).stream()
                .filter(l -> FuneralRole.SPOUSE.name().equals(l.getRole()) && l.getCoverEnd() == null).findFirst();
            if (spouse.isPresent()) {
                CoveredLife takingOver = spouse.get();
                // The association stays policyholder, so nothing waits on an identity document: the spouse heads the
                // family now, at no premium of their own. Their benefit stays the one their cover was set at, as an
                // individual policy's takeover keeps it.
                takingOver.becomeMainMember(takingOver.getPartyId(), BigDecimal.ZERO,
                    Period.between(takingOver.getDateOfBirth(), today).getYears());
                lives.save(takingOver);
                PolicyMember member = members.findByPolicyMemberIdAndTenantId(memberId, TenantContext.get()).orElseThrow();
                member.takenOverBy(takingOver.getFullName(), takingOver.getDateOfBirth());
                members.save(member);
                funeralMembers.findById(memberId).ifPresent(f -> {
                    f.takenOver();
                    funeralMembers.save(f);
                });
                restateFamilyCover(policy.getPolicyNumber(), memberId, today, by);
                return new Discharge(DeathOutcome.SPOUSE_TOOK_OVER, memberId);
            }
        }
        endFamily(policy, memberId, dateOfEvent.with(java.time.temporal.TemporalAdjusters.firstDayOfNextMonth()));
        return new Discharge(DeathOutcome.MEMBER_LEAVES, memberId);
    }

    /** Every active member's family cover restated as at {@code day}; whether any changed. */
    boolean restateCovers(Policy policy, LocalDate day, String by) {
        boolean changed = false;
        for (PolicyMember member : members.findByTenantIdAndPolicyNumberOrderByJoinedOnAscCreatedAtAsc(
                TenantContext.get(), policy.getPolicyNumber())) {
            if ("ACTIVE".equals(member.getStatus())) {
                changed |= restateFamilyCover(policy.getPolicyNumber(), member.getPolicyMemberId(), day, by);
            }
        }
        return changed;
    }

    /**
     * The member's benefit row -- the family's cover, what the scheme's total sums -- restated from {@code from} to
     * the benefits of the lives still covered then. A row already starting that day is replaced (one row per day).
     */
    boolean restateFamilyCover(String policyNumber, UUID policyMemberId, LocalDate from, String by) {
        UUID tenantId = TenantContext.get();
        BigDecimal cover = familyOn(policyNumber, policyMemberId, from).stream().map(CoveredLife::getBenefit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (cover.signum() == 0) {
            return false; // the family is leaving with its member; the exit ends the member's cover
        }
        List<PolicyMemberBenefit> history = memberBenefits.findByTenantIdAndPolicyMemberIdOrderByEffectiveFromDesc(
            tenantId, policyMemberId);
        java.util.Optional<PolicyMemberBenefit> inForce = history.stream()
            .filter(b -> !b.getEffectiveFrom().isAfter(from)).findFirst();
        if (inForce.isPresent() && inForce.get().getBenefitAmount().compareTo(cover) == 0) {
            return false;
        }
        history.stream().filter(b -> b.getEffectiveFrom().equals(from)).forEach(b -> {
            memberBenefits.delete(b);
            memberBenefits.flush();
        });
        memberBenefits.save(new PolicyMemberBenefit(tenantId, policyMemberId, from, null, cover, cover, by));
        return true;
    }

    /** Every family on the scheme, in the order they joined, each with its lives (the main member first). */
    List<GroupFuneralFamilyView> list(Policy policy) {
        UUID tenantId = TenantContext.get();
        FuneralPlan plan = productApi.resolveFuneralPlan(policy.getProductVersionId());
        Map<UUID, GroupFuneralMember> facts = new HashMap<>();
        funeralMembers.findByTenantIdAndPolicyNumber(tenantId, policy.getPolicyNumber())
            .forEach(f -> facts.put(f.getPolicyMemberId(), f));
        Map<UUID, List<CoveredLife>> byMember = new HashMap<>();
        lives.findByPolicy(tenantId, policy.getPolicyNumber()).stream().filter(l -> l.getPolicyMemberId() != null)
            .forEach(l -> byMember.computeIfAbsent(l.getPolicyMemberId(), k -> new ArrayList<>()).add(l));
        LocalDate today = CoveredLives.today();
        List<GroupFuneralFamilyView> views = new ArrayList<>();
        for (PolicyMember member : members.findByTenantIdAndPolicyNumberOrderByJoinedOnAscCreatedAtAsc(tenantId,
                policy.getPolicyNumber())) {
            GroupFuneralMember f = facts.get(member.getPolicyMemberId());
            List<CoveredLife> family = new ArrayList<>(byMember.getOrDefault(member.getPolicyMemberId(), List.of()));
            family.sort(java.util.Comparator.comparing((CoveredLife l) -> !FuneralRole.MAIN_MEMBER.name().equals(l.getRole()))
                .thenComparing(CoveredLife::getCoverStart));
            BigDecimal cover = family.stream().filter(l -> l.coveredOn(today)).map(CoveredLife::getBenefit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            views.add(new GroupFuneralFamilyView(member.getPolicyMemberId(),
                f != null ? f.getAssociationReference() : null, member.getMemberName(),
                tz.co.nlolo.lifeplatform.policy.api.MemberStatus.valueOf(member.getStatus()), member.getJoinedOn(),
                member.getLeftOn(), f != null ? f.getBeneficiaryName() : null,
                f != null ? f.getBeneficiaryRelationship() : null, f != null ? f.getBeneficiaryPhone() : null, cover,
                family.stream().map(l -> CoveredLives.toView(l, plan)).toList()));
        }
        return views;
    }

    /** A family's lives still covered on {@code day}: active, and not scheduled off by then. */
    private List<CoveredLife> familyOn(String policyNumber, UUID policyMemberId, LocalDate day) {
        return lives.findByPolicy(TenantContext.get(), policyNumber).stream()
            .filter(l -> policyMemberId.equals(l.getPolicyMemberId()) && l.isActive()
                && !l.getCoverStart().isAfter(day) && (l.getCoverEnd() == null || l.getCoverEnd().isAfter(day)))
            .toList();
    }

    private PolicyMember activeMember(Policy policy, UUID policyMemberId) {
        PolicyMember member = members.findByPolicyMemberIdAndTenantId(policyMemberId, TenantContext.get())
            .filter(m -> m.getPolicyNumber().equals(policy.getPolicyNumber()))
            .orElseThrow(() -> new InvalidPolicyStateException("Member " + policyMemberId + " is not a member of scheme "
                + policy.getPolicyNumber()));
        if (!"ACTIVE".equals(member.getStatus())) {
            throw new InvalidPolicyStateException("Member " + member.getMemberName() + " has left scheme "
                + policy.getPolicyNumber());
        }
        return member;
    }

    private static BigDecimal benefitOf(FuneralPlan plan, String planCode, FuneralRole role) {
        return plan.benefit(planCode, role).orElseThrow(() -> new InvalidPolicyStateException(
            "Plan " + planCode + " does not cover " + role.plural()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
