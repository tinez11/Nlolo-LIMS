package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;

/** One band on a GRADED scheme's benefit table: a staff category and what it is worth. */
public record GroupSchemeGradeView(String gradeCode, BigDecimal benefitAmount) {}
