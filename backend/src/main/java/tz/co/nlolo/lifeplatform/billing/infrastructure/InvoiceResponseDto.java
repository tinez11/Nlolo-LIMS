package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;

import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponseDto(UUID invoiceId, String policyNumber, LocalDate dueDate, MoneyDto amount,
                                  InvoiceStatus status, LocalDate gracePeriodEndsAt, Integer dunningLevel) {

    public static InvoiceResponseDto from(InvoiceView view) {
        return new InvoiceResponseDto(view.invoiceId(), view.policyNumber(), view.dueDate(),
            new MoneyDto(view.amount().toPlainString(), view.currency()), view.status(),
            view.gracePeriodEndsAt(), view.dunningLevel());
    }
}
