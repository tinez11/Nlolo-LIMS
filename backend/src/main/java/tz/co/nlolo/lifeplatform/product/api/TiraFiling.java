package tz.co.nlolo.lifeplatform.product.api;

import java.time.LocalDate;

/**
 * The TIRA filing that authorises a product version to be sold.
 *
 * <p>In Tanzania a product and its rates must be filed with and approved by TIRA before sale.
 * Nothing on this platform recorded that: publishing a version was one ADMIN call with no
 * reference, no approval date, and no evidence the regulator had ever seen the rates.
 *
 * <p><b>Required on every publish, with no defaulting overload.</b> An optional compliance field
 * is one nobody fills in, and a convenience overload that defaulted it would be a hole exactly
 * where the requirement lives — which is how "mandatory" quietly becomes "optional in practice".
 *
 * <p>Validation lives here rather than in the publish path so that no caller can construct an
 * invalid filing to pass anywhere, the same arrangement as {@link EligibilityBounds}'s ordering
 * invariant and {@link FrequencyLoading}'s 0-100 bounds.
 *
 * <p><b>What this does NOT check.</b> That the version's effective date falls on or after
 * {@code approvalDate} — selling before the regulator approved — is a real compliance failure and
 * is deliberately not refused. Corrections on this platform are republishes that carry the
 * original's effective date while their filing is dated later, and a hard gate with no override
 * path produces invented dates rather than compliance. Recorded as an open item in the design
 * spec. Nothing verifies the reference corresponds to a real filing either; this records what a
 * human asserts.
 *
 * <p><b>And it is not a second approver.</b> One ADMIN can still change a live product's price in
 * a single call, now accompanied by a reference they typed themselves. The maker-checker finding
 * from the product audit stays open.
 */
public record TiraFiling(String reference, LocalDate approvalDate) {

    public TiraFiling {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                "A TIRA filing reference is required -- a product version may not be published"
                    + " without the filing that authorises it");
        }
        reference = reference.trim();
        if (approvalDate == null) {
            throw new IllegalArgumentException(
                "A TIRA approval date is required alongside the filing reference");
        }
        if (approvalDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                "TIRA approval date " + approvalDate + " is in the future; an approval that has"
                    + " not happened cannot authorise a product");
        }
    }
}
