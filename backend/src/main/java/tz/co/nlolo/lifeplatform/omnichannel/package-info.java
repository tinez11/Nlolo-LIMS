// unitlinked::api and annuity::api added 2026-10-08 for the customer portal's policy page (units held, annuity income).
// product::api and accumulation::api added 2026-10-07 for the customer documents (the product's name on a
// schedule; a savings plan's account statement). Neither depends back on this module.
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "billing::api", "claims::api", "party::api",
    "product::api", "accumulation::api", "unitlinked::api", "annuity::api" })
package tz.co.nlolo.lifeplatform.omnichannel;
