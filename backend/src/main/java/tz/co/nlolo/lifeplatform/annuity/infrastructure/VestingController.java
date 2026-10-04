package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.annuity.api.VestingInstructionInput;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A pension's vesting over HTTP (product step 5 D2): its Annuity tab while it saves, the instruction
 * that says when and into what it vests, the re-confirmation of age a changed record needs, and the
 * queue of held vestings. Staff only, as D1's contract read is.
 */
@RestController
public class VestingController {

    private final AnnuityApi api;

    public VestingController(AnnuityApi api) {
        this.api = api;
    }

    /** Unannotated: every refusal is the service's own, in the console's words (VestingRules). */
    public record InstructionRequest(LocalDate vestingDate, String formCode, String frequency, UUID jointLifePartyId,
                                     BigDecimal lumpSumPercent, String contributions) {
        VestingInstructionInput toInput() {
            return new VestingInstructionInput(vestingDate, formCode, frequency, jointLifePartyId, lumpSumPercent, contributions);
        }
    }

    /** 404 NOT_A_DEFERRED_ANNUITY for every other policy -- the console reads it as "no vesting panel". */
    @GetMapping("/policies/{policyNumber}/annuity/vesting")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public VestingResponse vesting(@PathVariable String policyNumber) {
        return VestingResponse.from(api.vesting(policyNumber).orElseThrow(() -> new NotADeferredAnnuityException(policyNumber)));
    }

    @PutMapping("/policies/{policyNumber}/annuity/vesting/instruction")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public VestingResponse recordInstruction(@PathVariable String policyNumber, @RequestBody InstructionRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return VestingResponse.from(api.recordVestingInstruction(policyNumber, request.toInput(), jwt.getSubject()));
    }

    @PostMapping("/policies/{policyNumber}/annuity/vesting/reconfirm-age")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public VestingResponse reconfirmAge(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
        return VestingResponse.from(api.reconfirmAge(policyNumber, jwt.getSubject()));
    }

    @GetMapping("/annuity-vestings/held")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<VestingResponse> held() {
        return api.listHeldVestings().stream().map(VestingResponse::from).toList();
    }
}
