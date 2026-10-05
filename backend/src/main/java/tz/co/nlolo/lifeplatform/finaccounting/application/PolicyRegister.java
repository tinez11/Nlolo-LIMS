package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionView;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ElectionKey;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PolicyElection;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.PolicyElectionRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The effective-dated accounting policy register (IFRS 17 spec §3, decision D7). An election is proposed by one person
 * and approved -- with the sign-off it rests on -- or rejected by another; approving makes it the register's next
 * version, and every journal records the version in force when it was posted. Changes are prospective: a proposal
 * applies from today or later; correcting the past is a restatement, made through journals. The baseline is seeded
 * with the chart (ChartOfAccountSeeder).
 */
@Component
class PolicyRegister {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final PolicyElectionRepository elections;
    private final JdbcTemplate jdbc;

    PolicyRegister(PolicyElectionRepository elections, JdbcTemplate jdbc) {
        this.elections = elections;
        this.jdbc = jdbc;
    }

    int currentVersion(UUID tenantId) {
        return elections.currentVersion(tenantId);
    }

    @Transactional
    PolicyElectionView propose(PolicyElectionInput input, String by) {
        if (input == null) {
            throw new FinaccountingValidationException("An election names its key, value and effective date");
        }
        ElectionKey key = ElectionKey.of(input.key())
            .orElseThrow(() -> new FinaccountingValidationException("Unknown accounting policy election " + input.key()));
        if (!key.permits(input.value())) {
            throw new FinaccountingValidationException(input.value() + " is not a permitted value for " + key.name());
        }
        if (input.effectiveFrom() == null) {
            throw new FinaccountingValidationException("An election has an effective date");
        }
        if (input.effectiveFrom().isBefore(LocalDate.now(CIVIL))) {
            throw new FinaccountingValidationException(
                "An election applies from today or later; a past change is a restatement, made through journals");
        }
        PolicyElection e = new PolicyElection(TenantContext.get(), key.name(), input.scope(), input.value().trim(),
            input.effectiveFrom(), input.rationale(), by, Instant.now());
        return view(elections.save(e));
    }

    @Transactional
    PolicyElectionView approve(UUID electionId, String signOffRef, String by) {
        UUID tenantId = TenantContext.get();
        lockRegister(tenantId);
        PolicyElection e = load(electionId);
        e.approve(by, signOffRef, elections.currentVersion(tenantId) + 1, Instant.now());
        return view(elections.save(e));
    }

    @Transactional
    PolicyElectionView reject(UUID electionId, String reason, String by) {
        PolicyElection e = load(electionId);
        e.reject(by, reason, Instant.now());
        return view(elections.save(e));
    }

    @Transactional(readOnly = true)
    Optional<PolicyElectionView> inForce(String key, String scope, LocalDate on) {
        UUID tenantId = TenantContext.get();
        String s = scope == null || scope.isBlank() ? "*" : scope;
        Optional<PolicyElection> found = elections.approvedOnOrBefore(tenantId, key, s, on).stream().findFirst();
        if (found.isEmpty() && !"*".equals(s)) {
            found = elections.approvedOnOrBefore(tenantId, key, "*", on).stream().findFirst();
        }
        return found.map(PolicyRegister::view);
    }

    /**
     * The elections in force on {@code asOf} (one per key and scope), then those approved to take effect after it,
     * then every one still proposed. Without the second group an approved change dated tomorrow would be shown
     * nowhere until the day it took effect.
     */
    @Transactional(readOnly = true)
    List<PolicyElectionView> list(LocalDate asOf) {
        Map<String, PolicyElection> inForce = new LinkedHashMap<>();
        List<PolicyElectionView> scheduled = new ArrayList<>();
        List<PolicyElectionView> proposed = new ArrayList<>();
        for (PolicyElection e : elections.findByTenantIdOrderByKeyAscScopeAscEffectiveFromDesc(TenantContext.get())) {
            if (PolicyElection.Status.PROPOSED.name().equals(e.getStatus())) {
                proposed.add(view(e));
            } else if (PolicyElection.Status.APPROVED.name().equals(e.getStatus())) {
                if (e.getEffectiveFrom().isAfter(asOf)) {
                    scheduled.add(view(e));
                } else {
                    inForce.putIfAbsent(e.getKey() + "|" + e.getScope(), e);   // latest effective first
                }
            }
        }
        List<PolicyElectionView> out = new ArrayList<>(inForce.values().stream().map(PolicyRegister::view).toList());
        out.addAll(scheduled);
        out.addAll(proposed);
        return out;
    }

    /** Serialises register versions per tenant: two approvals at once must not take the same number. */
    private void lockRegister(UUID tenantId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "policy-register:" + tenantId);
    }

    private PolicyElection load(UUID electionId) {
        return elections.findByTenantIdAndElectionId(TenantContext.get(), electionId)
            .orElseThrow(() -> new PolicyElectionNotFoundException("Accounting policy election " + electionId + " not found"));
    }

    static PolicyElectionView view(PolicyElection e) {
        return new PolicyElectionView(e.getElectionId(), e.getKey(), e.getScope(), e.getValue(), e.getEffectiveFrom(),
            e.getStatus(), e.getRationale(), e.getSignOffRef(), e.getDecisionReason(), e.getProposedBy(),
            e.getProposedAt(), e.getDecidedBy(), e.getDecidedAt(), e.getRegisterVersion());
    }
}
