package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnderwritingDashboardTest {

    private final UnderwritingApi underwriting = mock(UnderwritingApi.class);
    private final UnderwritingDashboard dashboard = new UnderwritingDashboard(underwriting);

    private static Page<UnderwritingCaseView> total(long n) {
        return new PageImpl<>(List.of(), PageRequest.of(0, 1), n);
    }

    @Test
    void countsEachQueueByItsOwnStatusAndListsTheOldestOpenCasesFirst() {
        UUID caseId = UUID.randomUUID();
        UUID applicant = UUID.randomUUID();
        UnderwritingCaseView open = mock(UnderwritingCaseView.class);
        when(open.caseId()).thenReturn(caseId);
        when(open.applicantPartyId()).thenReturn(applicant);
        when(open.status()).thenReturn(UnderwritingCaseStatus.OPEN);
        when(open.sumAssuredAmount()).thenReturn(new BigDecimal("25000000"));
        when(open.sumAssuredCurrency()).thenReturn("TZS");

        when(underwriting.listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == 1))).thenReturn(total(238));
        when(underwriting.listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == UnderwritingDashboard.QUEUE_SIZE)))
            .thenReturn(new PageImpl<>(List.of(open)));
        when(underwriting.listCases(eq(UnderwritingCaseStatus.IN_REVIEW), isNull(), isNull(), any())).thenReturn(total(41));
        when(underwriting.listCasesAwaitingIssue(isNull(), any())).thenReturn(total(12));

        UnderwritingDashboardView view = dashboard.build();

        assertThat(view.openCases()).isEqualTo(238L);
        assertThat(view.casesInReview()).isEqualTo(41L);
        assertThat(view.casesAwaitingIssue()).isEqualTo(12L);
        assertThat(view.queue()).singleElement().satisfies(c -> {
            assertThat(c.caseId()).isEqualTo(caseId);
            assertThat(c.applicantPartyId()).isEqualTo(applicant);
            assertThat(c.status()).isEqualTo("OPEN");
            assertThat(c.sumAssured().amount()).isEqualTo("25000000.00");
            assertThat(c.sumAssured().currencyCode()).isEqualTo("TZS");
        });

        // "Next in your queue" is what has waited LONGEST -- the opposite of the queue page's newest-first.
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(underwriting, times(2)).listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(), pages.capture());
        assertThat(pages.getAllValues()).anySatisfy(p -> {
            assertThat(p.getPageSize()).isEqualTo(UnderwritingDashboard.QUEUE_SIZE);
            assertThat(p.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(p.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
        });
    }

    /** One broken source costs one card. A count that could not be read is null -- never 0, which would be a claim. */
    @Test
    void aCountThatCannotBeReadIsNullAndTheRestStillArrive() {
        when(underwriting.listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(), any()))
            .thenThrow(new IllegalStateException("underwriting is down"));
        when(underwriting.listCases(eq(UnderwritingCaseStatus.IN_REVIEW), isNull(), isNull(), any())).thenReturn(total(41));
        when(underwriting.listCasesAwaitingIssue(isNull(), any())).thenReturn(total(12));

        UnderwritingDashboardView view = dashboard.build();

        assertThat(view.openCases()).isNull();
        assertThat(view.queue()).isEmpty();
        assertThat(view.casesInReview()).isEqualTo(41L);
    }
}
