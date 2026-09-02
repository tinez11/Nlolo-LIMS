package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.DisclosureAnswer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Wire shape for {@code POST /underwriting/cases/{caseId}/disclosures}.
 *
 * <p>{@code questionCode}, {@code question} and {@code answer} are all required. Recording that a
 * question was asked without recording what it said, or that an answer was given without saying
 * what it was, produces a row that looks like evidence and is not -- and this record exists
 * precisely to be read back years later by someone arguing about a claim.
 *
 * <p>{@code notes} is the one optional field: most answers need no elaboration.
 */
public record RecordDisclosuresRequest(@NotEmpty @Valid List<Answer> answers) {

    public record Answer(
        @NotBlank String questionCode,
        @NotBlank String question,
        @NotBlank String answer,
        String notes) {}

    public List<DisclosureAnswer> toApiAnswers() {
        return answers.stream()
            .map(a -> new DisclosureAnswer(a.questionCode(), a.question(), a.answer(), a.notes()))
            .toList();
    }
}
