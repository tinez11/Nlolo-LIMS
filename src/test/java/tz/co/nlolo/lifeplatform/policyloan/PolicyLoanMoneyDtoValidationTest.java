package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.policyloan.infrastructure.MoneyDto;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.OriginateLoanRequestDto;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.RepaymentRequestDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 review, fix round 1, C1: a negative {@code MoneyDto.amount} was accepted by Bean
 * Validation and, traced end-to-end, let a customer inflate their own available loan value
 * (negative requestedAmount &lt; any positive available-value upper bound in
 * {@code PolicyApiImpl.reserveLoanValue}, then {@code PolicyAccount.increaseEncumbrance}'s
 * unguarded {@code .add()} drives the encumbrance negative). The fix is a single
 * {@code @DecimalMin(value = "0.01")} added to {@code policyloan.infrastructure.MoneyDto.amount}.
 *
 * <p>Deliberately a plain Bean Validation unit test against the real
 * {@code jakarta.validation.Validator} (Hibernate Validator, the same provider Spring MVC's
 * {@code @Valid @RequestBody} delegates to via {@code LocalValidatorFactoryBean}) rather than a
 * full {@code MockMvc}/Testcontainers contract test -- this is the cheapest test that genuinely
 * exercises the annotation (not a mock, not an assertion against the annotation's presence via
 * reflection), and Task 8 separately owns the full HTTP-level contract-test coverage for this
 * module including this same case.
 *
 * <p><b>Negative control performed for C1 (see task-7-report.md, Fix round 1):</b> with
 * {@code @DecimalMin(value = "0.01")} removed from {@code MoneyDto.amount}, this test class's
 * three amount-related methods FAIL (0 violations found where >=1 was asserted). With it
 * restored, all methods pass. This proves the test would catch the regression it's guarding
 * against, not just pass vacuously.
 */
class PolicyLoanMoneyDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void negativeAmountOnMoneyDtoIsRejected() {
        MoneyDto negative = new MoneyDto("-500000.00", "TZS");
        Set<ConstraintViolation<MoneyDto>> violations = validator.validate(negative);
        assertThat(violations).as("a negative amount must fail @DecimalMin(0.01)").isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
    }

    @Test
    void zeroAmountOnMoneyDtoIsRejected() {
        // Zero is not negative, but is not a meaningful loan/repayment amount either --
        // @DecimalMin(value = "0.01") excludes it too, not just negatives.
        MoneyDto zero = new MoneyDto("0.00", "TZS");
        Set<ConstraintViolation<MoneyDto>> violations = validator.validate(zero);
        assertThat(violations).as("a zero amount must fail @DecimalMin(0.01)").isNotEmpty();
    }

    @Test
    void positiveAmountOnMoneyDtoIsAccepted() {
        // Falsifiability check: prove the constraint doesn't just reject everything.
        MoneyDto positive = new MoneyDto("500000.00", "TZS");
        Set<ConstraintViolation<MoneyDto>> violations = validator.validate(positive);
        assertThat(violations).isEmpty();
    }

    @Test
    void negativeRequestedAmountFailsCascadedValidationOnOriginateLoanRequest() {
        // Exercises the actual wire-boundary shape: OriginateLoanRequestDto's @Valid on
        // requestedAmount must cascade into MoneyDto, exactly as @Valid @RequestBody does at
        // PolicyLoanController.originateLoan.
        OriginateLoanRequestDto request = new OriginateLoanRequestDto(new MoneyDto("-500000.00", "TZS"), "M-PESA-0712345678");
        Set<ConstraintViolation<OriginateLoanRequestDto>> violations = validator.validate(request);
        assertThat(violations).as("a negative requestedAmount must be rejected at the wire boundary").isNotEmpty();
    }

    @Test
    void negativeRepaymentAmountFailsCascadedValidationOnRepaymentRequest() {
        RepaymentRequestDto request = new RepaymentRequestDto(new MoneyDto("-50000.00", "TZS"), "REF-001");
        Set<ConstraintViolation<RepaymentRequestDto>> violations = validator.validate(request);
        assertThat(violations).as("a negative repayment amount must be rejected at the wire boundary").isNotEmpty();
    }
}
