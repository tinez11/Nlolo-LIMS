package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.QuotationDocuments;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentPdf;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The quotation as a PDF, before any case exists -- beside the policy schedule and the other customer documents,
 * and drawn by the same {@link DocumentPdf} (2026-10-19).
 *
 * <p><b>An agent quotes only for its own clients.</b> The document prints the applicant's name, phone and address, so
 * an agents-realm caller naming a party it did not register gets the 404 the party-detail read gives it: "parties
 * the agent REGISTERED", as {@code GET /underwriting/cases} and {@code GET /parties} define it.
 */
@RestController
public class QuotationDocumentController {

    private final QuotationDocuments quotations;
    private final PartyApi partyApi;

    public QuotationDocumentController(QuotationDocuments quotations, PartyApi partyApi) {
        this.quotations = quotations;
        this.partyApi = partyApi;
    }

    /** Inputs only. No premium, total or benefit is accepted: every figure is quoted again on the server. */
    public record Body(@NotNull UUID productId, @NotNull UUID productVersionId, @NotNull UUID applicantPartyId,
                       @Positive BigDecimal sumAssured, String currency, String frequency, Integer policyTermMonths,
                       Integer premiumPayingTermMonths, LocalDate commencement, String funeralPlanCode,
                       List<Life> lives) {

        /** One life on a funeral quotation. A blank date of birth is passed through so the quoter names the problem. */
        public record Life(@NotNull FuneralRole role, String name, LocalDate dateOfBirth, boolean student) {}

        QuotationDocuments.Request toRequest() {
            return new QuotationDocuments.Request(productId, productVersionId, applicantPartyId, sumAssured, currency,
                frequency, policyTermMonths, premiumPayingTermMonths, commencement, funeralPlanCode,
                lives == null ? List.of() : lives.stream()
                    .map(l -> new FuneralLifeInput(l.role(), l.name(), l.dateOfBirth(), l.student())).toList());
        }
    }

    @PostMapping(value = "/quotations/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> quotation(@Valid @RequestBody Body body, @AuthenticationPrincipal Jwt jwt,
                                            Authentication authentication) {
        boolean agent = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_AGENTS"::equals);
        if (agent && !partyApi.partyIdsRegisteredBy(jwt.getSubject()).contains(body.applicantPartyId())) {
            throw new PartyNotFoundException(body.applicantPartyId());
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"quotation-" + body.productVersionId().toString().substring(0, 8) + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(DocumentPdf.render(quotations.quotation(body.toRequest())));
    }

    /** As {@code CustomerProductsController} answers it: a request this product cannot meet, in its own words. */
    @ExceptionHandler(CustomerRequestRefusedException.class)
    public ProblemDetail refused(CustomerRequestRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Cannot do that");
        problem.setProperty("errorCode", "CUSTOMER_REQUEST_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
