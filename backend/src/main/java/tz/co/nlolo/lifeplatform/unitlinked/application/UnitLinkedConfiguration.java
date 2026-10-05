package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;

import java.time.Clock;

/**
 * The module's clock, named so it can never collide with another module's (plan C6). Every "now" that decides a
 * price -- the cut-off an approval must wait for, the instant an order is bound -- reads it, so the
 * forward-pricing tests can stand on a chosen instant instead of racing the real one.
 */
@Configuration
class UnitLinkedConfiguration {

    @Bean("unitLinkedClock")
    Clock unitLinkedClock() {
        return Clock.system(BindingRule.CIVIL_ZONE);
    }
}
