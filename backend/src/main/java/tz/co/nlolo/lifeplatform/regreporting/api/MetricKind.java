package tz.co.nlolo.lifeplatform.regreporting.api;

/**
 * Whether a metric is a point-in-time balance or a within-period total.
 *
 * <p>This distinction is the whole reason the fact tables store movements rather than snapshots:
 * a {@link #STOCK} metric for period P is the cumulative sum of movements where {@code period <= P},
 * so a return generated in Q4 for Q3 reports Q3's real figure. A {@link #FLOW} metric is that
 * period's own row and nothing else.
 */
public enum MetricKind { STOCK, FLOW }
