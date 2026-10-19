package tz.co.nlolo.lifeplatform.underwriting.api;

import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What to put on a quotation a customer can take away, asked for BEFORE any case exists (2026-10-16).
 *
 * <p><b>Inputs only -- never figures.</b> Everything priced on the document is quoted again on the server from
 * these inputs: the premium from the version's rate table, a family from the plan's grid, a deposit from its band.
 * A browser that sent its own totals could print a premium nobody would ever charge, which is the one thing a
 * document signed by an applicant must not do.
 *
 * <p>The three product families need different things, so the fields they do not use are null: a funeral plan has
 * no sum assured of its own (the plan's main-member benefit IS the sum assured) and a deposit has no frequency.
 *
 * @param applicantPartyId whose name goes on it as the proposer. Read from the party record, not sent.
 * @param sumAssured the sum assured, or a deposit's amount. Null on a funeral plan.
 * @param premiumPayingTermMonths null means the whole term, which is what the form's blank means
 * @param funeralPlanCode set only for a funeral plan, with {@code funeralLives}
 */
public record QuotationDocumentInput(UUID productId, UUID productVersionId, UUID applicantPartyId,
                                     BigDecimal sumAssured, String currency, String frequency,
                                     Integer policyTermMonths, Integer premiumPayingTermMonths,
                                     LocalDate commencement, String funeralPlanCode,
                                     List<FuneralLifeInput> funeralLives) {

    public QuotationDocumentInput {
        funeralLives = funeralLives != null ? List.copyOf(funeralLives) : List.of();
    }
}
