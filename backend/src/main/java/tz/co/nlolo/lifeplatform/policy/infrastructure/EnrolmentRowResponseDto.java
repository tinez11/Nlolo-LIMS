package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRowView;
import tz.co.nlolo.lifeplatform.policy.api.RowOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EnrolmentRowResponseDto(int lineNumber, String loanAccountNumber,
                                       String borrowerFullName, RowOutcome outcome,
                                       EnrolmentRejection reasonCode, String reason,
                                       UUID policyMemberId, String memberReference,
                                       MoneyDto premiumAmount, MoneyDto loanAmount,
                                       LocalDate loanStartDate, Integer loanTermMonths) {

    public static EnrolmentRowResponseDto from(EnrolmentRowView view) {
        return new EnrolmentRowResponseDto(view.lineNumber(), view.loanAccountNumber(),
            view.borrowerFullName(), view.outcome(), view.reasonCode(), view.reason(),
            view.policyMemberId(), view.memberReference(),
            money(view.premiumAmount(), view.currency()), money(view.loanAmount(), view.currency()),
            view.loanStartDate(), view.loanTermMonths());
    }

    public static List<EnrolmentRowResponseDto> from(List<EnrolmentRowView> views) {
        return views.stream().map(EnrolmentRowResponseDto::from).toList();
    }

    private static MoneyDto money(BigDecimal amount, String currency) {
        return amount != null ? new MoneyDto(amount.toPlainString(), currency) : null;
    }
}
