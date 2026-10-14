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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaimsDashboardTest {

    private final ClaimsApi claims = mock(ClaimsApi.class);
    private final ClaimsDashboard dashboard = new ClaimsDashboard(claims);

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

        ClaimsDashboardView view = dashboard.build(true);

        assertThat(view.registered()).isEqualTo(17L);
        assertThat(view.underAssessment()).isEqualTo(9L);
        assertThat(view.settlementRequested()).isEqualTo(6L);
        assertThat(view.approved()).isEqualTo(4L);
        assertThat(view.reopened()).isEqualTo(2L);
        assertThat(view.settled()).isEqualTo(128L);
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

        ClaimsDashboardView view = dashboard.build(false);

        assertThat(view.queueStatus()).isEqualTo("REGISTERED");
        assertThat(view.queue()).singleElement().satisfies(q -> assertThat(q.status()).isEqualTo("REGISTERED"));
    }
}
