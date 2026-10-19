package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.QuotationDocumentInput;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What to quote on a document, as the open-case form holds it.
 *
 * <p>Inputs only. No premium, no total and no benefit crosses this boundary: every figure on the document is quoted
 * again on the server, because an applicant signs it.
 */
public record QuotationDocumentRequest(@NotNull UUID productId, @NotNull UUID productVersionId,
                                       @NotNull UUID applicantPartyId,
                                       @Positive BigDecimal sumAssured, String currency, String frequency,
                                       Integer policyTermMonths, Integer premiumPayingTermMonths,
                                       LocalDate commencement, String funeralPlanCode, List<Life> lives) {

    /** One life on a family quotation. A blank date of birth is passed through so the quoter names the problem. */
    public record Life(@NotNull FuneralRole role, String name, LocalDate dateOfBirth, boolean student) {}

    QuotationDocumentInput toInput() {
        return new QuotationDocumentInput(productId, productVersionId, applicantPartyId, sumAssured, currency,
            frequency, policyTermMonths, premiumPayingTermMonths, commencement, funeralPlanCode,
            lives == null ? List.of() : lives.stream()
                .map(l -> new FuneralLifeInput(l.role(), l.name(), l.dateOfBirth(), l.student()))
                .toList());
    }
}
