package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.party.api.Address;
import tz.co.nlolo.lifeplatform.party.api.IdType;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.party.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalAmendmentApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The bodies of the return-for-amendment endpoints (2026-10-20). */
public final class AmendmentRequests {

    private AmendmentRequests() {}

    /** One reason: its category, what is wrong, what to change. */
    public record Reason(@NotNull ProposalAmendmentApi.ReturnCategory category, @NotBlank @Size(max = 2000) String problem,
                  @NotBlank @Size(max = 2000) String change) {
        ProposalAmendmentApi.ReturnReason toApi() {
            return new ProposalAmendmentApi.ReturnReason(category, problem, change);
        }
    }

    public record ReturnRequest(@NotEmpty List<@NotNull @Valid Reason> reasons, @Size(max = 2000) String note) {}

    /** The contract as amended. Every field the new value; null means none, as at opening. */
    public record TermsRequest(@NotNull @Positive BigDecimal sumAssured, Integer requestedTermMonths,
                        Integer premiumPayingTermMonths, String premiumFrequency, LocalDate proposedCommencementDate,
                        BigDecimal proposedRatePercent) {
        ProposalAmendmentApi.TermsAmendment toApi() {
            return new ProposalAmendmentApi.TermsAmendment(sumAssured, requestedTermMonths, premiumPayingTermMonths,
                premiumFrequency, proposedCommencementDate, proposedRatePercent);
        }
    }

    public record ResubmitRequest(@Size(max = 2000) String note) {}

    /**
     * The client's corrected record, in the same shape as registering one ({@code POST /parties/individuals}), so the
     * agent's correction form is the registration form. A full replacement, as the staff correction is.
     */
    public record ApplicantRequest(@NotBlank String fullName, @NotNull LocalDate dateOfBirth, @Valid @NotNull Contact contactInfo,
                            Sex sex, SmokerStatus smokerStatus, IdType idType, @Size(max = 50) String idNumber,
                            @Size(max = 120) String occupation, @Size(max = 30) String occupationClass,
                            @Size(max = 255) String employerName,
                            @Pattern(regexp = "^[A-Za-z]{2}$", message = "Nationality must be an ISO 3166-1 alpha-2 code, e.g. TZ")
                            String nationality,
                            @Valid AddressBody address, @Size(max = 50) String clientReference) {

        public record Contact(@Size(max = 30) String phoneNumber, @Size(max = 255) String email) {}

        public record AddressBody(@Size(max = 255) String line, @Size(max = 100) String ward, @Size(max = 100) String district,
                           @Size(max = 100) String region, @Size(max = 20) String postalCode) {}

        IndividualRegistration toRegistration() {
            return new IndividualRegistration(fullName, dateOfBirth, contactInfo.phoneNumber(), contactInfo.email(), sex,
                smokerStatus, new IdentityDocument(idType, idNumber), occupation, occupationClass, employerName,
                nationality,
                address == null ? Address.none()
                    : new Address(address.line(), address.ward(), address.district(), address.region(), address.postalCode()),
                clientReference);
        }
    }
}
