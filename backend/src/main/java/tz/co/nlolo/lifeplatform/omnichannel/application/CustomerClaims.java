package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.claims.api.ClaimJourneyApi;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerClaimView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerClaimView.Step;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in customer's claims (2026-10-08, the customer portal design step 4). The claims module owns every
 * decision; this says where a claim is in words a claimant can act on, and nothing staff alone should see.
 */
@Service
public class CustomerClaims {

    private final ClaimsApi claimsApi;
    private final ClaimJourneyApi journeyApi;
    private final PolicyApi policyApi;
    private final ProductApi productApi;

    public CustomerClaims(ClaimsApi claimsApi, ClaimJourneyApi journeyApi, PolicyApi policyApi, ProductApi productApi) {
        this.claimsApi = claimsApi;
        this.journeyApi = journeyApi;
        this.policyApi = policyApi;
        this.productApi = productApi;
    }

    @Transactional(readOnly = true)
    public List<CustomerClaimView.Summary> claims(UUID customerPartyId) {
        Map<String, String> productNames = new HashMap<>();
        return claimsApi.searchClaims(null, customerPartyId, null, null, PageRequest.of(0, 100)).getContent().stream()
            .sorted(Comparator.comparing(ClaimView::dateOfEvent).reversed())
            .map(c -> new CustomerClaimView.Summary(c.claimId(), c.policyNumber(), productName(c.policyNumber(), productNames),
                c.claimType().name(), c.status().name(), statusText(c.status()), c.dateOfEvent(),
                (int) journeyApi.documentRequests(c.claimId()).stream().filter(r -> "OPEN".equals(r.status())).count()))
            .toList();
    }

    @Transactional(readOnly = true)
    public CustomerClaimView claim(UUID customerPartyId, UUID claimId) {
        ClaimView claim = claimsApi.getClaim(claimId);
        if (!customerPartyId.equals(claim.claimantPartyId())) {
            throw new AccessDeniedException("Claim " + claimId + " was not made by this customer");
        }
        ClaimJourneyApi.Journey journey = journeyApi.journey(claimId);
        List<CustomerClaimView.Document> documents = claimsApi.listEvidence(claimId).stream()
            .map(e -> new CustomerClaimView.Document(e.documentRef(), e.description(), e.uploadedAt(), e.uploadedByName()))
            .toList();
        List<CustomerClaimView.DocumentRequest> requests = journeyApi.documentRequests(claimId).stream()
            .filter(r -> !"WITHDRAWN".equals(r.status()))
            .map(r -> new CustomerClaimView.DocumentRequest(r.requestId(), r.document(), r.reason(), r.requestedAt(),
                r.status(), r.receivedAt()))
            .toList();
        return new CustomerClaimView(claimId, claim.policyNumber(), productName(claim.policyNumber(), new HashMap<>()),
            claim.claimType().name(), claim.status().name(), statusText(claim.status()), claim.dateOfEvent(),
            steps(claim.status(), journey), decision(claim.status(), journey), journey.approvedAmount(), journey.currency(),
            documents, requests);
    }

    /** Received, reviewed, decided, paid -- each DONE, CURRENT or PENDING. A declined claim has no payment step. */
    static List<Step> steps(ClaimStatus status, ClaimJourneyApi.Journey j) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("Claim received", "DONE", j.receivedAt()));
        boolean decided = status == ClaimStatus.APPROVED || status == ClaimStatus.REJECTED
            || status == ClaimStatus.SETTLEMENT_REQUESTED || status == ClaimStatus.SETTLED;
        steps.add(new Step("Being reviewed", decided ? "DONE" : "CURRENT", j.reviewStartedAt()));
        if (status == ClaimStatus.REJECTED) {
            steps.add(new Step("Declined", "DONE", j.decidedAt()));
            return steps;
        }
        steps.add(new Step(decided ? "Approved" : "Decision", decided ? "DONE" : "PENDING", j.decidedAt()));
        String payment = status == ClaimStatus.SETTLED ? "DONE"
            : status == ClaimStatus.APPROVED || status == ClaimStatus.SETTLEMENT_REQUESTED ? "CURRENT" : "PENDING";
        steps.add(new Step(status == ClaimStatus.SETTLED ? "Paid" : "Payment", payment, j.settledAt()));
        return steps;
    }

    static String decision(ClaimStatus status, ClaimJourneyApi.Journey j) {
        if (j.approved() == null) return null;
        if (Boolean.TRUE.equals(j.approved())) {
            String amount = j.approvedAmount() == null ? "" : " for " + (j.currency() == null ? "" : j.currency() + " ")
                + NumberFormat.getNumberInstance(Locale.US).format(j.approvedAmount());
            return "Your claim was approved" + amount + "."
                + (status == ClaimStatus.SETTLED ? " It has been paid." : " Payment is being processed.");
        }
        return "Your claim was declined. " + declineWords(j.declineReason());
    }

    /** A coded decline reason in a claimant's words. A decline with no code says to contact us -- staff notes stay internal. */
    static String declineWords(String code) {
        if (code == null) return "Please contact us and we will explain the decision.";
        return switch (code) {
            case "WITHIN_WAITING_PERIOD" -> "The death happened during the waiting period, when the policy does not yet pay"
                + " for a death from natural causes. If it was an accident, contact us.";
            case "SUICIDE_WITHIN_EXCLUSION" -> "The policy does not pay for a death by suicide in its first period of cover.";
            case "PRE_EXISTING_WITHIN_EXCLUSION" -> "The claim relates to a condition that existed before cover started,"
                + " which the policy excludes in its first period.";
            default -> "Please contact us and we will explain the decision.";
        };
    }

    static String statusText(ClaimStatus status) {
        return switch (status) {
            case REGISTERED -> "Claim received";
            case UNDER_ASSESSMENT, REOPENED -> "Being reviewed";
            case APPROVED -> "Approved";
            case SETTLEMENT_REQUESTED -> "Payment being processed";
            case SETTLED -> "Paid";
            case REJECTED -> "Declined";
        };
    }

    private String productName(String policyNumber, Map<String, String> cache) {
        return cache.computeIfAbsent(policyNumber, n -> {
            try {
                return productApi.getProduct(policyApi.getPolicy(n).productId()).productName();
            } catch (RuntimeException e) {
                return null;
            }
        });
    }
}
