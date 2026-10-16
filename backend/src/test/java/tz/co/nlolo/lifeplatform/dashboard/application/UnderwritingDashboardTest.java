package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.underwriting.api.CaseFilter;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.ReferralStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
    private final RecentActivity activity = mock(RecentActivity.class);
    private final UnderwritingDashboard dashboard = new UnderwritingDashboard(underwriting, activity);

    /** A fixed civil day, so "the last complete month" is a known month in every assertion below. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

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

        UnderwritingDashboardView view = dashboard.build(TODAY);

        assertThat(view.openCases()).isEqualTo(238L);
        assertThat(view.casesInReview()).isEqualTo(41L);
        assertThat(view.casesAwaitingIssue()).isEqualTo(12L);
        assertThat(view.recentActivity()).isEmpty();
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

        UnderwritingDashboardView view = dashboard.build(TODAY);

        assertThat(view.openCases()).isNull();
        assertThat(view.queue()).isEmpty();
        assertThat(view.casesInReview()).isEqualTo(41L);
    }

    /** The feed is the underwriting module's own, and a broken audit read costs the feed and nothing else. */
    @Test
    void readsUnderwritingsOwnActivityAndSurvivesItsFailure() {
        when(underwriting.listCases(any(), isNull(), isNull(), any())).thenReturn(total(0));
        when(underwriting.listCasesAwaitingIssue(isNull(), any())).thenReturn(total(0));
        when(activity.latest("underwriting.")).thenThrow(new IllegalStateException("audit is down"));

        UnderwritingDashboardView view = dashboard.build(TODAY);

        assertThat(view.recentActivity()).isEmpty();
        assertThat(view.openCases()).isZero();
    }

    /** The three cards phase 1 drew dashed, now read from the filters and the aggregate that answer them. */
    @Test
    void countsReferralsAndReadsLastMonthsDecisions() {
        when(underwriting.listCases(any(CaseFilter.class), any())).thenReturn(total(0));
        when(underwriting.listCases(argThat((CaseFilter f) -> f != null
            && f.referral() == ReferralStatus.REFERRED_TO_SENIOR), any())).thenReturn(total(3));
        when(underwriting.decisionsByMonth(any(), any())).thenReturn(List.of(
            new tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth(
                YearMonth.of(2026, 9), DecisionOutcome.ACCEPT, 42, new BigDecimal("3.4")),
            new tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth(
                YearMonth.of(2026, 9), DecisionOutcome.DECLINED, 7, new BigDecimal("2.1"))));

        UnderwritingDashboardView view = dashboard.build(TODAY);

        assertThat(view.casesReferred()).isEqualTo(3L);
        assertThat(view.decisions())
            .extracting(d -> d.month() + " " + d.outcome() + " " + d.cases() + " " + d.averageDays())
            .containsExactly("2026-09 ACCEPT 42 3.4", "2026-09 DECLINED 7 2.1");
    }

    /** The LAST COMPLETE month, both sides: never the part-month that would not compare with a full one. */
    @Test
    void asksOnlyForTheLastCompleteMonth() {
        when(underwriting.listCases(any(CaseFilter.class), any())).thenReturn(total(0));
        when(underwriting.decisionsByMonth(any(), any())).thenReturn(List.of());

        dashboard.build(TODAY);

        verify(underwriting).decisionsByMonth(YearMonth.of(2026, 9), YearMonth.of(2026, 9));
    }

    /** One aggregate being down costs ONE card, not the page. */
    @Test
    void aFailedDecisionsReadLeavesTheRestStanding() {
        when(underwriting.listCases(any(CaseFilter.class), any())).thenReturn(total(4));
        when(underwriting.listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(), any())).thenReturn(total(4));
        when(underwriting.decisionsByMonth(any(), any())).thenThrow(new IllegalStateException("reporting is down"));

        UnderwritingDashboardView view = dashboard.build(TODAY);

        assertThat(view.decisions()).isEmpty();
        assertThat(view.casesReferred()).isEqualTo(4L);
    }
}
