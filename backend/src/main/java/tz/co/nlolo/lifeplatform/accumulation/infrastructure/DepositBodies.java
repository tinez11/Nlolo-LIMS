package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.accumulation.api.MaturityAction;

/** Request bodies for a fixed-term deposit's maturity: the client's choice, and finance's payee. */
final class DepositBodies {
    private DepositBodies() {}

    /** A pay-out's route (2026-10-28): blank payeeRef and no bank fields = the number the deposit came from. */
    record Instruction(@NotNull MaturityAction action, Integer termMonths, @Size(max = 200) String payeeRef,
                       @Size(max = 20) String method, @Size(max = 100) String payeeBank, @Size(max = 100) String payeeBranch,
                       @Size(max = 200) String payeeAccountName, @Size(max = 50) String payeeAccountNumber) {

        tz.co.nlolo.lifeplatform.PayoutRoute route() {
            boolean none = (payeeRef == null || payeeRef.isBlank()) && (method == null || method.isBlank()
                || "MOBILE_MONEY".equals(method));
            return none ? null : Payout.route(method, payeeRef, payeeBank, payeeBranch, payeeAccountName, payeeAccountNumber);
        }
    }

    /** Finance names the payee and how it is paid: mobile money, a bank transfer or a cheque (2026-10-28). */
    record Payout(@Size(max = 200) String payeeRef, @Size(max = 20) String method, @Size(max = 100) String payeeBank,
                  @Size(max = 100) String payeeBranch, @Size(max = 200) String payeeAccountName,
                  @Size(max = 50) String payeeAccountNumber) {

        tz.co.nlolo.lifeplatform.PayoutRoute route() {
            return route(method, payeeRef, payeeBank, payeeBranch, payeeAccountName, payeeAccountNumber);
        }

        /** A missing field is a 422 in the person's words. */
        static tz.co.nlolo.lifeplatform.PayoutRoute route(String method, String payeeRef, String bank, String branch, String accountName,
                          String accountNumber) {
            try {
                return tz.co.nlolo.lifeplatform.PayoutRoute.of(method, payeeRef, bank, branch, accountName, accountNumber);
            } catch (IllegalArgumentException e) {
                throw new tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException(e.getMessage());
            }
        }
    }
}
