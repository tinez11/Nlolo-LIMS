package tz.co.nlolo.lifeplatform.audit.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.audit.application.ActivityLabels;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRecorder;

/**
 * Places the activity recorder just inside Spring Security (2026-11-14). A servlet filter ordered after the security
 * chain runs within its doFilter, so within TenantContextFilter's try: the tenant and the person are set, and the
 * dispatcher has not yet run. Registered here rather than in iam's SecurityConfig, so iam does not depend on audit.
 */
@Configuration
class ActivityFilterConfig {

    @Bean
    FilterRegistrationBean<ActivityRecordingFilter> activityRecording(ActivityLabels labels, ActivityRecorder recorder,
                                                                      ObjectMapper json) {
        FilterRegistrationBean<ActivityRecordingFilter> bean =
            new FilterRegistrationBean<>(new ActivityRecordingFilter(labels, recorder, json));
        bean.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        return bean;
    }
}
