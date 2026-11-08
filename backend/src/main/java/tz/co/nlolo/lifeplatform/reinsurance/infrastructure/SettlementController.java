package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

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
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.SettlementView;
import tz.co.nlolo.lifeplatform.reinsurance.application.StatementSettlements;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Settling an approved reinsurance statement (2026-11-08): what it still has open, the movements against it, and the
 * second person's decision on each. Amounts are decimal strings on the wire, never JSON numbers.
 */
@RestController
public class SettlementController {

    public record Money(String amount, String currencyCode) {
        static Money of(BigDecimal amount, String currency) {
            return new Money(amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), currency);
        }
    }

    public record NewSettlementRequest(String kind, String amount, String method, String reference, LocalDate receivedOn,
                                       String payeeBank, String payeeBranch, String payeeAccountName,
                                       String payeeAccountNumber, String note) {}

    public record RefusalRequest(String reason) {}

    public record SettlementDto(UUID settlementId, UUID statementId, UUID treatyId, String reinsurerName, String quarter,
                                String kind, Money amount, String method, String reference, LocalDate receivedOn,
                                String payeeBank, String payeeBranch, String payeeAccountName, String payeeAccountNumber,
                                String note, String status, String recordedBy, Instant recordedAt, String decidedBy,
                                Instant decidedAt, String decisionReason, boolean actedAlone) {
        static SettlementDto from(SettlementView v) {
            return new SettlementDto(v.settlementId(), v.statementId(), v.treatyId(), v.reinsurerName(), v.quarter(),
                v.kind(), Money.of(v.amount(), v.currency()), v.method(), v.reference(), v.receivedOn(), v.payeeBank(),
                v.payeeBranch(), v.payeeAccountName(), v.payeeAccountNumber(), v.note(), v.status(), v.recordedBy(),
                v.recordedAt(), v.decidedBy(), v.decidedAt(), v.decisionReason(), v.actedAlone());
        }
    }

    public record OpenDto(String statementStatus, Money theyOweUs, Money weOweThem, Money profitCommissionDue,
                          Money withheldHeld, List<SettlementDto> settlements) {
        static OpenDto from(SettlementView.Open o) {
            return new OpenDto(o.statementStatus(), Money.of(o.theyOweUs(), o.currency()),
                Money.of(o.weOweThem(), o.currency()), Money.of(o.profitCommissionDue(), o.currency()),
                Money.of(o.withheldHeld(), o.currency()), o.settlements().stream().map(SettlementDto::from).toList());
        }
    }

    private final StatementSettlements settlements;

    public SettlementController(StatementSettlements settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/reinsurance-statements/{id}/settlements")
    @PreAuthorize("@access.can('reinsurance.view')")
    public OpenDto open(@PathVariable UUID id) {
        return OpenDto.from(settlements.open(id));
    }

    @PostMapping("/reinsurance-statements/{id}/settlements")
    @PreAuthorize("@access.can('reinsurance.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementDto record(@PathVariable UUID id, @RequestBody NewSettlementRequest body, @AuthenticationPrincipal Jwt jwt) {
        BigDecimal amount;
        try {
            amount = body.amount() == null ? null : new BigDecimal(body.amount().trim());
        } catch (NumberFormatException e) {
            throw new ReinsuranceValidationException("The amount is a number, to at most two decimal places");
        }
        return SettlementDto.from(settlements.record(id, new SettlementView.NewSettlement(body.kind(), amount,
            body.method(), body.reference(), body.receivedOn(), body.payeeBank(), body.payeeBranch(),
            body.payeeAccountName(), body.payeeAccountNumber(), body.note()), jwt.getSubject()));
    }

    @PostMapping("/reinsurance-settlements/{settlementId}/approval")
    @PreAuthorize("@access.can('reinsurance.approve')")
    public SettlementDto approve(@PathVariable UUID settlementId, @AuthenticationPrincipal Jwt jwt) {
        return SettlementDto.from(settlements.approve(settlementId, jwt.getSubject()));
    }

    @PostMapping("/reinsurance-settlements/{settlementId}/refusal")
    @PreAuthorize("@access.can('reinsurance.approve')")
    public SettlementDto refuse(@PathVariable UUID settlementId, @RequestBody(required = false) RefusalRequest body,
                                @AuthenticationPrincipal Jwt jwt) {
        return SettlementDto.from(settlements.refuse(settlementId, body == null ? null : body.reason(), jwt.getSubject()));
    }
}
