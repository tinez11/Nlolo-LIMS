package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;

/**
 * How much a policy can borrow now, and how that is reached (2026-11-07, the user: the Loans page shows everything a
 * loan needs). Every figure is the server's, so the page does no arithmetic.
 *
 * @param lends                 false when the product version lends nothing (its maximum loan is 0)
 * @param value                 the policy's value: its cash value, or a savings account's balance
 * @param maxLoanToValuePercent the most a loan may be, as a share of the value; null = the whole value
 * @param limit                 value × that share
 * @param borrowed              principal borrowed and not yet repaid, loans waiting for their payout included
 * @param interestOwed          interest accrued and not yet repaid
 * @param canBorrowNow          limit − borrowed − interest owed − loans being taken this moment, never below zero
 * @param annualInterestRate    the rate a new loan is charged, % a year
 */
public record LoanLimitView(String policyNumber, String currency, boolean lends, BigDecimal value,
                            BigDecimal maxLoanToValuePercent, BigDecimal limit, BigDecimal borrowed,
                            BigDecimal interestOwed, BigDecimal canBorrowNow, BigDecimal annualInterestRate) {}
