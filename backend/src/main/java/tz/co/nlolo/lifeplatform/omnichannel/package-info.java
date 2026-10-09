// unitlinked::api and annuity::api added 2026-10-08 for the customer portal's policy page (units held, annuity income).
// product::api and accumulation::api added 2026-10-07 for the customer documents (the product's name on a
// schedule; a savings plan's account statement). Neither depends back on this module.
// underwriting::api added 2026-10-08 for the portal's applications (step 5): asking for a product opens a case.
// communication::api added 2026-10-08 for the portal's messages inbox (step 7); communication depends on party alone.
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "billing::api", "claims::api", "party::api",
    "product::api", "accumulation::api", "unitlinked::api", "annuity::api", "underwriting::api", "communication::api" })
package tz.co.nlolo.lifeplatform.omnichannel;
