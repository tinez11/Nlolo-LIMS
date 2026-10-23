package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView.ClientAwaitingKyc;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView.CommissionPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.CountPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.Share;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.reporting.api.CategoryCount;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyCount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.underwriting.api.CaseFilter;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final PartyApi parties;
    private final PolicyApi policies;
    private final DistributionApi distribution;
    private final UnderwritingApi underwriting;
    private final ReportingApi reporting;

    public AgentDashboard(PartyApi parties, PolicyApi policies, DistributionApi distribution,
                          UnderwritingApi underwriting, ReportingApi reporting) {
        this.underwriting = underwriting;
        this.reporting = reporting;
        this.parties = parties;
        this.policies = policies;
        this.distribution = distribution;
    }

    /**
     * @param subject the token subject -- what clients are scoped on, as {@code PartyController} scopes them
     * @param partyId the token's {@code party_id} -- what the hierarchy team and the agent profile hang off
     * @param today the civil date in Africa/Dar_es_Salaam
     */
    public AgentDashboardView build(String subject, UUID partyId, LocalDate today) {
        return build(subject, partyId, DashboardPeriod.of(null, null, today), today);
    }

    /** @param period the months the ranged figures cover (2026-10-23); client counts and the book stay "now" */
    public AgentDashboardView build(String subject, UUID partyId, DashboardPeriod range, LocalDate today) {
        Long clients = Safely.read("my clients",
            () -> parties.searchParties(null, subject, null, null, TOTAL_ONLY).getTotalElements());
        Long awaitingKyc = Safely.read("clients awaiting KYC",
            () -> parties.searchParties(KycStatus.PENDING, subject, null, null, TOTAL_ONLY).getTotalElements());
        Long rejected = Safely.read("clients with KYC rejected",
            () -> parties.searchParties(KycStatus.REJECTED, subject, null, null, TOTAL_ONLY).getTotalElements());
        // Newest first, the same order the party search uses everywhere: an agent's most recent visit is the one
        // whose documents are in hand.
        List<ClientAwaitingKyc> queue = Safely.read("the KYC queue", () -> parties.searchParties(KycStatus.PENDING,
                subject, null, null, PageRequest.of(0, QUEUE_SIZE,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("partyId"))))
            .map(p -> new ClientAwaitingKyc(p.partyId(), p.displayName(), p.kycStatus().name()))
            .getContent());

        Long book = Safely.read("policies in my book", () -> policies.searchPolicies(null, null, null,
            Set.copyOf(distribution.resolveAgentTeam(partyId)), null, TOTAL_ONLY).getTotalElements());

        // The agent's own applications still with underwriting. NOT a gap, though phase 1 drew it dashed on a wrong
        // reading: GET /underwriting/cases admits an agents token, and a case carries no agent of its own, so the
        // scope is the clients this agent REGISTERED -- resolved exactly as the console's own case list resolves it.
        // An agent who has registered nobody gets an EMPTY set, which means zero rows; a null would mean every case
        // in the tenant.
        Long applications = Safely.read("my applications in underwriting", () -> underwriting.listCases(
                CaseFilter.status(UnderwritingCaseStatus.OPEN)
                    .withApplicants(parties.partyIdsRegisteredBy(subject)),
                TOTAL_ONLY)
            .getTotalElements());

        String period = YearMonth.from(today).minusMonths(1).toString();
        CommissionStatementView statement = Safely.read("commission statement", () -> distribution
            .agentIdForParty(partyId)
            .flatMap(agentId -> distribution.listStatements(agentId, period).stream().findFirst())
            .orElse(null));

        // ---- the ranged figures (2026-10-23), scoped as the agent's own screens are ----
        Set<UUID> team = Safely.read("my team", () -> Set.copyOf(distribution.resolveAgentTeam(partyId)));
        List<CommissionStatementView> statements = Safely.read("commission statements", () -> distribution
            .agentIdForParty(partyId)
            .map(agentId -> range.months().stream()
                .flatMap(m -> distribution.listStatements(agentId, m.toString()).stream())
                .toList())
            .orElse(List.of()));
        String currency = statements == null ? null : statements.stream().map(CommissionStatementView::totalCurrency)
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        List<CommissionPoint> commission = statements == null || currency == null ? List.of()
            : range.months().stream().map(m -> {
                BigDecimal paid = BigDecimal.ZERO;
                BigDecimal pending = BigDecimal.ZERO;
                for (CommissionStatementView s : statements) {
                    if (!m.toString().equals(s.period()) || !currency.equals(s.totalCurrency()) || s.totalAmount() == null) continue;
                    if (s.status() == StatementStatus.PAID) paid = paid.add(s.totalAmount());
                    else pending = pending.add(s.totalAmount());
                }
                return new CommissionPoint(m.toString(), Money.of(paid, currency), Money.of(pending, currency));
            }).toList();
        BigDecimal commissionTotal = statements == null || currency == null ? null : statements.stream()
            .filter(s -> currency.equals(s.totalCurrency()) && s.totalAmount() != null)
            .map(CommissionStatementView::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<YearMonth, Long> issued = team == null ? null : Safely.read("policies sold", () ->
            reporting.policiesIssuedByMonth(range.compareFrom(), range.to(), team).stream()
                .collect(Collectors.toMap(MonthlyCount::month, MonthlyCount::count)));
        Long sold = issued == null ? null : range.months().stream().mapToLong(m -> issued.getOrDefault(m, 0L)).sum();
        Long soldBefore = issued == null ? null
            : range.compareMonths().stream().mapToLong(m -> issued.getOrDefault(m, 0L)).sum();
        List<Share> byProduct = team == null ? List.of() : Safely.read("my book by product", () -> {
            List<CategoryCount> rows = reporting.policiesInForceByCategory(today, team);
            return DashboardPeriod.shares(rows.stream()
                    .map(c -> new DashboardPeriod.Slice(c.category(), BigDecimal.valueOf(c.count()))).toList(),
                null, BigDecimal::longValueExact);
        });

        return new AgentDashboardView(clients, awaitingKyc, rejected, book, applications, period,
            statement == null ? null : Money.of(statement.totalAmount(), statement.totalCurrency()),
            statement == null || statement.status() == null ? null : statement.status().name(),
            queue == null ? List.of() : queue,
            range.toView(), Money.of(commissionTotal, currency), commission,
            sold, soldBefore,
            sold == null ? null : DashboardPeriod.changePercent(BigDecimal.valueOf(sold), BigDecimal.valueOf(soldBefore)),
            issued == null ? List.of()
                : range.months().stream().map(m -> new CountPoint(m.toString(), issued.getOrDefault(m, 0L))).toList(),
            byProduct == null ? List.of() : byProduct);
    }
}
