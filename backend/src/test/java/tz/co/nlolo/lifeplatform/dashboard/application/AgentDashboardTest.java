package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.underwriting.api.CaseFilter;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDashboardTest {

    private static final String SUBJECT = "agent-subject";
    private static final UUID PARTY = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID DOWNLINE = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final PartyApi parties = mock(PartyApi.class);
    private final PolicyApi policies = mock(PolicyApi.class);
    private final DistributionApi distribution = mock(DistributionApi.class);
    private final UnderwritingApi underwriting = mock(UnderwritingApi.class);
    private final AgentDashboard dashboard = new AgentDashboard(parties, policies, distribution, underwriting);

    private static <T> PageImpl<T> total(long n) {
        return new PageImpl<>(List.of(), PageRequest.of(0, 1), n);
    }

    @Test
    void scopesClientsToTheirRegistrarAndPoliciesToTheTeam() {
        PartyView pending = new PartyView(UUID.randomUUID(), PartyType.INDIVIDUAL, KycStatus.PENDING, "Amina Hassan");
        CommissionStatementView statement = mock(CommissionStatementView.class);
        when(statement.totalAmount()).thenReturn(new BigDecimal("1284500.00"));
        when(statement.totalCurrency()).thenReturn("TZS");
        when(statement.status()).thenReturn(null);

        when(parties.searchParties(isNull(), eq(SUBJECT), isNull(), isNull(), any())).thenReturn(total(309));
        when(parties.searchParties(eq(KycStatus.PENDING), eq(SUBJECT), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == 1))).thenReturn(total(23));
        when(parties.searchParties(eq(KycStatus.PENDING), eq(SUBJECT), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == AgentDashboard.QUEUE_SIZE)))
            .thenReturn(new PageImpl<>(List.of(pending)));
        when(parties.searchParties(eq(KycStatus.REJECTED), eq(SUBJECT), isNull(), isNull(), any())).thenReturn(total(1));
        when(distribution.resolveAgentTeam(PARTY)).thenReturn(List.of(AGENT, DOWNLINE));
        when(policies.searchPolicies(isNull(), isNull(), isNull(), eq(Set.of(AGENT, DOWNLINE)), isNull(), any()))
            .thenReturn(total(412));
        when(distribution.agentIdForParty(PARTY)).thenReturn(Optional.of(AGENT));
        when(distribution.listStatements(AGENT, "2026-09")).thenReturn(List.of(statement));

        AgentDashboardView view = dashboard.build(SUBJECT, PARTY, TODAY);

        assertThat(view.clients()).isEqualTo(309L);
        assertThat(view.clientsAwaitingKyc()).isEqualTo(23L);
        assertThat(view.clientsKycRejected()).isEqualTo(1L);
        assertThat(view.policiesInBook()).isEqualTo(412L);
        // The last CLOSED period on 14 October is September.
        assertThat(view.commissionPeriod()).isEqualTo("2026-09");
        assertThat(view.commissionEarned().amount()).isEqualTo("1284500.00");
        assertThat(view.awaitingKyc()).singleElement().satisfies(c -> {
            assertThat(c.displayName()).isEqualTo("Amina Hassan");
            assertThat(c.kycStatus()).isEqualTo("PENDING");
        });
    }

    /** A party that is not an agent here has no team and no statement: nulls and an empty book, not an error. */
    @Test
    void aPartyWithNoAgentProfileHasNoCommissionAndNoBook() {
        when(parties.searchParties(any(), eq(SUBJECT), isNull(), isNull(), any())).thenReturn(total(0));
        when(distribution.resolveAgentTeam(PARTY)).thenReturn(List.of());
        when(policies.searchPolicies(isNull(), isNull(), isNull(), eq(Set.of()), isNull(), any())).thenReturn(total(0));
        when(distribution.agentIdForParty(PARTY)).thenReturn(Optional.empty());

        AgentDashboardView view = dashboard.build(SUBJECT, PARTY, TODAY);

        assertThat(view.policiesInBook()).isZero();
        assertThat(view.commissionEarned()).isNull();
        assertThat(view.commissionStatus()).isNull();
    }

    /**
     * Never a gap. GET /underwriting/cases admits an agents token and the controller already scopes it to
     * partyIdsRegisteredBy; phase 1 dashed this card on a wrong reading of the endpoint.
     */
    @Test
    void countsTheAgentsOwnApplicationsAwaitingUnderwriting() {
        Set<UUID> mine = Set.of(UUID.randomUUID());
        when(parties.partyIdsRegisteredBy(SUBJECT)).thenReturn(mine);
        when(underwriting.listCases(argThat((CaseFilter f) -> f != null
            && f.status() == UnderwritingCaseStatus.OPEN && mine.equals(f.applicantPartyIds())), any()))
            .thenReturn(total(5));

        assertThat(dashboard.build(SUBJECT, PARTY, TODAY).applicationsInUnderwriting()).isEqualTo(5L);
    }

    /**
     * An agent who has registered NOBODY sees zero, not everything. The empty set is the scope, and a null would
     * mean "no filter" -- every case in the tenant, on somebody else's clients.
     */
    @Test
    void anAgentWhoHasRegisteredNobodyIsScopedToNothing() {
        when(parties.partyIdsRegisteredBy(SUBJECT)).thenReturn(Set.of());
        when(underwriting.listCases(argThat((CaseFilter f) -> f != null
            && f.applicantPartyIds() != null && f.applicantPartyIds().isEmpty()), any())).thenReturn(total(0));

        assertThat(dashboard.build(SUBJECT, PARTY, TODAY).applicationsInUnderwriting()).isZero();
    }
}
