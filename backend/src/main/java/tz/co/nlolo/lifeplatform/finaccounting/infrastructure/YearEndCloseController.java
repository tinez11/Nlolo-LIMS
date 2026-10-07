package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.YearEndCloseView;
import tz.co.nlolo.lifeplatform.finaccounting.application.YearEndCloses;

import java.util.List;
import java.util.UUID;

/**
 * The year-end close over HTTP (IFRS 17 I6, guide 5.7): finance previews and prepares the close of a calendar year once
 * December is closing; a FINANCE_APPROVER who did not prepare it approves -- posting it in December -- or rejects it.
 */
@RestController
public class YearEndCloseController {

    private static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";
    private static final String APPROVER = "hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')";

    public record RejectionRequest(String reason) {}

    private final YearEndCloses closes;

    public YearEndCloseController(YearEndCloses closes) {
        this.closes = closes;
    }

    @GetMapping("/ifrs17/year-end/{year}/preview")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public YearEndCloseView preview(@PathVariable int year) {
        return closes.preview(year);
    }

    @PostMapping("/ifrs17/year-end/{year}/closes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public YearEndCloseView prepare(@PathVariable int year, @AuthenticationPrincipal Jwt jwt) {
        return closes.prepare(year, jwt.getSubject());
    }

    @GetMapping("/ifrs17/year-end/{year}/closes")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public List<YearEndCloseView> list(@PathVariable int year) {
        return closes.list(year);
    }

    @GetMapping("/ifrs17/year-end-closes/{id}")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public YearEndCloseView get(@PathVariable UUID id) {
        return closes.get(id);
    }

    @PostMapping("/ifrs17/year-end-closes/{id}/approval")
    @PreAuthorize(APPROVER)
    public YearEndCloseView approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return closes.approve(id, jwt.getSubject());
    }

    @PostMapping("/ifrs17/year-end-closes/{id}/rejection")
    @PreAuthorize(APPROVER)
    public YearEndCloseView reject(@PathVariable UUID id, @RequestBody(required = false) RejectionRequest body,
                                   @AuthenticationPrincipal Jwt jwt) {
        return closes.reject(id, body == null ? null : body.reason(), jwt.getSubject());
    }
}
