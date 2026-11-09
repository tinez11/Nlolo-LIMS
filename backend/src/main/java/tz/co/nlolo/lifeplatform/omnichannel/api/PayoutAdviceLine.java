package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One payment the company made on a policy (2026-11-09, the payout advice -- receipts step 4): the counterpart of a
 * premium receipt for money going out. A claim, a surrender, a withdrawal, an annuity or survival instalment.
 *
 * @param adviceNumber PA-year-eight characters of the payment's id, as printed on the advice
 * @param what         what the money was, in a customer's words: "Claim settlement", "Surrender value"
 * @param paidTo       the payee as the payment was instructed: a phone number, or a bank account
 * @param method       Mobile money, Bank transfer or Cheque
 */
public record PayoutAdviceLine(UUID disbursementId, String adviceNumber, LocalDate paidOn, BigDecimal amount,
                               String currency, String what, String paidTo, String method, String reference) {}
