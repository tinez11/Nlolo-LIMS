package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryValidationException;
import tz.co.nlolo.lifeplatform.policy.api.InsufficientLoanValueException;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.NotASingleLifeProductException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyAlreadyIssuedForCaseException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException;
import tz.co.nlolo.lifeplatform.policy.api.UnknownAgentOfRecordException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * @Order(HIGHEST_PRECEDENCE) is required -- Spring resolves @ExceptionHandler methods by
 * first-matching-ADVICE-BEAN-wins, not merged-by-specificity across beans. Without this,
 * GlobalExceptionHandler's catch-all could shadow these domain-specific mappings depending on
 * classpath-scan order (the exact regression M1's final review found and fixed for
 * PartyExceptionHandler -- do not repeat it here).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PolicyExceptionHandler {

    @ExceptionHandler(PolicyNotFoundException.class)
    public ProblemDetail handleNotFound(PolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "POLICY_NOT_FOUND");
    }

    @ExceptionHandler(InvalidPolicyStateException.class)
    public ProblemDetail handleInvalidState(InvalidPolicyStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "INVALID_POLICY_STATE");
    }

    /**
     * 409 rather than 422: the request is not malformed and nothing about it needs correcting.
     * The work is already done, and the message carries the policy number that proves it.
     */
    @ExceptionHandler(PolicyAlreadyIssuedForCaseException.class)
    public ProblemDetail handleAlreadyIssuedForCase(PolicyAlreadyIssuedForCaseException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "POLICY_ALREADY_ISSUED_FOR_CASE");
    }

    @ExceptionHandler(BeneficiaryValidationException.class)
    public ProblemDetail handleBeneficiaryValidation(BeneficiaryValidationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "BENEFICIARY_VALIDATION_FAILED");
    }

    @ExceptionHandler(NotASingleLifeProductException.class)
    public ProblemDetail handleNotASingleLifeProduct(NotASingleLifeProductException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "NOT_A_SINGLE_LIFE_PRODUCT");
    }

    @ExceptionHandler(InsufficientLoanValueException.class)
    public ProblemDetail handleInsufficientLoanValue(InsufficientLoanValueException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "INSUFFICIENT_LOAN_VALUE");
    }

    @ExceptionHandler(UnknownAgentOfRecordException.class)
    public ProblemDetail handleUnknownAgentOfRecord(UnknownAgentOfRecordException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "UNKNOWN_AGENT_OF_RECORD");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
