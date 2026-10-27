package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.MonthEndView;
import tz.co.nlolo.lifeplatform.finaccounting.application.MonthEnd;

/**
 * A month's close as the finance guide's eight steps (2026-10-27): where it stands and what is next. Read by whoever
 * reads the ledger or the IFRS 17 cycle; each step is acted on from its own page.
 */
@RestController
public class MonthEndController {

    private final MonthEnd monthEnd;

    public MonthEndController(MonthEnd monthEnd) {
        this.monthEnd = monthEnd;
    }

    @GetMapping("/finance/periods/{period}/month-end")
    @PreAuthorize("@access.canAny('ledger.view', 'ifrs17.view')")
    public MonthEndView monthEnd(@PathVariable String period) {
        return monthEnd.of(period);
    }
}
