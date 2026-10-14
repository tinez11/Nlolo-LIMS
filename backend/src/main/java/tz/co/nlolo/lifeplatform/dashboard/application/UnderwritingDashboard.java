package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.QueuedCase;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;

import java.util.List;

@Service
public class UnderwritingDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final UnderwritingApi underwriting;

    public UnderwritingDashboard(UnderwritingApi underwriting) {
        this.underwriting = underwriting;
    }

    public UnderwritingDashboardView build() {
        Long open = Safely.read("open cases",
            () -> underwriting.listCases(UnderwritingCaseStatus.OPEN, null, null, TOTAL_ONLY).getTotalElements());
        Long inReview = Safely.read("cases in review",
            () -> underwriting.listCases(UnderwritingCaseStatus.IN_REVIEW, null, null, TOTAL_ONLY).getTotalElements());
        Long awaitingIssue = Safely.read("cases awaiting issue",
            () -> underwriting.listCasesAwaitingIssue(null, TOTAL_ONLY).getTotalElements());

        // Oldest first: "next in your queue" means what has waited longest, where the queue PAGE sorts newest first.
        List<QueuedCase> queue = Safely.read("the open-case queue", () -> underwriting.listCases(
                UnderwritingCaseStatus.OPEN, null, null,
                PageRequest.of(0, QUEUE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
            .map(c -> new QueuedCase(c.caseId(), c.applicantPartyId(), c.lifeAssuredPartyId(), c.productId(),
                Money.of(c.sumAssuredAmount(), c.sumAssuredCurrency()), c.status().name()))
            .getContent());

        return new UnderwritingDashboardView(open, inReview, awaitingIssue, queue == null ? List.of() : queue);
    }
}
