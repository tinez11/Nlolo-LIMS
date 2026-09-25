package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.PremiumCreditView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PremiumCreditResponseDto(UUID creditId, String policyNumber, UUID policyMemberId,
                                       UUID originalInvoiceId, MoneyDto amount, String exitReason,
                                       LocalDate exitDate, Instant createdAt) {

    public static PremiumCreditResponseDto from(PremiumCreditView view) {
        return new PremiumCreditResponseDto(view.creditId(), view.policyNumber(), view.policyMemberId(),
            view.originalInvoiceId(), new MoneyDto(view.amount().toPlainString(), view.currency()),
            view.exitReason(), view.exitDate(), view.createdAt());
    }
}
