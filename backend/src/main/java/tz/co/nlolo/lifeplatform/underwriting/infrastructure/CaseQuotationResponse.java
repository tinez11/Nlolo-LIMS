package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationView;

import java.time.LocalDate;
import java.util.UUID;

/** {@code GET /underwriting/cases/{caseId}/quotation}: what the customer was quoted and agreed to (2026-10-20). */
public record CaseQuotationResponse(UUID quotationId, UUID productVersionId, JsonNode inputs, JsonNode quoted,
                                    LocalDate quotedOn, LocalDate validUntil) {

    static CaseQuotationResponse from(AcceptedQuotationView q, ObjectMapper mapper) {
        try {
            return new CaseQuotationResponse(q.quotationId(), q.productVersionId(), mapper.readTree(q.inputs()),
                mapper.readTree(q.quoted()), q.quotedOn(), q.validUntil());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A saved quotation could not be read", e);
        }
    }
}
