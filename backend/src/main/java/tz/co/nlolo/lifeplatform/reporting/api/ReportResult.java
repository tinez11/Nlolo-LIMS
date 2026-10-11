package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.Instant;
import java.util.List;

public record ReportResult(String key, String title, String filterSummary, Instant generatedAt, List<ReportSection> sections) {}
