// iam::api added 2026-11-06: this module implements iam's StaffTexts port (a staff password reset by SMS). iam depends
// on nothing back, so no cycle forms through approvals -> iam.
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "party::api", "refdata::api", "iam::api" })
package tz.co.nlolo.lifeplatform.communication;
