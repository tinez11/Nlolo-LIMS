package tz.co.nlolo.lifeplatform.payment.api;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String message) { super(message); }
}
