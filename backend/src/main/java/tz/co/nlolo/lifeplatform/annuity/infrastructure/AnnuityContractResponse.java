package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import tz.co.nlolo.lifeplatform.annuity.api.AnnuityContractView;

import java.util.Map;

/** An annuity contract on the wire (product step 5): the form, the lives, and the locked figures. */
public record AnnuityContractResponse(String policyNumber, String status, String formCode, Integer guaranteeYears,
                                      boolean joint, String survivorPercent, String escalationPercent,
                                      boolean capitalProtected, String timing, String frequency,
                                      String annuitantPartyId, String jointLifePartyId,
                                      Map<String, String> purchasePrice, String lockedOn, Integer annuitantAge,
                                      Integer jointAge, String rateSex, String annualRatePerMille, String factor,
                                      Map<String, String> annualIncome, Map<String, String> instalment,
                                      String firstDueDate, String guaranteeEndDate, String firstDeathDate,
                                      String lastDeathDate, Map<String, String> overpaymentOwed, String lockFailureReason) {

    static AnnuityContractResponse from(AnnuityContractView v) {
        String c = v.currency();
        return new AnnuityContractResponse(v.policyNumber(), v.status().name(), v.formCode(), v.guaranteeYears(), v.joint(),
            AnnuityController.plain(v.survivorPercent()), AnnuityController.plain(v.escalationPercent()), v.capitalProtected(),
            v.timing(), v.frequency(), v.annuitantPartyId().toString(),
            v.jointLifePartyId() != null ? v.jointLifePartyId().toString() : null,
            AnnuityController.money(v.purchasePrice(), c), str(v.lockedOn()), v.annuitantAge(), v.jointAge(), v.rateSex(),
            AnnuityController.plain(v.annualRatePerMille()), AnnuityController.plain(v.factor()),
            AnnuityController.money(v.annualIncome(), c), AnnuityController.money(v.instalment(), c),
            str(v.firstDueDate()), str(v.guaranteeEndDate()), str(v.firstDeathDate()), str(v.lastDeathDate()),
            AnnuityController.money(v.overpaymentOwed(), c), v.lockFailureReason());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
