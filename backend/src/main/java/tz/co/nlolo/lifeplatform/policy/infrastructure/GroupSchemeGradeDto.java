package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.policy.api.GroupSchemeGradeView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;

/** One band on a GRADED scheme's benefit table, in both directions. */
public record GroupSchemeGradeDto(
    @NotBlank @Size(max = 30) String gradeCode,
    @NotNull @DecimalMin("0.01") BigDecimal benefitAmount) {

    public PolicyApi.GradeInput toApiInput() {
        return new PolicyApi.GradeInput(gradeCode, benefitAmount);
    }

    public static GroupSchemeGradeDto from(GroupSchemeGradeView view) {
        return new GroupSchemeGradeDto(view.gradeCode(), view.benefitAmount());
    }
}
