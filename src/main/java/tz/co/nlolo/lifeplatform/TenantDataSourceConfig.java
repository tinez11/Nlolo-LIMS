package tz.co.nlolo.lifeplatform;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Wraps Spring Boot's autoconfigured datasource wiring. Marking this bean
 * @Primary means JPA's EntityManagerFactory autoconfiguration (and anything
 * else that autowires DataSource by type) picks this wrapped instance --
 * a standard Spring Boot technique for layering behavior onto autoconfigured
 * infrastructure without disabling that autoconfiguration.
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
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    @Primary
    public DataSource tenantAwareDataSource(DataSourceProperties dataSourceProperties) {
        DataSource dataSource = dataSourceProperties.initializeDataSourceBuilder().build();
        return new TenantAwareDataSource(dataSource);
    }
}
