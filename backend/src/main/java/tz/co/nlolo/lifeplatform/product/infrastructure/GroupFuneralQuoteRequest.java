package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.GroupFuneralQuote;

import java.time.LocalDate;
import java.util.List;

/**
 * A group funeral scheme to price, as the propose-scheme form holds it: the plan, the commencement, and each member's
 * family. The same lives the proposal sends -- one row per life, grouped by member number -- so what is quoted is
 * what will be proposed.
 */
public record GroupFuneralQuoteRequest(String planCode, LocalDate asOf, @Valid List<Family> families) {

    public record Family(String memberReference, @Valid List<Life> lives) {}

    public record Life(FuneralRole role, String name, LocalDate dateOfBirth, Boolean student) {}

    GroupFuneralQuote.Input toInput(LocalDate today) {
        return new GroupFuneralQuote.Input(planCode, asOf != null ? asOf : today,
            families == null ? List.of() : families.stream()
                .map(f -> new GroupFuneralQuote.FamilyInput(f.memberReference(), f.lives() == null ? List.of()
                    : f.lives().stream()
                        .map(l -> new FuneralLifeInput(l.role(), l.name(), l.dateOfBirth(), Boolean.TRUE.equals(l.student())))
                        .toList()))
                .toList());
    }
}
