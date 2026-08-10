package tz.co.nlolo.lifeplatform.policy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccount;
import tz.co.nlolo.lifeplatform.policy.infrastructure.ManualIssueRequestDto;
import tz.co.nlolo.lifeplatform.policy.infrastructure.MoneyDto;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3 final whole-branch review, Important I3: Task 7's negative-amount Critical was closed at
 * the {@code policyloan} wire boundary only. Two of the three layers it left open live here --
 * {@code policy}'s own {@code MoneyDto} (which still carried the regex-only hole verbatim, via
 * {@code ManualIssueRequestDto.sumAssured}) and {@code PolicyAccount.increaseEncumbrance}
 * (a bare {@code .add()}, which is the exact mechanism of the original exploit: a negative
 * amount REDUCES {@code loan_encumbrance_amount} and thereby RAISES the policyholder's own
 * available loan value). The third layer, {@code PolicyApiImpl.reserveLoanValue}'s sign guard,
 * is covered against a real database by
 * {@code ModuleArchitectureB1ConcurrencyTest.reserveLoanValueRejectsANonPositiveAmount}.
 *
 * <p>Mirrors {@code policyloan.PolicyLoanMoneyDtoValidationTest}'s approach: a plain Bean
 * Validation unit test against the real {@code jakarta.validation.Validator} (Hibernate
 * Validator, the same provider Spring MVC's {@code @Valid @RequestBody} delegates to), plus
 * direct domain-object assertions. Property paths are pinned rather than asserting merely
 * "some violation occurred", so the test cannot pass because of an unrelated constraint.
 */
class PolicyNegativeAmountDefenceTest {

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
    void negativeAmountOnPolicyMoneyDtoIsRejected() {
        Set<ConstraintViolation<MoneyDto>> violations = validator.validate(new MoneyDto("-5000000.00", "TZS"));
        assertThat(violations).as("a negative amount must fail @DecimalMin(0.01)").isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
    }

    @Test
    void zeroAmountOnPolicyMoneyDtoIsRejected() {
        Set<ConstraintViolation<MoneyDto>> violations = validator.validate(new MoneyDto("0.00", "TZS"));
        assertThat(violations).as("a zero amount must fail @DecimalMin(0.01)").isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
    }

    @Test
    void positiveAmountOnPolicyMoneyDtoIsAccepted() {
        // The constraint must reject non-positive amounts without rejecting everything --
        // without this, the two tests above would also pass against @DecimalMin("999999999").
        assertThat(validator.validate(new MoneyDto("5000000.00", "TZS"))).isEmpty();
    }

    @Test
    void manualIssueRequestCascadesTheFloorIntoItsNestedSumAssured() {
        // The reachable exploit path: POST /policies/manual-issue used to accept
        // sumAssured.amount = "-5000000.00" and persist a negative death benefit that
        // claims/finaccounting consume in M4/M5. Asserts the cascade through @Valid, not just
        // the annotation on MoneyDto in isolation.
        ManualIssueRequestDto request = new ManualIssueRequestDto(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), new MoneyDto("-5000000.00", "TZS"), null, "MONTHLY", null, "negative sum assured");
        Set<ConstraintViolation<ManualIssueRequestDto>> violations = validator.validate(request);
        assertThat(violations)
            .as("the floor must cascade from MoneyDto into ManualIssueRequestDto.sumAssured")
            .anyMatch(v -> v.getPropertyPath().toString().equals("sumAssured.amount"));
    }

    @Test
    void increaseEncumbranceRejectsANegativeAmountRatherThanRaisingAvailableLoanValue() {
        PolicyAccount account = new PolicyAccount("POL-TEST-0001", UUID.randomUUID(), new BigDecimal("1000000.00"), "TZS");

        assertThatThrownBy(() -> account.increaseEncumbrance(new BigDecimal("-700000.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        // The assertion that actually encodes the exploit: the encumbrance must not have moved,
        // so available loan value must not have gone UP. Without the guard this would be
        // 1,000,000 - (-700,000) = 1,700,000 against a 1,000,000 cash value.
        assertThat(account.getLoanEncumbranceAmount()).isEqualByComparingTo("0.00");
        assertThat(account.availableLoanValue(BigDecimal.ZERO)).isEqualByComparingTo("1000000.00");
    }

    @Test
    void increaseEncumbranceRejectsZeroAndNull() {
        PolicyAccount account = new PolicyAccount("POL-TEST-0002", UUID.randomUUID(), new BigDecimal("1000000.00"), "TZS");
        assertThatThrownBy(() -> account.increaseEncumbrance(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.increaseEncumbrance(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void increaseEncumbranceStillAcceptsAPositiveAmount() {
        // Control: the guard must not have turned increaseEncumbrance into a no-op.
        PolicyAccount account = new PolicyAccount("POL-TEST-0003", UUID.randomUUID(), new BigDecimal("1000000.00"), "TZS");
        account.increaseEncumbrance(new BigDecimal("700000.00"));
        assertThat(account.getLoanEncumbranceAmount()).isEqualByComparingTo("700000.00");
        assertThat(account.availableLoanValue(BigDecimal.ZERO)).isEqualByComparingTo("300000.00");
    }
}
