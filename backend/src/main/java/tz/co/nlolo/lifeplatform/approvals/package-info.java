/**
 * What is waiting for a signature, across the platform (2026-10-27).
 *
 * <p>Finance signed in ~20 places -- a withdrawal on one policy, a surrender on another, a fund price, a payment run --
 * and most of them could be found only by opening the right record. This module owns the contract and nothing else:
 * each module that holds an approval implements {@link tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource} for its
 * own rows, and this module gathers them, keeps the groups the viewer may act on, and names who asked.
 *
 * <p>It depends on no module that holds an approval -- they depend on it -- so the gathering cannot form a cycle.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "iam::api" })
package tz.co.nlolo.lifeplatform.approvals;
