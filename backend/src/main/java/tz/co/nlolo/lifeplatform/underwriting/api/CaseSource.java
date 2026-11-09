package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where a case came from, so an underwriter can tell them apart in the queue (2026-10-29, the user: "separate the cases
 * well so the underwriter knows the source -- simple, no complications"). Read from what every case already records;
 * nothing new is stored.
 *
 * <ul>
 *   <li>{@code CUSTOMER_ONLINE} -- the customer applied in the portal, priced and with their health answers;</li>
 *   <li>{@code PUBLIC_ONLINE} -- somebody with no login applied through the public quote link (2026-11-08): a new
 *       client, KYC pending, whose phone was confirmed by a texted code;</li>
 *   <li>{@code CALL_BACK} -- the customer asked an adviser about a product in the portal, with no amount: someone calls
 *       them before there is anything to decide;</li>
 *   <li>{@code AGENT} -- an agent's quotation, or a case with an agent of record;</li>
 *   <li>{@code STAFF} -- opened in the office.</li>
 * </ul>
 */
public enum CaseSource {
    CUSTOMER_ONLINE, PUBLIC_ONLINE, CALL_BACK, AGENT, STAFF;

    /** What the portal writes as a case's source of business. */
    public static final String PORTAL = "CUSTOMER_PORTAL";
    /** What an agent's submitted quotation writes. */
    public static final String AGENT_QUOTATION = "Agent quotation";
    /** What the public quote link writes. */
    public static final String PUBLIC_LINK = "PUBLIC_LINK";

    public static CaseSource of(String sourceOfBusiness, BigDecimal sumAssured, UUID agentOfRecordId) {
        if (PUBLIC_LINK.equals(sourceOfBusiness)) {
            return PUBLIC_ONLINE;
        }
        if (PORTAL.equals(sourceOfBusiness)) {
            return sumAssured == null || sumAssured.signum() == 0 ? CALL_BACK : CUSTOMER_ONLINE;
        }
        return agentOfRecordId != null || AGENT_QUOTATION.equals(sourceOfBusiness) ? AGENT : STAFF;
    }
}
