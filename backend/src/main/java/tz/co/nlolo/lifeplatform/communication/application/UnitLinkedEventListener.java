package tz.co.nlolo.lifeplatform.communication.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Unit-linked notices to the policyholder (product step 6, U1): the first premium bought units, the fund is running
 * low, the policy lapsed because the fund was exhausted, and a surrender's or maturity's proceeds are coming; and
 * (U2) the yearly unit statement is ready.
 * FuneralEventListener's shape. Only the FIRST premium's allocation is told -- one message a month for every
 * premium would be noise the customer learns to ignore.
 */
@Component("unitLinkedNoticeListener")
public class UnitLinkedEventListener {

    private static final Logger log = LoggerFactory.getLogger(UnitLinkedEventListener.class);
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NotificationApi notificationApi;
    private final TransactionTemplate requiresNew;

    public UnitLinkedEventListener(NotificationApi notificationApi, PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if (!type.startsWith("unitlinked.")) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) envelope.payload();
        String template = switch (type) {
            case "unitlinked.UnitsAllocated" -> Boolean.TRUE.equals(p.get("firstPremium")) ? "UNIT_LINKED_ALLOCATED" : null;
            case "unitlinked.LowFund" -> "UNIT_LINKED_LOW_FUND";
            case "unitlinked.FundExhausted" -> "UNIT_LINKED_LAPSED_EXHAUSTED";
            case "unitlinked.ProceedsReady" -> "UNIT_LINKED_PROCEEDS";
            // U2: the yearly statement only; an on-demand one is staff's and tells the customer nothing.
            case "unitlinked.StatementIssued" -> Boolean.TRUE.equals(p.get("annual")) ? "UNIT_LINKED_STATEMENT" : null;
            default -> null;
        };
        if (template == null || p.get("policyholderPartyId") == null) {
            return;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            String policyNumber = (String) p.get("policyNumber");
            UUID recipient = UUID.fromString(String.valueOf(p.get("policyholderPartyId")));
            Map<String, String> values = switch (template) {
                case "UNIT_LINKED_ALLOCATED" -> Map.of("policyNumber", policyNumber,
                    "allocated", (String) p.get("allocated"), "currency", (String) p.get("currencyCode"));
                case "UNIT_LINKED_LOW_FUND" -> Map.of("policyNumber", policyNumber, "fundValue", (String) p.get("fundValue"),
                    "monthsCovered", (String) p.get("monthsCovered"), "currency", (String) p.get("currencyCode"));
                case "UNIT_LINKED_LAPSED_EXHAUSTED" -> Map.of("policyNumber", policyNumber, "on", day(p.get("on")));
                case "UNIT_LINKED_STATEMENT" -> Map.of("policyNumber", policyNumber, "year", String.valueOf(p.get("year")),
                    "closingValue", (String) p.get("closingValue"), "currency", (String) p.get("currencyCode"),
                    "priceDate", p.get("priceDate") == null ? "-" : day(p.get("priceDate")));
                default -> Map.of("policyNumber", policyNumber, "amount", (String) p.get("amount"),
                    "currency", (String) p.get("currencyCode"));
            };
            requiresNew.executeWithoutResult(status ->
                notificationApi.notify(envelope.eventId(), recipient, policyNumber, template, Map.copyOf(values)));
        } catch (Exception e) {
            // The dispatch row records any failure for the desk; the unit movement itself stands.
            log.error("Failed to send the {} notice for policy {}", template, p.get("policyNumber"), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    private static String day(Object isoDate) {
        return LocalDate.parse((String) isoDate).format(DAY_MONTH_YEAR);
    }
}
