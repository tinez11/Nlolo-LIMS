package tz.co.nlolo.lifeplatform.reporting.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A live payload carries Java objects; the same payload read back from the audit journal is JSON. Every
 * reader must give the same answer for both, or a rebuild would project different rows than live did. */
class PayloadReaderTest {

    private static final UUID PRODUCT = UUID.fromString("7f8e0f9a-1111-4c2d-9e0b-222222222222");

    private static Map<String, Object> typed() {
        Map<String, Object> p = new HashMap<>();
        p.put("productId", PRODUCT);
        p.put("issueDate", LocalDate.of(2026, 3, 4));
        p.put("lapsedAt", Instant.parse("2026-05-31T22:15:00Z"));
        p.put("livesCovered", 12);
        p.put("groupScheme", true);
        p.put("sumAssured", Map.of("amount", "1500000.00", "currencyCode", "TZS"));
        p.put("paidUpSumAssured", "400000.00");
        p.put("agentOfRecordId", null);
        return p;
    }

    private static Map<String, Object> asJournal(Map<String, Object> typed) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper.readValue(mapper.writeValueAsString(typed), new TypeReference<>() {});
    }

    @Test
    void typedAndJournalPayloadsReadTheSame() throws Exception {
        for (Map<String, Object> p : List.of(typed(), asJournal(typed()))) {
            assertThat(PayloadReader.uuid(p, "productId")).isEqualTo(PRODUCT);
            assertThat(PayloadReader.date(p, "issueDate")).isEqualTo(LocalDate.of(2026, 3, 4));
            assertThat(PayloadReader.instant(p, "lapsedAt")).isEqualTo(Instant.parse("2026-05-31T22:15:00Z"));
            assertThat(PayloadReader.integer(p, "livesCovered")).isEqualTo(12);
            assertThat(PayloadReader.flag(p, "groupScheme")).isTrue();
            assertThat(PayloadReader.amount(p, "sumAssured")).isEqualByComparingTo("1500000.00");
            assertThat(PayloadReader.currency(p, "sumAssured")).isEqualTo("TZS");
            assertThat(PayloadReader.amount(p, "paidUpSumAssured")).isEqualByComparingTo(new BigDecimal("400000.00"));
            assertThat(PayloadReader.uuid(p, "agentOfRecordId")).isNull();
            assertThat(PayloadReader.text(p, "missing")).isNull();
        }
    }

    @Test
    void anEpochSecondsInstantIsAccepted() {
        assertThat(PayloadReader.instant(Map.of("at", "1780265700.000000000"), "at"))
            .isEqualTo(Instant.ofEpochSecond(1780265700L));
    }
}
