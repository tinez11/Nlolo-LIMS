// distribution::api added so a case can VALIDATE the agent of record it carries.
//
// V2__agent_of_record.sql's own note said underwriting "does not depend on that module and does
// not need to in order to carry an id through to issuance". That was true while nothing could
// fail on the id: an unknown agent simply accrued no commission. It stopped being true when
// issuance began REFUSING an agent of record that is not an agent -- a case can no longer carry
// an id it has not checked, because there is no endpoint to correct a case's agent afterwards,
// so an unchecked id becomes a case that is accepted and can then never be issued.
//
// This is the same validation, one field over, that openCase already performs against
// `party::api` for the applicant: check the outbound reference at the boundary where whoever
// supplied it is still present to fix it.
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "party::api", "product::api", "document::api", "refdata::api", "distribution::api" })
package tz.co.nlolo.lifeplatform.underwriting;
