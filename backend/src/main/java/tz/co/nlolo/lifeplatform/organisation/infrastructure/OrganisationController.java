package tz.co.nlolo.lifeplatform.organisation.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;
import tz.co.nlolo.lifeplatform.organisation.api.BranchView;
import tz.co.nlolo.lifeplatform.organisation.api.BrandingView;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationSettingsException;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationSettingsView;
import tz.co.nlolo.lifeplatform.organisation.api.ProfileView;
import tz.co.nlolo.lifeplatform.organisation.api.SignatoryInput;
import tz.co.nlolo.lifeplatform.organisation.api.SignatoryView;
import tz.co.nlolo.lifeplatform.organisation.application.Organisation;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Settings → Organisation (2026-11-09): read and changed by administrators alone ({@code organisation.manage}, decision
 * O2). The branch list is read by any staff member, for the branch pickers; the logo too, for the console.
 */
@RestController
public class OrganisationController {

    private final Organisation organisation;

    public OrganisationController(Organisation organisation) {
        this.organisation = organisation;
    }

    @GetMapping("/organisation/settings")
    @PreAuthorize("@access.can('organisation.manage')")
    public OrganisationSettingsView settings() {
        return organisation.settings();
    }

    @PutMapping("/organisation/profile")
    @PreAuthorize("@access.can('organisation.manage')")
    public ProfileView saveProfile(@RequestBody ProfileView profile, @AuthenticationPrincipal Jwt jwt) {
        return organisation.saveProfile(profile, who(jwt));
    }

    @PutMapping("/organisation/branding")
    @PreAuthorize("@access.can('organisation.manage')")
    public BrandingView saveBranding(@RequestBody BrandingView branding, @AuthenticationPrincipal Jwt jwt) {
        return organisation.saveBranding(branding, who(jwt));
    }

    @PostMapping(value = "/organisation/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.can('organisation.manage')")
    public ResponseEntity<Void> uploadLogo(@RequestPart("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt)
            throws IOException {
        organisation.uploadLogo(file.getBytes(), file.getContentType(), file.getOriginalFilename(), who(jwt));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/organisation/logo")
    @PreAuthorize("@access.can('organisation.manage')")
    public ResponseEntity<Void> removeLogo(@AuthenticationPrincipal Jwt jwt) {
        organisation.removeLogo(who(jwt));
        return ResponseEntity.noContent().build();
    }

    /** The logo as stored, for the settings page and the console; 404 when the company has none. */
    @GetMapping("/organisation/logo")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> logo() {
        byte[] logo = organisation.logo();
        if (logo == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(sniff(logo)).body(logo);
    }

    @PostMapping("/organisation/signatories")
    @PreAuthorize("@access.can('organisation.manage')")
    public ResponseEntity<SignatoryView> addSignatory(@RequestBody SignatoryInput in, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organisation.addSignatory(in, who(jwt)));
    }

    @PutMapping("/organisation/signatories/{signatoryId}")
    @PreAuthorize("@access.can('organisation.manage')")
    public SignatoryView updateSignatory(@PathVariable UUID signatoryId, @RequestBody SignatoryInput in,
                                         @AuthenticationPrincipal Jwt jwt) {
        return organisation.updateSignatory(signatoryId, in, who(jwt));
    }

    @PostMapping(value = "/organisation/signatories/{signatoryId}/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.can('organisation.manage')")
    public SignatoryView uploadSignature(@PathVariable UUID signatoryId, @RequestPart("file") MultipartFile file,
                                         @AuthenticationPrincipal Jwt jwt) throws IOException {
        return organisation.uploadSignature(signatoryId, file.getBytes(), file.getContentType(),
            file.getOriginalFilename(), who(jwt));
    }

    @GetMapping("/organisation/signatories/{signatoryId}/signature")
    @PreAuthorize("@access.can('organisation.manage')")
    public ResponseEntity<byte[]> signature(@PathVariable UUID signatoryId) {
        byte[] image = organisation.signature(signatoryId);
        if (image == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(sniff(image)).body(image);
    }

    /** The company's branches, for every branch picker on the console. */
    @GetMapping("/organisation/branches")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<BranchView> branches() {
        return organisation.branches();
    }

    @PutMapping("/organisation/branches/{code}")
    @PreAuthorize("@access.can('organisation.manage')")
    public BranchView saveBranch(@PathVariable String code, @RequestBody BranchView branch, @AuthenticationPrincipal Jwt jwt) {
        return organisation.saveBranch(new BranchView(code, branch.name(), branch.address(), branch.phone(),
            branch.email(), branch.manager(), branch.active(), false), who(jwt));
    }

    /** A sample receipt in the draft profile and branding, before either is saved. */
    public record PreviewRequest(ProfileView profile, BrandingView branding) {}

    @PostMapping(value = "/organisation/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("@access.can('organisation.manage')")
    public ResponseEntity<byte[]> preview(@RequestBody PreviewRequest request) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .body(organisation.preview(request.profile(), request.branding()));
    }

    private static String who(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = jwt.getClaimAsString("preferred_username");
        return name == null || name.isBlank() ? jwt.getSubject() : name;
    }

    private static MediaType sniff(byte[] image) {
        return image.length > 3 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
    }

    /** A setting that cannot be saved is a 400 in the administrator's words. */
    @RestControllerAdvice(assignableTypes = OrganisationController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Errors {
        @ExceptionHandler(OrganisationSettingsException.class)
        ProblemDetail invalid(OrganisationSettingsException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
            problem.setProperty("errorCode", "ORGANISATION_SETTING_INVALID");
            problem.setProperty("traceId", UUID.randomUUID().toString());
            return problem;
        }
    }
}
