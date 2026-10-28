package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Money a customer chose to add, collected through payment and credited only once confirmed. */
public record TopUpView(UUID topUpId, String policyNumber, BigDecimal amount, String currency, String payerRef,
                        String status, String requestedBy, Instant requestedAt,
                        /* MOBILE_MONEY (a request to the payer's phone) or how it was received by hand. */
                        String method,
                        /* The method's own reference and the day it came; null on a mobile-money request. */
                        String reference, java.time.LocalDate receivedOn) {}
