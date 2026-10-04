package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.time.LocalDate;

/** The bodies of the covered-life endpoints. Unannotated: the service refuses in the product's words. */
final class CoveredLifeRequests {
    private CoveredLifeRequests() {}

    record Add(FuneralRole role, String fullName, LocalDate dateOfBirth, String sex, String idNumber, Boolean student) {
        FuneralApplication.Life toLife() {
            return new FuneralApplication.Life(role, fullName, dateOfBirth, sex, idNumber, Boolean.TRUE.equals(student));
        }
    }

    record Remove(String reason) {}
}
