package tz.co.nlolo.lifeplatform.dashboard.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The latest events of one or more modules, for a dashboard's "Recent activity" (2026-10-14).
 *
 * <p>Reads the audit log the same way {@code AuditLogController} does -- newest first, no date bounds -- and keeps the
 * newest {@link #SIZE} across the prefixes asked for. Each prefix is read separately and the results merged by time:
 * ordering, not arithmetic.
 */
@Component
public class RecentActivity {

    static final int SIZE = 8;

    /** Read per prefix before the noise is dropped, so a burst of noise still leaves events to show. */
    private static final int FETCH = 40;

    /**
     * Events the platform raises by itself in bulk -- one per GL posting, one per earned-revenue run, one per invoice
     * in a billing run. Kept, they would fill the feed and bury everything a person did.
     */
    static final Set<String> NOISE = Set.of("finaccounting.GlPostingRecorded", "finaccounting.PaaRevenueEarned",
        "billing.PremiumInvoiceGenerated");

    /** The only payload keys that leave the server. A payer's phone number or a free-text reason never does. */
    static final List<String> REF_KEYS = List.of("caseId", "outcome", "claimId", "claimType", "policyNumber",
        "receiptId", "disbursementId", "purpose");

    /** Where an event keeps its money: plainly, or under the name of what the money was. */
    private static final List<String> AMOUNT_KEYS = List.of("amount", "settledAmount", "approvedAmount");

    private final AuditApi audit;
    private final ObjectReader json;

    public RecentActivity(AuditApi audit, ObjectMapper mapper) {
        this.audit = audit;
        // Decimals as BigDecimal, so "62500.00" keeps its cents instead of becoming the double 62500.0.
        this.json = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public List<ActivityItem> latest(String... prefixes) {
        return Arrays.stream(prefixes)
            .flatMap(prefix -> audit.listEvents(prefix, new DateRange(null, null),
                PageRequest.of(0, FETCH, Sort.by(Sort.Direction.DESC, "occurredAt"))).getContent().stream())
            .filter(e -> !NOISE.contains(e.eventType()))
            .sorted(Comparator.comparing(AuditEntryView::occurredAt).reversed())
            .limit(SIZE)
            .map(this::item)
            .toList();
    }

    private ActivityItem item(AuditEntryView event) {
        Map<String, String> refs = new LinkedHashMap<>();
        Money amount = null;
        try {
            JsonNode payload = json.readTree(event.payloadJson() == null ? "{}" : event.payloadJson());
            for (String key : REF_KEYS) {
                JsonNode value = payload.get(key);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    refs.put(key, value.asText());
                }
            }
            for (String key : AMOUNT_KEYS) {
                JsonNode money = payload.get(key);
                if (money != null && money.isObject() && money.hasNonNull("amount") && money.hasNonNull("currencyCode")) {
                    JsonNode value = money.get("amount");
                    BigDecimal decimal = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
                    amount = Money.of(decimal, money.get("currencyCode").asText());
                    break;
                }
            }
        } catch (Exception unreadable) {
            // An unreadable payload still says WHAT happened and WHEN; it just names nothing.
            refs.clear();
            amount = null;
        }
        return new ActivityItem(event.eventType(), event.occurredAt(), Map.copyOf(refs), amount);
    }
}
