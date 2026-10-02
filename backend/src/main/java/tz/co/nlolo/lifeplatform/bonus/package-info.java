/**
 * With-profits bonuses (product step 4): declarations, the attached-bonus ledger, exit settlements,
 * and this module's own status record for every participating policy.
 *
 * <p>policy::api for the projection it restates and the policy it reads; product::api for the
 * version's terms. Claims is read by envelope only, never depended on.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "product::api" })
package tz.co.nlolo.lifeplatform.bonus;
