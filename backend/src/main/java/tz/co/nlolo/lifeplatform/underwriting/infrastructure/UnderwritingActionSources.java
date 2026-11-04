package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ActionSource;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Underwriting's jobs for the bell (2026-11-03): proposals an agent must correct or resubmit, and for staff the
 * proposals waiting for review, a senior's decision, or a policy that failed to issue. Each is read from the case's own
 * state, so it leaves the bell when that state moves on.
 */
@Configuration
class UnderwritingActionSources {

    /** A case's arrival: when it was opened, or last resubmitted. */
    private static final String ARRIVED = """
        COALESCE((SELECT max(e.created_at) FROM underwriting.case_event e
                   WHERE e.case_id = c.case_id AND e.kind = 'RESUBMITTED'), c.created_at)""";

    @Bean
    ActionSource proposalReturned(NamedParameterJdbcTemplate jdbc, PartyApi parties) {
        return ActionSource.forAgents("proposal-returned", "Correct it and resubmit", (viewer, limit) -> {
            Set<UUID> clients = parties.partyIdsRegisteredBy(viewer.subject());
            if (clients.isEmpty()) return new ActionSource.Pending(0, List.of());
            return read(jdbc, parties, "c.status = 'RETURNED' AND c.applicant_party_id IN (:clients)",
                Map.of("clients", clients),
                "(SELECT max(e.created_at) FROM underwriting.case_event e WHERE e.case_id = c.case_id AND e.kind = 'RETURNED')",
                limit, number -> "Proposal" + number + " returned");
        });
    }

    @Bean
    ActionSource proposalPostponed(NamedParameterJdbcTemplate jdbc, PartyApi parties) {
        return ActionSource.forAgents("proposal-postponed", "Resubmit when the client is ready", (viewer, limit) -> {
            Set<UUID> clients = parties.partyIdsRegisteredBy(viewer.subject());
            if (clients.isEmpty()) return new ActionSource.Pending(0, List.of());
            return read(jdbc, parties,
                "c.status = 'DECIDED' AND c.decision_outcome = 'POSTPONED' AND c.applicant_party_id IN (:clients)",
                Map.of("clients", clients), "c.decision_decided_at",
                limit, number -> "Proposal" + number + " postponed");
        });
    }

    /** Waiting until somebody starts on it; a case with a senior is the senior's. */
    @Bean
    ActionSource proposalToReview(NamedParameterJdbcTemplate jdbc, PartyApi parties) {
        return ActionSource.forStaff("proposal-review", "underwriting.assess", "Review it", (viewer, limit) ->
            read(jdbc, parties, "c.status = 'OPEN' AND c.referral_status <> 'REFERRED_TO_SENIOR'", Map.of(), ARRIVED,
                limit, number -> "Proposal" + number));
    }

    @Bean
    ActionSource referredToSenior(NamedParameterJdbcTemplate jdbc, PartyApi parties) {
        return ActionSource.forStaff("proposal-referred", "underwriting.decide-against-recommendation", "Decide it",
            (viewer, limit) -> read(jdbc, parties,
                "c.referral_status = 'REFERRED_TO_SENIOR' AND c.status <> 'DECIDED'", Map.of(), "c.created_at",
                limit, number -> "Proposal" + number));
    }

    /** The reason is set when automatic issuance fails and cleared when the policy is issued. */
    @Bean
    ActionSource issuanceFailed(NamedParameterJdbcTemplate jdbc, PartyApi parties) {
        return ActionSource.forStaff("issue-failed", "policies.issue", "Issue the policy", (viewer, limit) ->
            read(jdbc, parties, "c.issuance_failure_reason IS NOT NULL", Map.of(), "c.issuance_failed_at",
                limit, number -> "Proposal" + number + " accepted, no policy yet"));
    }

    private static ActionSource.Pending read(NamedParameterJdbcTemplate jdbc, PartyApi parties, String where,
                                             Map<String, ?> extra, String since, int limit,
                                             java.util.function.UnaryOperator<String> detail) {
        MapSqlParameterSource params = new MapSqlParameterSource(extra).addValue("tenantId", TenantContext.get())
            .addValue("limit", limit);
        String from = " FROM underwriting.underwriting_case c WHERE c.tenant_id = :tenantId AND " + where;
        Long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        List<Row> rows = jdbc.query("SELECT c.case_id, c.proposal_number, c.applicant_party_id, " + since + " AS since"
                + from + " ORDER BY since DESC NULLS LAST, c.case_id LIMIT :limit", params,
            (rs, i) -> new Row(rs.getObject("case_id", UUID.class), rs.getString("proposal_number"),
                rs.getObject("applicant_party_id", UUID.class),
                rs.getTimestamp("since") == null ? null : rs.getTimestamp("since").toInstant()));
        Map<UUID, String> names = parties.displayNames(rows.stream().map(Row::applicant).toList());
        return new ActionSource.Pending(total == null ? 0 : total, rows.stream()
            .map(r -> new ActionSource.Item(r.caseId().toString(), null, names.getOrDefault(r.applicant(), "A client"),
                detail.apply(r.proposalNumber() == null ? "" : " " + r.proposalNumber()), r.since()))
            .toList());
    }

    private record Row(UUID caseId, String proposalNumber, UUID applicant, Instant since) {}
}
