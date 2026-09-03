package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.GroupSchemeGradeView;

/** One band on a GRADED scheme's benefit table, as read back. */
public record GroupSchemeGradeDto(String gradeCode, MoneyDto benefit) {

    public static GroupSchemeGradeDto from(GroupSchemeGradeView view, String currency) {
        return new GroupSchemeGradeDto(view.gradeCode(),
            new MoneyDto(view.benefitAmount().toPlainString(), currency));
    }
}
