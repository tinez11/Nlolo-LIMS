package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.api.ClientPremiumsView;
import tz.co.nlolo.lifeplatform.omnichannel.application.ClientPremiums;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentPdf;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentXlsx;
import tz.co.nlolo.lifeplatform.organisation.api.DocumentKind;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationApi;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The client Premiums tab (2026-11-12): what a client owes across every policy, and the same as a file. Collections'
 * own permission -- the people who check and take payments -- as the tab asks it on the console.
 */
@RestController
public class ClientPremiumsController {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final ClientPremiums premiums;
    private final OrganisationApi organisation;

    public ClientPremiumsController(ClientPremiums premiums, OrganisationApi organisation) {
        this.premiums = premiums;
        this.organisation = organisation;
    }

    @GetMapping("/parties/{partyId}/premiums")
    @PreAuthorize("@access.can('collections.view')")
    public ClientPremiumsView premiums(@PathVariable UUID partyId, @RequestParam(defaultValue = "30") String window) {
        return premiums.read(partyId, window, LocalDate.now(CIVIL));
    }

    @GetMapping("/parties/{partyId}/premiums/{format}")
    @PreAuthorize("@access.can('collections.view')")
    public ResponseEntity<byte[]> premiumsFile(@PathVariable UUID partyId, @PathVariable String format,
                                               @RequestParam(defaultValue = "30") String window) {
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"xlsx".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("A document downloads as pdf or xlsx, not " + format);
        }
        LocalDate today = LocalDate.now(CIVIL);
        CustomerDocument document = premiums.document(partyId, window, today);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"premiums-" + today + (pdf ? ".pdf" : ".xlsx") + "\"")
            .contentType(pdf ? MediaType.APPLICATION_PDF : PolicyDocumentsController.XLSX)
            .body(pdf
                ? DocumentPdf.render(document, organisation.letterhead(),
                    organisation.signatory(DocumentKind.STATEMENT, today).orElse(null))
                : DocumentXlsx.render(document, organisation.letterhead()));
    }
}
