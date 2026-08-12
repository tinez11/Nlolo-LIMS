package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review fix (C3). Guards the ONE thing about the shipped connection-pool configuration that is not
 * a matter of taste: it must be large enough for M5's nested {@code AFTER_COMMIT} chain.
 *
 * <p>Why this test exists at all, given that "assert the config equals the config" is a tautology:
 * the failure mode being guarded is not a wrong number, it is a REMOVED number. Before this fix
 * there was no {@code spring.datasource.hikari.*} configuration anywhere in the repository, so
 * {@code maximumPoolSize} was Hikari's default 10 -- and a single loan origination was measured
 * (instrumenting {@code HikariPoolMXBean.getActiveConnections()} through the real chain: loan
 * origination -> payment phase 1/3 -> policyloan.markDisbursed -> audit) to hold FOUR pooled
 * connections at once, because every level is {@code PROPAGATION_REQUIRES_NEW} and Spring holds a
 * suspended transaction's connection until the outer transaction completes. Three concurrent
 * originations therefore exhausted the pool, and a resulting pool timeout inside payment's phase 3
 * is swallowed by {@code PaymentRequestListener.withTenant}'s catch -- stranding a PENDING row with
 * no gateway_reference after the rail had already accepted the payout.
 *
 * <p>So the assertion is a genuine INEQUALITY derived from a measurement, not an equality restating
 * the file: the pool must fit at least {@value #MEASURED_CONNECTIONS_PER_ORIGINATION} x
 * {@value #MIN_SUPPORTED_CONCURRENT_ORIGINATIONS} connections. Deleting the config, or "optimizing"
 * it back toward Hikari's default, fails here with the reason attached. Raising it further passes.
 *
 * <p>Reads {@code application.yml} directly rather than booting a context, so it needs no
 * Testcontainers Postgres and costs milliseconds -- the value under test is the SHIPPED default, and
 * every integration test in this suite overrides datasource properties anyway.
 */
class ConnectionPoolSizingTest {

    /** Measured, not estimated -- see this class's javadoc. */
    private static final int MEASURED_CONNECTIONS_PER_ORIGINATION = 4;
    /** The floor this platform commits to. Hikari's default of 10 fails it, which is the point. */
    private static final int MIN_SUPPORTED_CONCURRENT_ORIGINATIONS = 3;

    private static Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        yaml.afterPropertiesSet();
        Properties properties = yaml.getObject();
        assertThat(properties).as("application.yml must be loadable at all").isNotNull();
        return properties;
    }

    /** The values are written as {@code ${ENV_VAR:default}} so a deployment can override them. This
     * test is about the DEFAULT -- i.e. what an unconfigured deployment actually gets -- so it
     * resolves the placeholder with no environment values supplied, exactly as Spring would when the
     * env var is absent. */
    private static int defaultIntValue(Properties properties, String key) {
        Object raw = properties.get(key);
        assertThat(raw).as("%s must be configured explicitly, not left to the framework default", key).isNotNull();
        String resolved = new PropertyPlaceholderHelper("${", "}", ":", false)
            .replacePlaceholders(raw.toString(), placeholder -> null);
        return Integer.parseInt(resolved.trim());
    }

    @Test
    void theShippedPoolFitsTheMeasuredNestedTransactionChainDepthAtRealisticConcurrency() {
        int maximumPoolSize = defaultIntValue(applicationYml(), "spring.datasource.hikari.maximum-pool-size");
        int required = MEASURED_CONNECTIONS_PER_ORIGINATION * MIN_SUPPORTED_CONCURRENT_ORIGINATIONS;

        assertThat(maximumPoolSize)
            .as("A single loan origination was MEASURED holding %d pooled connections simultaneously "
                + "(three-deep nested PROPAGATION_REQUIRES_NEW AFTER_COMMIT chain; Spring holds a "
                + "suspended transaction's connection until the outer one completes). The pool must fit "
                + "at least %d concurrent originations, i.e. %d connections. Hikari's default of 10 does "
                + "NOT, and a pool timeout inside payment's phase 3 is swallowed -- stranding a PENDING "
                + "row after the rail already accepted the payout. See application.yml's own comment.",
                MEASURED_CONNECTIONS_PER_ORIGINATION, MIN_SUPPORTED_CONCURRENT_ORIGINATIONS, required)
            .isGreaterThanOrEqualTo(required);
    }

    @Test
    void connectionTimeoutIsExplicitAndShorterThanHikarisThirtySecondDefault() {
        int connectionTimeoutMs = defaultIntValue(applicationYml(), "spring.datasource.hikari.connection-timeout");

        // Not a taste assertion: 30s of silent queueing makes pool exhaustion indistinguishable from
        // ordinary latency, and a request thread blocked that long has already failed. The lower
        // bound guards the opposite mistake -- a timeout so short that a legitimate burst of the
        // nested chains above is rejected as exhaustion.
        assertThat(connectionTimeoutMs).isLessThan(30_000).isGreaterThanOrEqualTo(2_000);
    }

    @Test
    void minimumIdleIsSmallerThanTheMaximumSoThePoolIsNotHeldOpenAtFullSize() {
        Properties properties = applicationYml();
        int maximumPoolSize = defaultIntValue(properties, "spring.datasource.hikari.maximum-pool-size");
        int minimumIdle = defaultIntValue(properties, "spring.datasource.hikari.minimum-idle");

        // Hikari DEFAULTS minimum-idle to maximum-pool-size, so raising the maximum without setting
        // this would hold every connection open for the process's whole life -- 24 Postgres backend
        // processes per JVM. maximum-pool-size is burst headroom; steady state needs nothing like it.
        // This assertion is coupled to the one above by construction: whoever raises the maximum must
        // keep the floor below it, or the raise silently becomes a permanent allocation.
        assertThat(minimumIdle).isPositive().isLessThan(maximumPoolSize);
    }
}
