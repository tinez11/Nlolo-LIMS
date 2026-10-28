package tz.co.nlolo.lifeplatform;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * How a stored payout reaches the person, as the five columns every module that holds a payee keeps beside its own
 * {@code payee_ref} (2026-10-28): {@code payout_method}, {@code payee_bank}, {@code payee_branch},
 * {@code payee_account_name}, {@code payee_account_number}. The payee itself stays in the owner's {@code payee_ref}, so
 * every list that already prints a payee still prints someone meaningful.
 *
 * <p>Embedded rather than copied into each entity: a surrender, a unit-linked exit and a deposit's maturity all store
 * the same route, and one mapping is one place to get it right.
 */
@Embeddable
public class PayoutRouteColumns {

    @Column(name = "payout_method", nullable = false)
    private String method = PayoutRoute.MOBILE_MONEY;
    @Column(name = "payee_bank") private String bank;
    @Column(name = "payee_branch") private String branch;
    @Column(name = "payee_account_name") private String accountName;
    @Column(name = "payee_account_number") private String accountNumber;

    protected PayoutRouteColumns() {}

    public static PayoutRouteColumns of(PayoutRoute route) {
        PayoutRouteColumns c = new PayoutRouteColumns();
        if (route != null) {
            c.method = route.method();
            c.bank = route.bank();
            c.branch = route.branch();
            c.accountName = route.accountName();
            c.accountNumber = route.accountNumber();
        }
        return c;
    }

    /** Mobile money, as every row stored before these columns existed. */
    public static PayoutRouteColumns mobileMoney() {
        return new PayoutRouteColumns();
    }

    /** The route, with the payee its owner keeps in {@code payee_ref}. */
    public PayoutRoute toRoute(String payee) {
        return new PayoutRoute(method == null ? PayoutRoute.MOBILE_MONEY : method, payee, bank, branch, accountName,
            accountNumber);
    }
}
