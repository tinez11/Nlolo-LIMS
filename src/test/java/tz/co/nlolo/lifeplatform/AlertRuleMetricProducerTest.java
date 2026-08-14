package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the PRODUCER side of alerting, the half that {@link ActuatorExposureTest} cannot see.
 *
 * <p><b>Why this exists.</b> M6's final review found that no alert rule could fire because nothing
 * exported metrics at all. Fixing the transport (adding {@code micrometer-registry-prometheus} and
 * a reachable scrape endpoint) then produced a subtler version of the SAME failure, which a review
 * of that very fix caught: the transport works, but 6 of the 13 rules reference metrics that no
 * code in this repository emits and no configured scrape target provides. They are as silent after
 * the fix as before it, and the fix's own commit message initially implied otherwise.
 *
 * <p>The worst instance is {@code RlsBypassRoleDetected} ({@code severity: critical}), whose
 * annotation asserted that its metric "is emitted by a scheduled query against pg_roles
 * specifically to catch it in production, not just in testing". Nothing emitted it. A critical
 * detector for the single failure mode that would void this platform's entire tenant-isolation
 * layer was documented as production-grade while being unreachable. That annotation is now
 * corrected, and this test is what stops the class of claim from recurring.
 *
 * <p><b>What it enforces.</b> Every metric name referenced by {@code observability/alert-rules.yml}
 * must be classified as either produced by this application or knowingly external/unimplemented.
 * A new rule referencing an unclassified metric fails here, forcing the author to say which it is
 * rather than shipping a rule that looks live and is not. This is deliberately a plain unit test:
 * no Spring context, no containers, so it costs milliseconds and cannot be skipped for being slow.
 *
 * <p>It does NOT assert the 6 unproduced metrics are acceptable — only that their absence is
 * deliberate and recorded. Giving any of them a real producer means moving it between the two sets
 * below, which is a one-line, reviewable diff.
 */
class AlertRuleMetricProducerTest {

    private static final Path ALERT_RULES = Path.of("observability/alert-rules.yml");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /** Metrics this application registers itself. Each MUST appear as a literal in src/main. */
    private static final Set<String> PRODUCED_BY_THIS_APPLICATION = Set.of(
        "lifeplatform_payment_gateway_requests_total",
        "lifeplatform_payment_in_doubt_total",
        "lifeplatform_payment_callback_ambiguous_total",
        "lifeplatform_payment_callback_unknown_status_total",
        "lifeplatform_claims_settlement_failed_total",
        "lifeplatform_claims_policy_closure_failed_total",
        "lifeplatform_field_receipt_overdue_count",
        "lifeplatform_distribution_clawback_total");

    /**
     * Metrics referenced by a rule that NOTHING currently produces, each with the reason. Every
     * entry here is a rule that cannot fire today. Listed so the gap is explicit rather than
     * discovered during an incident.
     */
    private static final Map<String, String> KNOWINGLY_UNPRODUCED = Map.ofEntries(
        Map.entry("lifeplatform_app_role_has_bypassrls",
            "RlsBypassRoleDetected (severity: critical). No producer. The property IS enforced in "
            + "CI and asserted by AppRolePrivilegesIntegrationTest/RowLevelSecurityIntegrationTest, "
            + "but nothing checks it in a running deployment. Closing this needs a scheduled "
            + "pg_roles query publishing a gauge -- note a Java @Scheduled thread has no "
            + "TenantContext, so follow billing's pg_cron + SECURITY DEFINER pattern."),
        Map.entry("lifeplatform_app_role_is_superuser",
            "RlsBypassRoleDetected, same rule and same gap as the bypassrls gauge above."),
        Map.entry("lifeplatform_failed_event_total",
            "FailedEventBacklogGrowing. audit.failed_event has no schema yet -- recorded as an "
            + "unclosed cross-deliverable item since M4 (docs/05-event-catalog.md) and still open."),
        Map.entry("lifeplatform_max_partition_upper_bound_epoch",
            "PartitionHeadroomLow. No producer. Partition headroom is currently verified only by "
            + "db-migrations/_post-migration/verify-partition-controls.sql at deploy time."),
        Map.entry("camunda_process_instances",
            "SurrenderMaturityProcessStuck. Camunda is not a dependency of this project at all and "
            + "was deliberately dropped platform-wide (Camunda 7 EOL; Camunda 8's shared-thread-pool "
            + "job workers risk a fail-open cross-tenant leak against this platform's ThreadLocal "
            + "TenantContext). This rule is vestigial and should arguably be deleted."),
        Map.entry("camunda_process_instance_start_timestamp",
            "SurrenderMaturityProcessStuck, same vestigial rule as above."),
        Map.entry("pg_cron_job_failed_total",
            "PgPartmanMaintenanceFailed. The stock postgres-exporter in infra/docker-compose.yml "
            + "does not emit this; it needs a custom queries file against cron.job_run_details."),
        Map.entry("node_filesystem_avail_bytes",
            "PostgresDiskSpaceLow. Requires node-exporter, which is in neither compose file."),
        Map.entry("node_filesystem_size_bytes",
            "PostgresDiskSpaceLow, same missing node-exporter as above."));

    /** Matches a Prometheus metric name in an expr: an identifier we care about tracking. */
    private static final Pattern METRIC_NAME = Pattern.compile(
        "\\b(lifeplatform_[a-z0-9_]+|camunda_[a-z0-9_]+|pg_cron_[a-z0-9_]+|node_[a-z0-9_]+)\\b");

    private static Set<String> metricsReferencedByAlertRules() throws IOException {
        String yaml = Files.readString(ALERT_RULES);
        Set<String> found = new TreeSet<>();
        Matcher matcher = METRIC_NAME.matcher(yaml);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    void everyMetricAnAlertRuleReferencesIsEitherProducedHereOrKnowinglyUnproduced() throws IOException {
        Set<String> referenced = metricsReferencedByAlertRules();
        assertThat(referenced)
            .as("sanity: the regex must actually find metrics in %s", ALERT_RULES)
            .isNotEmpty();

        Set<String> unclassified = new LinkedHashSet<>(referenced);
        unclassified.removeAll(PRODUCED_BY_THIS_APPLICATION);
        unclassified.removeAll(KNOWINGLY_UNPRODUCED.keySet());

        assertThat(unclassified)
            .as("""
                A rule in observability/alert-rules.yml references a metric that is neither listed \
                as produced by this application nor listed as knowingly unproduced. Classify it in \
                AlertRuleMetricProducerTest: add it to PRODUCED_BY_THIS_APPLICATION if code emits \
                it (the next assertion will verify that), or to KNOWINGLY_UNPRODUCED with the \
                reason it cannot fire yet. A rule referencing a metric nothing publishes is as \
                silent as no rule at all -- which is the exact defect this test exists to prevent \
                recurring.""")
            .isEmpty();
    }

    @Test
    void everyMetricClaimedToBeProducedHereGenuinelyAppearsInMainSources() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            List<String> allMainSource = sources
                .filter(p -> p.toString().endsWith(".java"))
                .map(p -> {
                    try {
                        return Files.readString(p);
                    } catch (IOException e) {
                        throw new IllegalStateException("could not read " + p, e);
                    }
                })
                .toList();

            for (String metric : new TreeSet<>(PRODUCED_BY_THIS_APPLICATION)) {
                assertThat(allMainSource.stream().anyMatch(src -> src.contains(metric)))
                    .as("""
                        %s is listed as produced by this application but appears nowhere in \
                        src/main. Either a producer was removed (in which case its alert rule is \
                        now silent -- move it to KNOWINGLY_UNPRODUCED with that reason) or the \
                        metric was renamed (in which case the alert rule's expression no longer \
                        matches the emitted name, and the rule is silent for that reason \
                        instead). A near-miss metric name is a documented prior failure mode on \
                        this project.""", metric)
                    .isTrue();
            }
        }
    }

    /** The inverse guard: a metric listed as unproduced must NOT have quietly gained a producer,
     * because that would mean a live rule is documented here as dead. */
    @Test
    void noMetricListedAsUnproducedHasQuietlyGainedAProducer() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            List<String> allMainSource = sources
                .filter(p -> p.toString().endsWith(".java"))
                .map(p -> {
                    try {
                        return Files.readString(p);
                    } catch (IOException e) {
                        throw new IllegalStateException("could not read " + p, e);
                    }
                })
                .toList();

            for (String metric : new TreeSet<>(KNOWINGLY_UNPRODUCED.keySet())) {
                assertThat(allMainSource.stream().noneMatch(src -> src.contains(metric)))
                    .as("""
                        %s is listed as knowingly unproduced, but src/main now references it. If a \
                        producer was added, move it to PRODUCED_BY_THIS_APPLICATION -- leaving it \
                        here understates the platform's real alerting coverage, which is the same \
                        class of false documentation this test was written to stop.""", metric)
                    .isTrue();
            }
        }
    }
}
