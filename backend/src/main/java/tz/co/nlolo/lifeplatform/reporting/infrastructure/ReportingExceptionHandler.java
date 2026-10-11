package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.util.UUID;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReportingExceptionHandler {

    @ExceptionHandler(ReportNotFoundException.class)
    public ProblemDetail notFound(ReportNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "REPORT_NOT_FOUND");
    }

    @ExceptionHandler(ReportParameterException.class)
    public ProblemDetail badParameter(ReportParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "REPORT_PARAMETER_INVALID");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
