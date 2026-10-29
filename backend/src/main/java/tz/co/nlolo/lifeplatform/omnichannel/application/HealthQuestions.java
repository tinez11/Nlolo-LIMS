package tz.co.nlolo.lifeplatform.omnichannel.application;

import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView.HealthQuestion;

import java.util.List;

/**
 * The short health questions a self-service application asks (2026-10-29, the user approved this set of five). The
 * platform keeps no questionnaire on purpose -- the wording belongs to the insurer and its reinsurer -- so this list is
 * the portal's, held in one place, and each answer is recorded with the question exactly as it was put, in the language
 * the customer read it in. A funeral plan asks the first two, of the main member; a deposit asks none.
 *
 * <p>The Swahili is a draft for a native speaker to confirm before go-live.
 */
final class HealthQuestions {

    static final List<HealthQuestion> ALL = List.of(
        new HealthQuestion("GOOD_HEALTH",
            "Are you in good health and able to do your normal work?",
            "Je, una afya njema na unaweza kufanya kazi zako za kawaida?"),
        new HealthQuestion("SERIOUS_ILLNESS_5Y",
            "In the last 5 years, have you been treated for heart disease, cancer, stroke, diabetes, kidney disease or"
                + " high blood pressure?",
            "Katika miaka 5 iliyopita, umewahi kutibiwa ugonjwa wa moyo, saratani, kiharusi, kisukari, ugonjwa wa figo"
                + " au shinikizo la damu?"),
        new HealthQuestion("HOSPITAL_12M",
            "In the last 12 months, have you stayed in hospital for more than 3 days, or been told you need an operation?",
            "Katika miezi 12 iliyopita, umelazwa hospitalini zaidi ya siku 3, au umeambiwa unahitaji upasuaji?"),
        new HealthQuestion("LONG_TERM_MEDICINE",
            "Are you taking medicine for a long-term illness?",
            "Je, unatumia dawa za ugonjwa wa muda mrefu?"),
        new HealthQuestion("TOBACCO_12M",
            "Have you used tobacco in the last 12 months?",
            "Je, umetumia tumbaku katika miezi 12 iliyopita?"));

    private HealthQuestions() {}

    /** What a kind of product asks. */
    static List<HealthQuestion> forKind(String kind) {
        return switch (kind) {
            case "PROTECTION", "CASH_VALUE" -> ALL;
            case "FUNERAL" -> ALL.subList(0, 2);
            default -> List.of();
        };
    }

    /**
     * The answer the healthy applicant gives. A YES on GOOD_HEALTH is the expected answer; on every other question it is
     * the one that needs a few words.
     */
    static boolean needsDetail(String code, boolean yes) {
        return "GOOD_HEALTH".equals(code) ? !yes : yes;
    }
}
