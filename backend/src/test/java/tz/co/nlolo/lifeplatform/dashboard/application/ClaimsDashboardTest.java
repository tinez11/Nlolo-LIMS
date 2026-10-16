package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimsDashboardTest {

    private final ClaimsApi claims = mock(ClaimsApi.class);
    private final RecentActivity activity = mock(RecentActivity.class);
    private final ClaimsDashboard dashboard = new ClaimsDashboard(claims, activity);

    /** A fixed civil day, so "the last complete month" is a known month. */
    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 10, 14);

    private void count(ClaimStatus status, long n) {
        when(claims.searchClaims(eq(status), isNull(), isNull(), isNull(), argThat(p -> p != null && p.getPageSize() == 1)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), n));
    }

    private ClaimView claim(ClaimStatus status) {
        ClaimView c = mock(ClaimView.class);
        when(c.claimId()).thenReturn(UUID.randomUUID());
        when(c.policyNumber()).thenReturn("POL-00A4B7A1");
        when(c.claimantPartyId()).thenReturn(UUID.randomUUID());
        when(c.claimType()).thenReturn(ClaimType.DEATH);
        when(c.dateOfEvent()).thenReturn(LocalDate.of(2026, 9, 29));
        when(c.status()).thenReturn(status);
        return c;
    }

    private void everyStage() {
        count(ClaimStatus.REGISTERED, 17);
        count(ClaimStatus.UNDER_ASSESSMENT, 9);
        count(ClaimStatus.SETTLEMENT_REQUESTED, 6);
        count(ClaimStatus.APPROVED, 4);
        count(ClaimStatus.REOPENED, 2);
        count(ClaimStatus.SETTLED, 128);
    }

    @Test
    void countsEveryStageAndGivesAManagerTheSettlementQueue() {
        everyStage();
        // Built BEFORE the stubbing that returns it: `claim()` stubs a mock of its own, and starting that inside an
        // unfinished `thenReturn(...)` is what Mockito reports as an unfinished stubbing.
        ClaimView queued = claim(ClaimStatus.SETTLEMENT_REQUESTED);
        when(claims.searchClaims(eq(ClaimStatus.SETTLEMENT_REQUESTED), isNull(), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == ClaimsDashboard.QUEUE_SIZE)))
            .thenReturn(new PageImpl<>(List.of(queued)));

        ClaimsDashboardView view = dashboard.build(true, TODAY);

        assertThat(view.registered()).isEqualTo(17L);
        assertThat(view.underAssessment()).isEqualTo(9L);
        assertThat(view.settlementRequested()).isEqualTo(6L);
        assertThat(view.approved()).isEqualTo(4L);
        assertThat(view.reopened()).isEqualTo(2L);
        assertThat(view.settled()).isEqualTo(128L);
        assertThat(view.recentActivity()).isEmpty();
        assertThat(view.queueStatus()).isEqualTo("SETTLEMENT_REQUESTED");
        assertThat(view.queue()).singleElement().satisfies(q -> {
            assertThat(q.policyNumber()).isEqualTo("POL-00A4B7A1");
            assertThat(q.claimType()).isEqualTo("DEATH");
            assertThat(q.dateOfEvent()).isEqualTo(LocalDate.of(2026, 9, 29));
        });
    }

    /** An assessor cannot decide a settlement; their queue is the claim nobody has assessed yet. */
    @Test
    void givesAnAssessorTheUnassessedQueue() {
        everyStage();
        ClaimView queued = claim(ClaimStatus.REGISTERED);
        when(claims.searchClaims(eq(ClaimStatus.REGISTERED), isNull(), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == ClaimsDashboard.QUEUE_SIZE)))
            .thenReturn(new PageImpl<>(List.of(queued)));

        ClaimsDashboardView view = dashboard.build(false, TODAY);

        assertThat(view.queueStatus()).isEqualTo("REGISTERED");
        assertThat(view.queue()).singleElement().satisfies(q -> assertThat(q.status()).isEqualTo("REGISTERED"));
    }

    /** The two cards phase 1 drew dashed: what was paid last month, and what is waiting on a document. */
    @Test
    void readsSettledTotalsAndOpenDocumentRequests() {
        when(claims.countOpenDocumentRequests()).thenReturn(11L);
        when(claims.settlementsByMonth(any(), any())).thenReturn(List.of(
            new tz.co.nlolo.lifeplatform.claims.api.SettlementMonth(
                java.time.YearMonth.of(2026, 9), "TZS", 9, new java.math.BigDecimal("41000000.00")),
            new tz.co.nlolo.lifeplatform.claims.api.SettlementMonth(
                java.time.YearMonth.of(2026, 9), "USD", 1, new java.math.BigDecimal("1000.00"))));

        ClaimsDashboardView view = dashboard.build(true, TODAY);

        assertThat(view.openDocumentRequests()).isEqualTo(11L);
        // Per currency, each carrying its OWN currency on the money -- never added together.
        assertThat(view.settlements())
            .extracting(s -> s.month() + " " + s.currency() + " " + s.claims() + " " + s.totalApproved().amount()
                + " " + s.totalApproved().currencyCode())
            .containsExactly("2026-09 TZS 9 41000000.00 TZS", "2026-09 USD 1 1000.00 USD");
    }

    /** The last complete month, both sides. */
    @Test
    void asksSettlementsForTheLastCompleteMonth() {
        dashboard.build(true, TODAY);

        verify(claims).settlementsByMonth(java.time.YearMonth.of(2026, 9), java.time.YearMonth.of(2026, 9));
    }

    /** One aggregate down costs one card. */
    @Test
    void aFailedSettlementsReadLeavesTheCountsStanding() {
        count(ClaimStatus.REGISTERED, 17);
        when(claims.settlementsByMonth(any(), any())).thenThrow(new IllegalStateException("claims is down"));
        when(claims.countOpenDocumentRequests()).thenThrow(new IllegalStateException("claims is down"));

        ClaimsDashboardView view = dashboard.build(true, TODAY);

        assertThat(view.settlements()).isEmpty();
        assertThat(view.openDocumentRequests()).isNull();
        assertThat(view.registered()).isEqualTo(17L);
    }
}
