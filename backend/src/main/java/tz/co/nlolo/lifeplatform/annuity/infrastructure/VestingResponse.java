package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import tz.co.nlolo.lifeplatform.annuity.api.VestingView;

import java.util.Map;

/**
 * A deferred annuity's vesting on the wire (product step 5 D2). Percentages are decimal strings
 * without trailing zeros, money is the common Money shape -- as the contract response does.
 */
public record VestingResponse(String policyNumber, String targetDate, String earliestVestingDate, String latestVestingDate,
                              String vestingDate, String formCode, String frequency, String jointLifePartyId,
                              String lumpSumPercent, String maxCommutationPercent, boolean instructed, String contributions,
                              String holdReason, String heldAt, String vestedOn, Map<String, String> vestedBalance,
                              Map<String, String> lumpSum, String ageConfirmedBy, String confirmedDateOfBirth,
                              String confirmedSex) {

    static VestingResponse from(VestingView v) {
        return new VestingResponse(v.policyNumber(), str(v.targetDate()), str(v.earliestVestingDate()),
            str(v.latestVestingDate()), str(v.vestingDate()), v.formCode(), v.frequency(), str(v.jointLifePartyId()),
            AnnuityController.plain(v.lumpSumPercent()), AnnuityController.plain(v.maxCommutationPercent()), v.instructed(),
            v.contributions(), v.holdReason(), str(v.heldAt()), str(v.vestedOn()),
            AnnuityController.money(v.vestedBalance(), v.currency()), AnnuityController.money(v.lumpSum(), v.currency()),
            v.ageConfirmedBy(), str(v.confirmedDateOfBirth()), v.confirmedSex());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
