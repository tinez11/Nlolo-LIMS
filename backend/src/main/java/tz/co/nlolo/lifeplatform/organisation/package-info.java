/**
 * The company itself (2026-11-09, organisation settings): its profile, its branding, the people who sign its
 * documents, and its branches -- and the branded renderer every document and export is drawn with.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "document::api", "refdata::api" })
package tz.co.nlolo.lifeplatform.organisation;
