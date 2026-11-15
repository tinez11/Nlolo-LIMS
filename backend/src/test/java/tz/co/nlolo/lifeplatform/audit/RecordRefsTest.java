package tz.co.nlolo.lifeplatform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.audit.application.RecordRefs;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordRefsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void thePathVariableNamesTheRecord() {
        assertThat(RecordRefs.of(Map.of("policyNumber", "POL-1"), "/policies/{policyNumber}/lapse"))
            .isEqualTo(new RecordRefs.Ref("policy", "POL-1"));
        assertThat(RecordRefs.of(Map.of("partyId", "p-1"), "/parties/{partyId}"))
            .isEqualTo(new RecordRefs.Ref("client", "p-1"));
        assertThat(RecordRefs.of(Map.of("userId", "u-1"), "/users/{userId}/access"))
            .isEqualTo(new RecordRefs.Ref("staff user", "u-1"));
    }

    @Test
    void aRouteNamingSeveralIsAboutTheMostSpecific() {
        Map<String, String> both = new LinkedHashMap<>();
        both.put("policyNumber", "POL-1");
        both.put("claimId", "c-1");
        assertThat(RecordRefs.of(both, "/policies/{policyNumber}/claims/{claimId}"))
            .isEqualTo(new RecordRefs.Ref("claim", "c-1"));
    }

    @Test
    void anUnknownVariableNamesItsFirstPathSegment() {
        assertThat(RecordRefs.of(Map.of("treatyId", "t-1"), "/treaties/{treatyId}/approve"))
            .isEqualTo(new RecordRefs.Ref("treaties", "t-1"));
        assertThat(RecordRefs.of(Map.of(), "/products")).isNull();
    }

    @Test
    void theAnswerGivesTheRecordAReadableName() throws Exception {
        assertThat(RecordRefs.label(json.readTree("{\"claimNumber\":\"CLM-0042\",\"policyNumber\":\"POL-1\"}")))
            .isEqualTo("CLM-0042");
        assertThat(RecordRefs.label(json.readTree("{\"fullName\":\"Juma K\"}"))).isEqualTo("Juma K");
        assertThat(RecordRefs.label(json.readTree("[1,2]"))).isNull();
    }

    @Test
    void moneyIsReadFromTheRequestFirstThenTheAnswer() throws Exception {
        assertThat(RecordRefs.amount(json.readTree("{\"amount\":{\"amount\":\"1500.50\",\"currencyCode\":\"TZS\"}}"), null))
            .isEqualTo(new RecordRefs.Amount(new BigDecimal("1500.50"), "TZS"));
        assertThat(RecordRefs.amount(json.readTree("{\"note\":\"x\"}"), json.readTree("{\"approvedAmount\":\"200\"}")))
            .isEqualTo(new RecordRefs.Amount(new BigDecimal("200"), "TZS"));
        assertThat(RecordRefs.amount(json.readTree("{\"amount\":\"lots\"}"), null)).isNull();
    }
}
