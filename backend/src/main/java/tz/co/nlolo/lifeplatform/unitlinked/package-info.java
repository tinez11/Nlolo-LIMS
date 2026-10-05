/**
 * Unit-linked (product step 6, U1): the fund register, two-person prices, the unit ledger, and every decision
 * that moves units -- premiums bought, charges sold, deaths, surrenders, maturities, lapses and free-look
 * unwinds, each priced FORWARD (spec §5). It pays nothing itself: payment pays its PayoutRequested;
 * benefitpayout releases a free-look refund; claims pays a death through its own settlement.
 *
 * <p>policy::api for the policy it decorates; product::api for the version's terms (and the FundDirectory
 * SPI it implements); underwriting::api for the sale's fund choice; party::api for the life's age and sex;
 * benefitpayout::api for the free-look refund; refdata::api for the price-move alert; document::api to file a unit
 * statement's PDF (U2). Billing, claims and policy reach it by event envelope only.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "policy::api", "product::api", "underwriting::api", "party::api", "benefitpayout::api", "refdata::api",
    "document::api" })
package tz.co.nlolo.lifeplatform.unitlinked;
