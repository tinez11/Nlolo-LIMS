package tz.co.nlolo.lifeplatform.accumulation.api;

/**
 * A term account's life (V5): it runs until its maturity date, then is paid out -- MATURED -- or waits for a payee
 * when there is nobody to pay, or its payment failed and the money came back.
 */
public enum TermAccountStatus { RUNNING, MATURED, AWAITING_PAYEE }
