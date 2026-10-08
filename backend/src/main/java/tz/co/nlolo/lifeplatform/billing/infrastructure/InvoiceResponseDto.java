package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;

import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponseDto(UUID invoiceId, String policyNumber, LocalDate dueDate, MoneyDto amount,
                                  InvoiceStatus status, LocalDate gracePeriodEndsAt, Integer dunningLevel,
                                  MoneyDto amountPaid, MoneyDto amountCredited, MoneyDto balanceDue,
                                  UUID enrolmentSubmissionId, LocalDate coversFrom, LocalDate coversTo) {

    public static InvoiceResponseDto from(InvoiceView view) {
        return new InvoiceResponseDto(view.invoiceId(), view.policyNumber(), view.dueDate(),
            money(view.amount(), view.currency()), view.status(),
            view.gracePeriodEndsAt(), view.dunningLevel(),
            money(view.amountPaid(), view.currency()), money(view.amountCredited(), view.currency()),
            money(view.balanceDue(), view.currency()), view.enrolmentSubmissionId(), view.coversFrom(), view.coversTo());
    }

    private static MoneyDto money(java.math.BigDecimal amount, String currency) {
        return new MoneyDto(amount.toPlainString(), currency);
    }
}
