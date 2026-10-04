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

    /** The identity document seen at claim (promotion) or at takeover, with a phone number and sex. */
    record Identify(tz.co.nlolo.lifeplatform.party.api.IdType idType, String idNumber, String phoneNumber,
                    tz.co.nlolo.lifeplatform.party.api.Sex sex) {
        tz.co.nlolo.lifeplatform.policy.api.PromoteMemberRequest toRequest() {
            return new tz.co.nlolo.lifeplatform.policy.api.PromoteMemberRequest(
                idType == null ? tz.co.nlolo.lifeplatform.party.api.IdentityDocument.none()
                    : new tz.co.nlolo.lifeplatform.party.api.IdentityDocument(idType, idNumber),
                phoneNumber, sex);
        }
    }
}
