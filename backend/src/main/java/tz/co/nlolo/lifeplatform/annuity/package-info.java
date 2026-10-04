/**
 * Immediate annuities (product step 5, sub-project D1): the contract and its decisions -- created
 * from the case's choice at issue, its income locked once when the single premium arrives, and what
 * each death does (survivor, guarantee, end, capital refund).
 *
 * <p>Deferred annuities and pensions (D2): a contract that saves in its account from issue and vests
 * into an annuity -- on its target date, or as staff instruct inside the version's window -- priced at
 * the rates in force that day, with an optional lump sum.
 *
 * <p>It moves no money itself: benefitpayout owns the stream and pays it; claims pays the capital
 * refund through its own settlement. product::api for the forms and the pricer; underwriting::api for
 * the choice; party::api for the lives; policy::api for the contract it decorates; accumulation::api
 * to close the account at vesting (D2). Billing and claims reach it by event envelope only, so neither
 * is a dependency, and only claims depends on it.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "policy::api", "product::api", "underwriting::api", "party::api", "benefitpayout::api", "accumulation::api" })
package tz.co.nlolo.lifeplatform.annuity;
