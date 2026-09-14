package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.FieldReceiptNotFoundException;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BillingExceptionHandler {

    @ExceptionHandler(FieldReceiptNotFoundException.class)
    public ProblemDetail handleFieldReceiptNotFound(FieldReceiptNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "FIELD_RECEIPT_NOT_FOUND");
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ProblemDetail handleNotFound(InvoiceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "INVOICE_NOT_FOUND");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
