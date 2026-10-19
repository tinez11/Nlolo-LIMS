package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A group funeral scheme priced before it is proposed (2026-10-19): the association's bill, and each family checked
 * against the plan's role rules as it is entered.
 *
 * <p><b>The bill is the scheme's, not a sum of family premiums.</b> A scheme pays the plan's group rate per member
 * (per family), whatever the family holds; issuance bills exactly that. So the bill is {@code rate x members},
 * computed here once, rather than by a browser multiplying two numbers it was shown.
 *
 * <p><b>A family the plan will not take is named, not dropped.</b> Its problems come from the same role rules the
 * proposal and issuance apply, so the screen can say "this plan does not cover extended family" while the member
 * is being typed, instead of when the whole scheme is submitted.
 *
 * @param rate the plan's group rate per member, charged per {@code period}
 * @param members the families on the scheme -- one member each
 * @param bill {@code rate x members}, per {@code period}
 * @param totalCover every family's cover added up; null while any family has a problem, since a scheme with one
 *     inadmissible family is refused whole and has no cover to state
 * @param problems every family's problems, each prefixed with its member number -- empty when the scheme may be proposed
 */
public record GroupFuneralQuote(String planCode, BigDecimal rate, GroupRatePeriod period, int members, BigDecimal bill,
                                BigDecimal totalCover, List<Family> families, List<String> problems) {

    public GroupFuneralQuote {
        families = families != null ? List.copyOf(families) : List.of();
        problems = problems != null ? List.copyOf(problems) : List.of();
    }

    /**
     * One member's family.
     *
     * @param cover each life's plan benefit added up -- what the scheme pays across this family's deaths; null when
     *     the family has a problem, since a life the plan does not cover has no benefit to add
     */
    public record Family(String reference, String mainMember, int lives, BigDecimal cover, List<String> problems) {
        public Family {
            problems = problems != null ? List.copyOf(problems) : List.of();
        }
    }

    /** What to price: the plan, the day ages are taken, and the families as the form or the file holds them. */
    public record Input(String planCode, LocalDate asOf, List<FamilyInput> families) {
        public Input {
            families = families != null ? List.copyOf(families) : List.of();
        }
    }

    /** One family, its main member among its lives. */
    public record FamilyInput(String reference, List<FuneralLifeInput> lives) {
        public FamilyInput {
            lives = lives != null ? List.copyOf(lives) : List.of();
        }
    }
}
