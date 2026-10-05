package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedValuation;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;

/**
 * The unit-linked reconciliation (product step 6, spec §8, plan R10): every fund's units in issue at its latest
 * price, against what the ledger holds in 2150. The difference should always be zero -- a staff report that says so,
 * or says by how much it is not.
 */
@RestController
public class UnitLinkedReconciliationController {

    private final FinaccountingApi finaccountingApi;
    private final ObjectProvider<UnitLinkedValuation> valuation;

    public UnitLinkedReconciliationController(FinaccountingApi finaccountingApi, ObjectProvider<UnitLinkedValuation> valuation) {
        this.finaccountingApi = finaccountingApi;
        this.valuation = valuation;
    }

    public record ReconciliationResponse(List<UnitLinkedValuation.FundValuation> funds, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalValue,
                                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ledgerBalance2150, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference) {}

    @GetMapping("/finance/unit-linked-reconciliation")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ReconciliationResponse reconcile() {
        UnitLinkedValuation source = valuation.getIfAvailable();
        List<UnitLinkedValuation.FundValuation> funds = source == null ? List.of() : source.valuations();
        BigDecimal total = funds.stream().map(UnitLinkedValuation.FundValuation::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledger = finaccountingApi.trialBalance(null).accounts().stream()
            .filter(a -> "2150".equals(a.accountCode())).map(AccountBalanceView::balance).findFirst().orElse(BigDecimal.ZERO);
        return new ReconciliationResponse(funds, total, ledger, ledger.subtract(total));
    }
}
