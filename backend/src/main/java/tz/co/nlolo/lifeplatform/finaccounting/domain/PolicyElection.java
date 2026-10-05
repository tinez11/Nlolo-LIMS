package tz.co.nlolo.lifeplatform.finaccounting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyRegisterStateException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One accounting policy election (IFRS 17 spec §3, decision D7): an effective-dated choice -- a measurement model, an
 * investment-component rule, the OCI option -- proposed by one person and decided by another, with the sign-off it
 * rests on. Decided once and never edited (finaccounting V10's trigger); a change is a new, dated election.
 */
@Entity
@Table(name = "accounting_policy_election", schema = "finaccounting")
public class PolicyElection {

    public enum Status { PROPOSED, APPROVED, REJECTED }

    @Id @Column(name = "election_id") private UUID electionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "election_key", nullable = false) private String key;
    @Column(name = "scope", nullable = false) private String scope;
    @Column(name = "election_value", nullable = false) private String value;
    @Column(name = "effective_from", nullable = false) private LocalDate effectiveFrom;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "rationale") private String rationale;
    @Column(name = "sign_off_ref") private String signOffRef;
    @Column(name = "decision_reason") private String decisionReason;
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt;
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "register_version") private Integer registerVersion;
    @Version @Column(name = "version", nullable = false) private long version;

    protected PolicyElection() {}

    public PolicyElection(UUID tenantId, String key, String scope, String value, LocalDate effectiveFrom,
                          String rationale, String proposedBy, Instant proposedAt) {
        this.electionId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.key = key;
        this.scope = scope == null || scope.isBlank() ? "*" : scope.trim();
        this.value = value;
        this.effectiveFrom = effectiveFrom;
        this.rationale = rationale;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
        this.status = Status.PROPOSED.name();
    }

    /** A second person approves, naming the sign-off it rests on; it becomes the register's next version. */
    public void approve(String by, String signOff, int nextRegisterVersion, Instant at) {
        requireProposed();
        if (by == null || by.equals(proposedBy)) {
            throw new PolicyRegisterStateException("A second person approves an accounting policy election");
        }
        if (signOff == null || signOff.isBlank()) {
            throw new FinaccountingValidationException("An approval names its sign-off reference");
        }
        this.status = Status.APPROVED.name();
        this.decidedBy = by;
        this.decidedAt = at;
        this.signOffRef = signOff.trim();
        this.registerVersion = nextRegisterVersion;
    }

    public void reject(String by, String reason, Instant at) {
        requireProposed();
        if (by == null || by.equals(proposedBy)) {
            throw new PolicyRegisterStateException("A second person decides an accounting policy election");
        }
        if (reason == null || reason.isBlank()) {
            throw new FinaccountingValidationException("A rejection gives its reason");
        }
        this.status = Status.REJECTED.name();
        this.decidedBy = by;
        this.decidedAt = at;
        this.decisionReason = reason.trim();
    }

    private void requireProposed() {
        if (!Status.PROPOSED.name().equals(status)) {
            throw new PolicyRegisterStateException("Election " + electionId + " is " + status + ", not awaiting a decision");
        }
    }

    /** The baseline (spec §3) is written approved, without the proposal path's "from today" rule. */
    public static PolicyElection baseline(UUID tenantId, String key, String scope, String value, LocalDate effectiveFrom,
                                          String rationale, String signOff, int registerVersion, Instant at) {
        PolicyElection e = new PolicyElection(tenantId, key, scope, value, effectiveFrom, rationale,
            "system:baseline-proposer", at);
        e.status = Status.APPROVED.name();
        e.decidedBy = "system:baseline";
        e.decidedAt = at;
        e.signOffRef = signOff;
        e.registerVersion = registerVersion;
        return e;
    }

    public UUID getElectionId() { return electionId; }
    public UUID getTenantId() { return tenantId; }
    public String getKey() { return key; }
    public String getScope() { return scope; }
    public String getValue() { return value; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public String getStatus() { return status; }
    public String getRationale() { return rationale; }
    public String getSignOffRef() { return signOffRef; }
    public String getDecisionReason() { return decisionReason; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Integer getRegisterVersion() { return registerVersion; }
}
