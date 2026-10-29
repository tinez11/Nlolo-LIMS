package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The customer portal's product shelf (2026-10-08, the customer portal design step 5; PRD §13-16): what is offered
 * online, an indicative price, and what the customer asked for.
 *
 * <p>2026-10-29 (the self-service rework): each product says what KIND it is -- read from what its version carries, not
 * from its category alone, so a fixed deposit filed as an endowment is no longer priced as cover -- and whether it can be
 * priced and asked for here ({@code online}). The user's decision: term life, cash-value endowment and whole life,
 * funeral and fixed deposit online; annuities, unit-linked, education and the savings accounts through an adviser.
 *
 * @param kind PROTECTION, CASH_VALUE, DEPOSIT, FUNERAL, SAVINGS_ACCOUNT, TERM_ACCOUNT, ANNUITY, UNIT_LINKED, EDUCATION
 *     or OTHER
 * @param quotable the same as {@code online}; kept for the screens written before it
 * @param terms what the pricing questions may offer: the term range, a deposit's terms, a funeral plan's plans and roles
 * @param questions the health questions an application asks, in both languages; empty where none are asked
 */
public record CustomerProductView(UUID productId, String productName, String category, String currency, String summary,
                                  List<String> benefits, boolean quotable, String kind, boolean online, Terms terms,
                                  List<HealthQuestion> questions) {

    public CustomerProductView {
        benefits = benefits != null ? List.copyOf(benefits) : List.of();
        questions = questions != null ? List.copyOf(questions) : List.of();
    }

    /**
     * @param minTermYears whole years the version allows; null where it sets no bound
     * @param minAmount the least cover, or a deposit's smallest band; null where none is set
     * @param depositTermMonths every term a deposit grid offers; empty for every other kind
     * @param funeralPlans a funeral version's plans with the main member's benefit; empty for every other kind
     * @param funeralRoles who a funeral plan may cover, how many and at what ages
     */
    public record Terms(Integer minTermYears, Integer maxTermYears, BigDecimal minAmount, BigDecimal maxAmount,
                        List<Integer> depositTermMonths, List<FuneralPlanOffer> funeralPlans,
                        List<FuneralRoleOffer> funeralRoles) {
        public Terms {
            depositTermMonths = depositTermMonths != null ? List.copyOf(depositTermMonths) : List.of();
            funeralPlans = funeralPlans != null ? List.copyOf(funeralPlans) : List.of();
            funeralRoles = funeralRoles != null ? List.copyOf(funeralRoles) : List.of();
        }
    }

    public record FuneralPlanOffer(String planCode, String name, BigDecimal mainMemberBenefit) {}

    public record FuneralRoleOffer(String role, int maxLives, int minEntryAge, int maxEntryAge) {}

    /** One question as asked, in English and Swahili -- the server owns the wording so the record says what was asked. */
    public record HealthQuestion(String code, String en, String sw) {}

    /**
     * What a customer asks to be priced. Cover and cash-value plans: {@code sumAssured}, {@code frequency} and
     * optionally {@code termYears}. A deposit: {@code amount} and {@code termMonths}. Funeral: {@code planCode},
     * {@code frequency} and the {@code family} beside the customer, who is always the main member.
     */
    public record QuoteRequest(BigDecimal sumAssured, String frequency, Integer termYears, BigDecimal amount,
                               Integer termMonths, String planCode, List<FamilyMember> family) {
        public QuoteRequest(BigDecimal sumAssured, String frequency, Integer termYears) {
            this(sumAssured, frequency, termYears, null, null, null, List.of());
        }

        public QuoteRequest {
            family = family != null ? List.copyOf(family) : List.of();
        }
    }

    /** A person a funeral plan covers beside the customer. {@code student} matters only for a child. */
    public record FamilyMember(String role, String name, LocalDate dateOfBirth, boolean student) {}

    /**
     * An indicative price, on the customer's own age, sex and smoker status as we hold them -- not an offer: underwriting
     * may load or decline. Every figure is the server's; the screen draws them and adds nothing up.
     *
     * @param sumAssured what is paid on a death, a deposit's amount, or a funeral plan's main-member benefit
     * @param instalment what is paid each time, at {@code frequency}; a deposit is paid once
     * @param totalOverTerm all the premiums over the term; null where the term does not total (no term, or SINGLE)
     * @param paidByYear what has been paid by each policy year end, on the same terms
     * @param deposit a deposit's answer; null for every other kind
     * @param funeral the family priced person by person; empty for every other kind
     */
    public record Quote(UUID productId, String productName, String kind, String currency, BigDecimal sumAssured,
                        String frequency, BigDecimal instalment, BigDecimal yearly, Integer ageAtEntry, Integer termYears,
                        BigDecimal totalOverTerm, List<PaidByYear> paidByYear, DepositAnswer deposit,
                        List<FuneralLine> funeral) {
        public Quote {
            paidByYear = paidByYear != null ? List.copyOf(paidByYear) : List.of();
            funeral = funeral != null ? List.copyOf(funeral) : List.of();
        }
    }

    public record PaidByYear(int year, BigDecimal paid) {}

    /** {@code ratePercent} is for the whole term, not a year; {@code refusal} says why nothing priced. */
    public record DepositAnswer(BigDecimal amount, Integer termMonths, BigDecimal ratePercent, BigDecimal interest,
                                BigDecimal maturityValue, LocalDate maturityDate, List<Integer> offeredTermMonths,
                                String refusal) {
        public DepositAnswer {
            offeredTermMonths = offeredTermMonths != null ? List.copyOf(offeredTermMonths) : List.of();
        }
    }

    public record FuneralLine(String role, String name, int age, BigDecimal benefit, BigDecimal yearlyPremium) {}

    /**
     * Asking for a product: it opens an application (an underwriting case) with the customer as applicant and life
     * assured, sent straight to underwriting (the user, 2026-10-29). The pricing inputs are the quote's; the server
     * prices them again, so nothing typed in a browser sets a price.
     *
     * @param beneficiaries who receives the money, shares totalling 100; may be empty
     * @param answers one per health question the product asks, a YES with a few words
     * @param language EN or SW: the language the questions were asked in, recorded with the answers
     */
    public record ApplicationRequest(UUID productId, BigDecimal sumAssured, String frequency, Integer termYears,
                                     BigDecimal amount, Integer termMonths, String planCode, List<FamilyMember> family,
                                     List<Beneficiary> beneficiaries, List<HealthAnswer> answers, String language) {
        public ApplicationRequest(UUID productId, BigDecimal sumAssured, String frequency, Integer termYears) {
            this(productId, sumAssured, frequency, termYears, null, null, null, List.of(), List.of(), List.of(), null);
        }

        public ApplicationRequest {
            family = family != null ? List.copyOf(family) : List.of();
            beneficiaries = beneficiaries != null ? List.copyOf(beneficiaries) : List.of();
            answers = answers != null ? List.copyOf(answers) : List.of();
        }
    }

    public record Beneficiary(String name, String relationship, String phone, BigDecimal sharePercent) {}

    /** {@code answer} is YES or NO. */
    public record HealthAnswer(String code, String answer, String notes) {}

    /**
     * One application as the customer reads it.
     *
     * @param stage where it is, for the progress line: REVIEWING, NEEDS_YOU (sent back for something), ACCEPTED (the
     *     offer is being prepared), ISSUED, POSTPONED or DECLINED
     * @param decidedAt when underwriting decided it; null until then
     */
    public record Application(UUID caseId, String proposalNumber, UUID productId, String productName, BigDecimal sumAssured,
                              String currency, String status, String statusText, String stage, java.time.Instant decidedAt) {}
}
