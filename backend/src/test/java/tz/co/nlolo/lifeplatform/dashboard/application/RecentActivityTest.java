package tz.co.nlolo.lifeplatform.dashboard.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecentActivityTest {

    private final AuditApi audit = mock(AuditApi.class);
    private final RecentActivity activity = new RecentActivity(audit, new ObjectMapper());

    private static AuditEntryView event(String type, String at, String payload) {
        return new AuditEntryView(UUID.randomUUID(), type, Instant.parse(at), payload);
    }

    @Test
    void mergesItsPrefixesNewestFirstAndDropsSystemNoise() {
        when(audit.listEvents(eq("billing."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("billing.PremiumCollected", "2026-10-14T08:00:00Z",
                "{\"policyNumber\":\"POL-1\",\"amount\":{\"amount\":62500.00,\"currencyCode\":\"TZS\"}}"),
            event("billing.PremiumInvoiceGenerated", "2026-10-14T09:00:00Z", "{}"))));
        when(audit.listEvents(eq("payment."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("payment.EftDisbursementExecuted", "2026-10-14T10:00:00Z", "{\"disbursementId\":\"d-1\"}"))));

        List<ActivityItem> items = activity.latest("billing.", "payment.");

        assertThat(items).extracting(ActivityItem::eventType)
            .containsExactly("payment.EftDisbursementExecuted", "billing.PremiumCollected");
        ActivityItem premium = items.get(1);
        assertThat(premium.refs()).containsEntry("policyNumber", "POL-1");
        // A number in the payload keeps its cents: "62500.00", never "62500.0".
        assertThat(premium.amount().amount()).isEqualTo("62500.00");
        assertThat(premium.amount().currencyCode()).isEqualTo("TZS");
    }

    /** Only whitelisted references leave the server -- a payer's phone number does not reach a dashboard. */
    @Test
    void passesOnlyWhitelistedReferences() {
        when(audit.listEvents(eq("billing."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("billing.PremiumCollected", "2026-10-14T08:00:00Z",
                "{\"policyNumber\":\"POL-1\",\"payerRef\":\"+255712345678\"}"))));

        assertThat(activity.latest("billing.").get(0).refs()).containsOnlyKeys("policyNumber");
    }

    /** The settled and approved amounts live under their own names on claim events. */
    @Test
    void readsAClaimsSettledAmount() {
        when(audit.listEvents(eq("claims."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("claims.ClaimSettled", "2026-10-14T08:00:00Z",
                "{\"claimId\":\"c-1\",\"settledAmount\":{\"amount\":\"10000000.00\",\"currencyCode\":\"TZS\"}}"))));

        ActivityItem settled = activity.latest("claims.").get(0);

        assertThat(settled.refs()).containsEntry("claimId", "c-1");
        assertThat(settled.amount().amount()).isEqualTo("10000000.00");
    }

    /** An unreadable payload still says what happened and when; it just names nothing. */
    @Test
    void survivesAPayloadItCannotRead() {
        when(audit.listEvents(eq("claims."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("claims.ClaimRegistered", "2026-10-14T08:00:00Z", "not json"))));

        ActivityItem item = activity.latest("claims.").get(0);

        assertThat(item.eventType()).isEqualTo("claims.ClaimRegistered");
        assertThat(item.refs()).isEmpty();
        assertThat(item.amount()).isNull();
    }

    @Test
    void keepsTheNewestEightAndAsksForTheNewestFirst() {
        List<AuditEntryView> twelve = IntStream.range(0, 12)
            .mapToObj(i -> event("claims.ClaimAssessed", "2026-10-14T0" + (i % 10) + ":0" + (i / 10) + ":00Z", "{}"))
            .toList();
        when(audit.listEvents(eq("claims."), any(), argThat(p -> p != null
            && p.getSort().getOrderFor("occurredAt") != null
            && p.getSort().getOrderFor("occurredAt").isDescending()))).thenReturn(new PageImpl<>(twelve));

        assertThat(activity.latest("claims.")).hasSize(RecentActivity.SIZE);
    }
}
