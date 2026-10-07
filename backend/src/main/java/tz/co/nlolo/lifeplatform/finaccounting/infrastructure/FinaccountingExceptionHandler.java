package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountHasChildrenException;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountInUseException;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.DuplicateAccountCodeException;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyRegisterStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventResolvedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Scoped to {@code finaccounting}'s own domain exception types, mirroring
 * {@code ReinsuranceExceptionHandler}'s shape and its shared {@code @Order(HIGHEST_PRECEDENCE)}
 * requirement. That annotation is not decoration: {@code @ExceptionHandler} resolution is
 * first-matching-ADVICE-BEAN-wins across {@code @ControllerAdvice} beans rather than
 * merged-by-specificity, so an unordered advice here could be silently shadowed by
 * {@code GlobalExceptionHandler}'s {@code @Order(LOWEST_PRECEDENCE)} catch-all depending on
 * classpath-scan order.
 *
 * <p>Deliberately does NOT map {@code IllegalArgumentException} or {@code AccessDeniedException}:
 * {@code GlobalExceptionHandler:78} and {@code :83} already map them to 400 {@code VALIDATION_ERROR}
 * and 403 {@code FORBIDDEN}. A duplicate advice here would itself be the two-advices-shadowing-each-
 * other bug this discipline exists to prevent.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FinaccountingExceptionHandler {

    @ExceptionHandler(JournalEntryNotFoundException.class)
    public ProblemDetail handleJournalEntryNotFound(JournalEntryNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "JOURNAL_ENTRY_NOT_FOUND");
    }

    @ExceptionHandler(FinaccountingValidationException.class)
    public ProblemDetail handleValidation(FinaccountingValidationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "FINACCOUNTING_VALIDATION_FAILED");
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "ACCOUNT_NOT_FOUND");
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    public ProblemDetail handleDuplicateAccountCode(DuplicateAccountCodeException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "DUPLICATE_ACCOUNT_CODE");
    }

    @ExceptionHandler(AccountInUseException.class)
    public ProblemDetail handleAccountInUse(AccountInUseException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "ACCOUNT_IN_USE");
    }

    /** Distinct from ACCOUNT_IN_USE: that one means postings exist, this one means children do.
     *  Both are 409, but a caller resolves them differently -- deactivate versus deal with the
     *  children first. */
    @ExceptionHandler(AccountHasChildrenException.class)
    public ProblemDetail handleAccountHasChildren(AccountHasChildrenException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "ACCOUNT_HAS_CHILDREN");
    }

    @ExceptionHandler(PeriodStateException.class)
    public ProblemDetail handlePeriodState(PeriodStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "PERIOD_STATE");
    }

    @ExceptionHandler(PolicyRegisterStateException.class)
    public ProblemDetail handlePolicyRegisterState(PolicyRegisterStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "POLICY_REGISTER_STATE");
    }

    @ExceptionHandler(PolicyElectionNotFoundException.class)
    public ProblemDetail handlePolicyElectionNotFound(PolicyElectionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "POLICY_ELECTION_NOT_FOUND");
    }

    @ExceptionHandler(tz.co.nlolo.lifeplatform.finaccounting.api.EngineNotFoundException.class)
    public ProblemDetail handleEngineNotFound(tz.co.nlolo.lifeplatform.finaccounting.api.EngineNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "ENGINE_NOT_FOUND");
    }

    /** IFRS 17 I5a: a step of the engine cycle the period, the run's state or its people refuse. */
    @ExceptionHandler(tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException.class)
    public ProblemDetail handleEngineState(tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "ENGINE_STATE");
    }

    @ExceptionHandler(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationStateException.class)
    public ProblemDetail handleAllocationState(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "ALLOCATION_STATE");
    }

    @ExceptionHandler(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException.class)
    public ProblemDetail handleAllocationNotFound(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "ALLOCATION_NOT_FOUND");
    }

    @ExceptionHandler(ManualJournalNotFoundException.class)
    public ProblemDetail handleManualJournalNotFound(ManualJournalNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "MANUAL_JOURNAL_NOT_FOUND");
    }

    @ExceptionHandler(ManualJournalStateException.class)
    public ProblemDetail handleManualJournalState(ManualJournalStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "MANUAL_JOURNAL_STATE");
    }

    @ExceptionHandler(UnpostedEventNotFoundException.class)
    public ProblemDetail handleUnpostedEventNotFound(UnpostedEventNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "UNPOSTED_EVENT_NOT_FOUND");
    }

    @ExceptionHandler(UnpostedEventResolvedException.class)
    public ProblemDetail handleUnpostedEventResolved(UnpostedEventResolvedException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "UNPOSTED_EVENT_RESOLVED");
    }

    /** {@code traceId} is REQUIRED by openapi-common.yaml's ProblemDetails schema -- an M6 contract
     * test caught a filter omitting it, so every branch here sets it. */
    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
