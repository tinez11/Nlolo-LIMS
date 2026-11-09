package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicApplications;

import java.util.UUID;

/**
 * For staff: how a case opened through the public quote link came in (2026-11-08) -- the confirmed phone, the
 * declaration agreed to, and anyone already on the books who may be the same person. Never merged; shown so an
 * underwriter can look before deciding (the user's decision).
 */
@RestController
public class PublicApplicationController {

    private final PublicApplications applications;

    public PublicApplicationController(PublicApplications applications) {
        this.applications = applications;
    }

    @GetMapping("/underwriting/cases/{caseId}/public-application")
    @PreAuthorize("@access.can('underwriting.view')")
    public ResponseEntity<PublicApplications.StaffView> forCase(@PathVariable UUID caseId) {
        return applications.forCase(caseId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
