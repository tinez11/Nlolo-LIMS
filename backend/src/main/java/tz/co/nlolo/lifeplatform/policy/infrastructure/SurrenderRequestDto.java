package tz.co.nlolo.lifeplatform.policy.infrastructure;


/** Body of a surrender request: where the payout goes. */
/**
 * How the surrender is paid (2026-10-28): method blank or MOBILE_MONEY = payeeRef is the number; EFT = the four bank
 * fields; CHEQUE = payeeRef is who it is made out to.
 */
public record SurrenderRequestDto(@jakarta.validation.constraints.Size(max = 200) String payeeRef,
                                  @jakarta.validation.constraints.Size(max = 20) String method,
                                  @jakarta.validation.constraints.Size(max = 100) String payeeBank,
                                  @jakarta.validation.constraints.Size(max = 100) String payeeBranch,
                                  @jakarta.validation.constraints.Size(max = 200) String payeeAccountName,
                                  @jakarta.validation.constraints.Size(max = 50) String payeeAccountNumber) {

    /** The route, or IllegalArgumentException naming what is missing -- a 400 in the person's words. */
    public tz.co.nlolo.lifeplatform.PayoutRoute route() {
        return tz.co.nlolo.lifeplatform.PayoutRoute.of(method, payeeRef, payeeBank, payeeBranch, payeeAccountName,
            payeeAccountNumber);
    }
}
