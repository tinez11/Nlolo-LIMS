package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceNotFoundException;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerDashboardView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerDashboardView.Amount;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerDashboardView.CustomerPolicySummary;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerDashboardView.NextPremium;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerPolicyView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a signed-in customer sees of their own business (2026-10-08, the customer portal design step 2). A channel, not
 * a calculator: every status, amount and value is read from the module that owns it, and the customer is always the
 * token's party -- never one the request names. A policy they do not hold is refused, not hidden behind a 404.
 */
@Service
public class CustomerPortal {

    /** The statuses a customer reads as "in force": what the dashboard counts as an active policy. */
    private static final Set<PolicyStatus> IN_FORCE = EnumSet.of(PolicyStatus.ACTIVE, PolicyStatus.REINSTATED,
        PolicyStatus.PAID_UP);
    /** A claim is in progress until it is paid or declined. */
    private static final Set<ClaimStatus> CLOSED = EnumSet.of(ClaimStatus.SETTLED, ClaimStatus.REJECTED);

    private final PolicyApi policyApi;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final BillingApi billingApi;
    private final ClaimsApi claimsApi;
    private final AccumulationApi accumulationApi;
    private final UnitLinkedApi unitLinkedApi;
    private final AnnuityApi annuityApi;
    private final tz.co.nlolo.lifeplatform.communication.api.NotificationApi notificationApi;

    public CustomerPortal(PolicyApi policyApi, PartyApi partyApi, ProductApi productApi, BillingApi billingApi,
                          ClaimsApi claimsApi, AccumulationApi accumulationApi, UnitLinkedApi unitLinkedApi,
                          AnnuityApi annuityApi,
                          tz.co.nlolo.lifeplatform.communication.api.NotificationApi notificationApi) {
        this.notificationApi = notificationApi;
        this.policyApi = policyApi;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.billingApi = billingApi;
        this.claimsApi = claimsApi;
        this.accumulationApi = accumulationApi;
        this.unitLinkedApi = unitLinkedApi;
        this.annuityApi = annuityApi;
    }

    @Transactional(readOnly = true)
    public CustomerDashboardView dashboard(UUID customerPartyId) {
        List<PolicyView> held = policyApi.searchPolicies(customerPartyId, null, null, null, null, PageRequest.of(0, 100))
            .getContent();
        Map<UUID, String> productNames = new HashMap<>();
        List<CustomerPolicySummary> summaries = new ArrayList<>();
        NextPremium next = null;
        BigDecimal value = null;
        String valueCurrency = null;
        for (PolicyView policy : held) {
            CustomerPolicySummary summary = summary(policy, productNames);
            summaries.add(summary);
            if (summary.nextDueDate() != null && (next == null || summary.nextDueDate().isBefore(next.dueDate()))) {
                InvoiceView invoice = nextDue(policy.policyNumber()).orElseThrow();
                next = new NextPremium(policy.policyNumber(), summary.productName(), invoice.balanceDue(),
                    invoice.currency(), invoice.dueDate(), invoice.status().name());
            }
            if (summary.value() != null) {
                value = (value == null ? BigDecimal.ZERO : value).add(summary.value());
                valueCurrency = summary.currency();
            }
        }
        int active = (int) held.stream().filter(p -> IN_FORCE.contains(p.status())).count();
        int claimsInProgress = held.isEmpty() ? 0 : (int) claimsOn(held.stream().map(PolicyView::policyNumber)
            .collect(Collectors.toSet())).stream().filter(c -> !CLOSED.contains(c.status())).count();
        summaries.sort(Comparator.comparing(CustomerPolicySummary::policyNumber));
        return new CustomerDashboardView(partyApi.getParty(customerPartyId).displayName(), active, claimsInProgress,
            next, value == null ? null : new Amount(value, valueCurrency), summaries,
            (int) notificationApi.inbox(customerPartyId).stream().filter(m -> !m.read()).count());
    }

    @Transactional(readOnly = true)
    public CustomerPolicyView policy(UUID customerPartyId, String policyNumber) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (!customerPartyId.equals(policy.policyholderPartyId())) {
            throw new AccessDeniedException("Policy " + policyNumber + " is not held by this customer");
        }
        CustomerPolicySummary summary = summary(policy, new HashMap<>());
        List<CustomerPolicyView.Beneficiary> beneficiaries = (policy.beneficiaries() == null ? List.<BeneficiaryView>of()
            : policy.beneficiaries()).stream()
            .map(b -> new CustomerPolicyView.Beneficiary(b.partyId() != null ? partyApi.getParty(b.partyId()).displayName()
                : b.freeformDesignee(), b.sharePercent()))
            .toList();
        List<CustomerPolicyView.CoveredLife> lives = "FUNERAL".equals(policy.productCategory())
            ? policyApi.coveredLives(policyNumber).stream()
                .map(l -> new CustomerPolicyView.CoveredLife(l.coveredLifeId(), l.fullName(), l.role().name(), l.benefit(), l.status(),
                    l.waitingPeriodEnds()))
                .toList()
            : null;
        CustomerPolicyView.Savings savings = accumulationApi.findAccount(policyNumber)
            .map(a -> new CustomerPolicyView.Savings(a.balance(), a.currency(), a.status().name(), a.openedOn()))
            .orElse(null);
        CustomerPolicyView.Units units = null;
        if ("UNIT_LINKED".equals(policy.productCategory())) {
            PolicyUnitsView held = unitLinkedApi.units(policyNumber);
            units = new CustomerPolicyView.Units(held.totalValue(), held.currency(), held.holdings().stream()
                .map(h -> new CustomerPolicyView.Holding(h.fundName(), h.units(), h.price(), h.priceDate(), h.value()))
                .toList());
        }
        CustomerPolicyView.Annuity annuity = annuityApi.contract(policyNumber)
            .map(c -> new CustomerPolicyView.Annuity(c.status().name(), c.annualIncome(), c.instalment(), c.frequency(),
                c.firstDueDate(), c.guaranteeEndDate(), c.purchasePrice(), c.currency()))
            .orElse(null);
        List<CustomerPolicyView.ClaimLine> claims = claimsOn(Set.of(policyNumber)).stream()
            .sorted(Comparator.comparing(ClaimView::dateOfEvent).reversed())
            .map(c -> new CustomerPolicyView.ClaimLine(c.claimId(), c.claimType().name(), c.status().name(),
                c.dateOfEvent(), c.approvedAmount(), c.approvedCurrency()))
            .toList();
        String lifeAssured = policy.lifeAssuredPartyId() == null ? null
            : partyApi.getParty(policy.lifeAssuredPartyId()).displayName();
        return new CustomerPolicyView(summary, lifeAssured, policy.commencementDate(), policy.maturityDate(),
            policy.policyTermMonths(), beneficiaries, lives, savings, units, annuity, claims);
    }

    private CustomerPolicySummary summary(PolicyView policy, Map<UUID, String> productNames) {
        String productName = productNames.computeIfAbsent(policy.productId(),
            id -> productApi.getProduct(id).productName());
        Optional<InvoiceView> due = nextDue(policy.policyNumber());
        BigDecimal value = accumulationApi.findAccount(policy.policyNumber()).map(a -> a.balance()).orElse(null);
        if (value == null && "UNIT_LINKED".equals(policy.productCategory())) {
            value = unitLinkedApi.units(policy.policyNumber()).totalValue();
        }
        String currency = policy.premiumCurrency() != null ? policy.premiumCurrency() : policy.sumAssuredCurrency();
        return new CustomerPolicySummary(policy.policyNumber(), productName, policy.productCategory(),
            policy.status().name(), policy.sumAssuredAmount(), currency, policy.premiumAmount(), policy.premiumFrequency(),
            due.map(InvoiceView::dueDate).orElse(null), due.map(InvoiceView::balanceDue).orElse(null), value);
    }

    private Optional<InvoiceView> nextDue(String policyNumber) {
        try {
            return Optional.ofNullable(billingApi.getNextDueInvoice(policyNumber));
        } catch (InvoiceNotFoundException none) {
            return Optional.empty();
        }
    }

    private List<ClaimView> claimsOn(Set<String> policyNumbers) {
        return claimsApi.searchClaims(null, null, policyNumbers, null, PageRequest.of(0, 100)).getContent();
    }
}
