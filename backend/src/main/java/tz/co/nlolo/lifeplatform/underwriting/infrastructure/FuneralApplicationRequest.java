package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.time.LocalDate;
import java.util.List;

/** A funeral case's plan and dependants. Unannotated: the service refuses in the quote's words. */
public record FuneralApplicationRequest(String planCode, @Valid List<Dependant> dependants) {

    public record Dependant(FuneralRole role, String fullName, LocalDate dateOfBirth, String sex, String idNumber,
                            Boolean student) {}

    List<FuneralApplication.Life> lives() {
        return dependants == null ? List.of() : dependants.stream()
            .map(d -> new FuneralApplication.Life(d.role(), d.fullName(), d.dateOfBirth(), d.sex(), d.idNumber(),
                Boolean.TRUE.equals(d.student()))).toList();
    }
}
