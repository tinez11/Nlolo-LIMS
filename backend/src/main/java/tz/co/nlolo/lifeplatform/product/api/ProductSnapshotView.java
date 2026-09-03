package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * M3 addition: `category` and `surrenderChargeScheduleJson` were not exposed here in M2
 * because nothing needed them yet. `policy.issuePolicy` needs `category` to gate SUSPENDED
 * eligibility (Deliverable 3 Rev 2 §3's flagged, unresolved "which product categories
 * support SUSPENDED" item); `policy.quoteSurrenderValue` needs the raw surrender-charge
 * JSON to compute an early-surrender penalty. Both are additive -- no existing caller of
 * this record breaks.
 */
public record ProductSnapshotView(UUID productId, UUID productVersionId, LocalDate effectiveDate,
                                   IfrsMeasurementModel ifrsMeasurementModel, int gracePeriodDays, BigDecimal maxLoanToValuePercent,
                                   ProductCategory category, String surrenderChargeScheduleJson,
                                   // Build 3 stored these and Build 3 did not expose them, so nothing could
                                   // read a bound it had just written. Its round-trip test read them back
                                   // through ProductVersionRepository rather than through this view, which
                                   // is why the gap survived a green suite. issueGates reads them here.
                                   EligibilityBounds eligibility) {}
