/**
 * The payout engine -- the guide's "tap" (§21.4): money the insurer pays OUT while the life
 * assured is still alive. A maturity benefit, the survival benefits of a money-back plan, an
 * income stream, a premium return on a return-of-premium term policy, and free-look cancellation.
 *
 * <p>It depends on {@code policy} and {@code product} only. Billing, claims and payment reach it
 * as event envelopes -- a type string and a Map -- so none of them is a compile-time dependency,
 * the arrangement {@code policy}'s own cash-value listener already uses. Nothing depends on this
 * module except through its published events, with one exception: {@code claims} calls
 * {@code benefitpayout::api} to value a death claim, and there is no cycle because this module
 * never imports {@code claims}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "product::api", "accumulation::api" })
package tz.co.nlolo.lifeplatform.benefitpayout;
