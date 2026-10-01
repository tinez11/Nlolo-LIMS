/**
 * Product step 3: the accumulation engine -- the guide's "bucket" (§21.4). Owns a savings account's
 * balance and EVERY transaction that changes it; other modules ask, by event or through
 * {@code accumulation::api}, and this module posts. Entries are immutable and each source posts at
 * most once.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "product::api" })
package tz.co.nlolo.lifeplatform.accumulation;
