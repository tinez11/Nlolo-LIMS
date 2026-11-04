package tz.co.nlolo.lifeplatform.dashboard.infrastructure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.dashboard.api.BooksMatchView;
import tz.co.nlolo.lifeplatform.dashboard.application.BooksMatch;

/** Do the books match? (money movement step 7) -- see {@link BooksMatch}. Read by finance and by the seeding script. */
@RestController
public class BooksMatchController {

    private final BooksMatch booksMatch;

    public BooksMatchController(BooksMatch booksMatch) {
        this.booksMatch = booksMatch;
    }

    /** The same expression as every finance endpoint and the finance dashboard. */
    @GetMapping("/finance/books-match")
    @PreAuthorize("@access.can('ledger.view')")
    public BooksMatchView check() {
        return booksMatch.check();
    }
}
