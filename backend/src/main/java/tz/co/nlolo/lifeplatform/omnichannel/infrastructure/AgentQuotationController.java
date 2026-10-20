package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.QuotationDocuments;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationApi;
import tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationView;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * From "the customer agrees" to a proposal in the underwriter's queue (2026-10-20, the agents' prospect-to-proposal
 * flow).
 *
 * <p><b>The first write is the agent's confirmation.</b> Quoting and the PDF store nothing; {@code POST} here saves the
 * quotation the customer accepted -- its inputs, and the figures priced again on the server from them. The agent's
 * cancel deletes it; a quotation never submitted is swept at the end of the day.
 *
 * <p><b>The proposal is the saved quotation.</b> Submitting opens the case from the stored inputs and the stored
 * version priced, never from anything sent again, so what the underwriter sees is what the customer was quoted.
 *
 * <p><b>One agent's own.</b> Every quotation belongs to the agent who confirmed it, and a proposal is opened only for a
 * client that agent registered -- "my clients" as {@code GET /parties} defines it.
 */
@RestController
@PreAuthorize("hasRole('REALM_AGENTS')")
public class AgentQuotationController {

    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final QuotationDocuments quotations;
    private final AcceptedQuotationApi accepted;
    private final UnderwritingApi underwritingApi;
    private final PartyApi partyApi;
    private final ObjectMapper objectMapper;

    public AgentQuotationController(QuotationDocuments quotations, AcceptedQuotationApi accepted,
                                    UnderwritingApi underwritingApi, PartyApi partyApi, ObjectMapper objectMapper) {
        this.quotations = quotations;
        this.accepted = accepted;
        this.underwritingApi = underwritingApi;
        this.partyApi = partyApi;
        this.objectMapper = objectMapper;
    }

    /** The saved quotation: its inputs as quoted and the figures as priced. */
    public record AgentQuotation(UUID quotationId, UUID applicantPartyId, UUID productId, UUID productVersionId,
                                 JsonNode inputs, JsonNode quoted, LocalDate quotedOn, LocalDate validUntil,
                                 UUID caseId, String proposalNumber) {}

    public record ProposalBody(@NotNull UUID applicantPartyId) {}

    @PostMapping("/agent-quotations")
    public ResponseEntity<AgentQuotation> accept(@Valid @RequestBody QuotationDocumentController.Body body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        if ((body.applicantPartyId() == null) == (body.prospect() == null)) {
            throw new CustomerRequestRefusedException("A quotation is for a registered applicant or for a prospect: name one");
        }
        if (body.applicantPartyId() != null) requireOwnClient(body.applicantPartyId(), jwt);

        QuotationDocuments.Priced priced = quotations.price(body.toRequest(null));
        Map<String, Object> quoted = new LinkedHashMap<>();
        quoted.put("sumAssured", priced.sumAssured());
        quoted.put("currency", priced.currency());
        quoted.put("quote", priced.quote());
        LocalDate today = LocalDate.now(CIVIL_ZONE);

        AcceptedQuotationView saved = accepted.accept(jwt.getSubject(), body.applicantPartyId(), body.productId(),
            priced.productVersionId(), json(body), json(quoted), today, QuotationDocuments.validUntil(today));
        return ResponseEntity.status(HttpStatus.CREATED).body(response(saved, null));
    }

    @GetMapping("/agent-quotations/{quotationId}")
    public ResponseEntity<AgentQuotation> get(@PathVariable UUID quotationId, @AuthenticationPrincipal Jwt jwt) {
        AcceptedQuotationView saved = accepted.get(quotationId, jwt.getSubject());
        return ResponseEntity.ok(response(saved, proposalNumber(saved)));
    }

    @DeleteMapping("/agent-quotations/{quotationId}")
    public ResponseEntity<Void> discard(@PathVariable UUID quotationId, @AuthenticationPrincipal Jwt jwt) {
        accepted.discard(quotationId, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    /**
     * Opens the case from the saved quotation. One transaction: a funeral family the plan refuses rolls the case back,
     * so a 422 leaves no half-opened proposal behind (the open-case endpoint's own rule).
     */
    @PostMapping("/agent-quotations/{quotationId}/proposal")
    @Transactional
    public ResponseEntity<AgentQuotation> submit(@PathVariable UUID quotationId, @Valid @RequestBody ProposalBody body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        AcceptedQuotationView saved = accepted.get(quotationId, jwt.getSubject());
        if (saved.caseId() != null) {
            throw new CustomerRequestRefusedException("This quotation was already submitted as a proposal");
        }
        requireOwnClient(body.applicantPartyId(), jwt);
        if (saved.applicantPartyId() != null && !saved.applicantPartyId().equals(body.applicantPartyId())) {
            throw new CustomerRequestRefusedException("This quotation was for another client");
        }

        QuotationDocumentController.Body inputs = read(saved.inputs(), QuotationDocumentController.Body.class);
        JsonNode quoted = read(saved.quoted(), JsonNode.class);
        BigDecimal sumAssured = quoted.path("sumAssured").isNumber() ? quoted.path("sumAssured").decimalValue() : null;
        String currency = quoted.path("currency").asText(null);

        UnderwritingCaseView opened = underwritingApi.openCase(body.applicantPartyId(), saved.productId(),
            saved.productVersionId(), sumAssured, currency, null,
            new ProposalDetails(null, null, "Agent quotation", inputs.commencement(), inputs.policyTermMonths(),
                inputs.premiumPayingTermMonths(), inputs.frequency(), List.of()),
            jwt.getSubject());
        if (inputs.funeralPlanCode() != null && !inputs.funeralPlanCode().isBlank()) {
            // The main member is the registered customer; the family travels as it was quoted.
            List<FuneralApplication.Life> dependants = (inputs.lives() == null ? List.<QuotationDocumentController.Body.Life>of()
                : inputs.lives()).stream()
                .filter(l -> l.role() != FuneralRole.MAIN_MEMBER)
                .map(l -> new FuneralApplication.Life(l.role(), l.name(), l.dateOfBirth(), null, null, l.student()))
                .toList();
            underwritingApi.recordFuneralApplication(opened.caseId(), inputs.funeralPlanCode(), dependants,
                jwt.getSubject());
        }
        AcceptedQuotationView submitted = accepted.markSubmitted(quotationId, jwt.getSubject(), opened.caseId(),
            body.applicantPartyId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response(submitted, opened.proposalNumber()));
    }

    /** A client this agent registered, or the 404 the party read gives any other. */
    private void requireOwnClient(UUID partyId, Jwt jwt) {
        if (!partyApi.partyIdsRegisteredBy(jwt.getSubject()).contains(partyId)) {
            throw new PartyNotFoundException(partyId);
        }
    }

    private String proposalNumber(AcceptedQuotationView saved) {
        return saved.caseId() == null ? null : underwritingApi.getCase(saved.caseId()).proposalNumber();
    }

    private AgentQuotation response(AcceptedQuotationView saved, String proposalNumber) {
        return new AgentQuotation(saved.quotationId(), saved.applicantPartyId(), saved.productId(),
            saved.productVersionId(), read(saved.inputs(), JsonNode.class), read(saved.quoted(), JsonNode.class),
            saved.quotedOn(), saved.validUntil(), saved.caseId(), proposalNumber);
    }

    /** As the quotation PDF answers it: a request this product cannot meet, in its own words. */
    @org.springframework.web.bind.annotation.ExceptionHandler(CustomerRequestRefusedException.class)
    public org.springframework.http.ProblemDetail refused(CustomerRequestRefusedException e) {
        org.springframework.http.ProblemDetail problem =
            org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Cannot do that");
        problem.setProperty("errorCode", "CUSTOMER_REQUEST_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A quotation could not be written as JSON", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A saved quotation could not be read", e);
        }
    }
}
