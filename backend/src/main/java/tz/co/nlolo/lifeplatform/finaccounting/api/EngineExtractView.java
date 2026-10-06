package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An extract the IFRS 17 engine was sent for a period (IFRS 17 I5a, month-end step 6): numbered per period, the groups
 * it names (an engine run may report only on those) and the row count of each sheet. The sheets are downloaded.
 */
public record EngineExtractView(UUID extractId, String period, int number, List<String> groups, int cashFlowRows,
                                int balanceRows, int policyRows, String documentRef, String createdBy, Instant createdAt) {}
