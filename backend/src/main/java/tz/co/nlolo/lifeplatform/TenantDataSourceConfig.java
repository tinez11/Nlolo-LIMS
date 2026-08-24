package tz.co.nlolo.lifeplatform;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Wraps Spring Boot's autoconfigured datasource wiring. Marking the wrapper
 * bean @Primary means JPA's EntityManagerFactory autoconfiguration (and
 * anything else that autowires DataSource by type) picks this wrapped
 * instance -- a standard Spring Boot technique for layering behavior onto
 * autoconfigured infrastructure without disabling that autoconfiguration.
 *
 * Builds the real pooled DataSource here from DataSourceProperties rather
 * than injecting a "DataSource dataSource" parameter and wrapping it: any
 * DataSource-typed @Bean defined outside DataSourceAutoConfiguration itself
 * makes DataSourceAutoConfiguration's own PooledDataSourceConfiguration back
 * off (its @ConditionalOnMissingBean(DataSource.class) guard), meaning the
 * "dataSource" bean this class would otherwise try to inject is never
 * registered at all -- not a self-reference, a NoSuchBeanDefinitionException.
 * DataSourceProperties, by contrast, is registered unconditionally via
 * DataSourceAutoConfiguration's class-level @EnableConfigurationProperties,
 * so it is always present regardless of whether a DataSource bean already
 * exists. initializeDataSourceBuilder() is the exact call Spring Boot's own
 * DataSourceConfiguration.Hikari/Generic use internally to build the pooled
 * DataSource from spring.datasource.* -- reused here so this produces the
 * identical real connection pool DataSourceAutoConfiguration would have.
 *
 * The real pool is registered as its OWN bean (realDataSource), typed and
 * built as HikariDataSource specifically (not the generic DataSource the
 * builder would otherwise infer), with @ConfigurationProperties bound onto
 * it exactly the way Spring Boot's own DataSourceConfiguration.Hikari does.
 * Without this, spring.datasource.hikari.* (pool size, connection timeout,
 * leak detection threshold, max lifetime) would silently never apply -- the
 * pool would sit at HikariCP's own default size forever. Registering it as a
 * bean (rather than only newing it up inline) also means Spring closes it on
 * context shutdown (graceful connection draining) and Micrometer's
 * DataSourcePoolMetricsAutoConfiguration can find it to expose
 * hikaricp.connections.* metrics -- neither of which is possible for a pool
 * that only exists as a private local wrapped immediately in another type.
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource realDataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource tenantAwareDataSource(HikariDataSource realDataSource) {
        return new TenantAwareDataSource(realDataSource);
    }
}
