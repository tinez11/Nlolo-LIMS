package tz.co.nlolo.lifeplatform.product.api;

/**
 * The measurement models a contract can be accounted under: IFRS 17's general model, variable fee approach and
 * premium allocation approach, or IFRS 9 for a contract that is not insurance. Used for a version's override; the
 * model in force is the accounting policy register's (finaccounting).
 */
public enum Ifrs17Model { GMM, VFA, PAA, IFRS9 }
