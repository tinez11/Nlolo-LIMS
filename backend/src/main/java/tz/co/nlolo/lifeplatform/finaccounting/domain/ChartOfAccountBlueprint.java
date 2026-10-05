package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * THE chart of accounts: the guide's (IFRS 17 Chart of Accounts and Posting Guide, 2.5), exactly -- codes, names,
 * types, normal balances and posting modes -- read from {@code finaccounting/ifrs17-chart.csv}. Rows are in parent
 * order, so a caller may insert straight down the list.
 *
 * <p>An account the guide nests under a heading whose code does not share its prefix (5300-5600 under 5200, 8210-8490
 * under 8100) hangs off its class root instead: {@link ChartOfAccount#childOf} requires a child's code to begin with
 * its parent's significant prefix, and skipping a level is allowed (IFRS 17 I1, correction R3).
 *
 * <p>Every account and treatment here is the guide's; the guide itself says the chart must be reviewed by the finance
 * director, the appointed actuary and the external auditors before go-live.
 */
public final class ChartOfAccountBlueprint {

    private ChartOfAccountBlueprint() {}

    /** One account: {@code parentCode} null only for the nine class roots; {@code postingAllowed} false for a heading. */
    public record Seed(String code, String name, String parentCode, AccountType type, PostingDirection normalBalance,
                       PostingMode mode, boolean postingAllowed, String usedFor) {}

    /** The tenant functional currency every account is seeded with. */
    public static final String SEED_CURRENCY = "TZS";

    private static final List<Seed> ACCOUNTS = load();

    public static List<Seed> accounts() {
        return ACCOUNTS;
    }

    private static List<Seed> load() {
        try (var in = ChartOfAccountBlueprint.class.getResourceAsStream("/finaccounting/ifrs17-chart.csv")) {
            if (in == null) {
                throw new IllegalStateException("finaccounting/ifrs17-chart.csv is missing from the classpath");
            }
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            List<Seed> seeds = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(";", 8);   // at most 8: some "used for" notes contain semicolons
                seeds.add(new Seed(c[0], c[1], c[2].isEmpty() ? null : c[2], AccountType.valueOf(c[3]),
                    PostingDirection.valueOf(c[4]), PostingMode.valueOf(c[5]), "Y".equals(c[6]),
                    c[7].isEmpty() ? null : c[7]));
            }
            return List.copyOf(seeds);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
