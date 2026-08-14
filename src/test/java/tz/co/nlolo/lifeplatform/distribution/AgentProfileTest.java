package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.distribution.api.DistributionValidationException;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 3: a plain unit test over {@link AgentProfile} and {@link CommissionRule} -- no Spring, no
 * container, no database.
 *
 * <p>Covers {@code canAccrueCommission} across all three {@link LicenseStatus} values, and
 * {@code CommissionRule}'s rejection of {@code TierType.THRESHOLD_BONUS} at construction
 * (M7 user decision 1 -- nothing computes that tier, so no rule may be authored with it).
 */
class AgentProfileTest {

    private AgentProfile newAgent() {
        return new AgentProfile(UUID.randomUUID(), UUID.randomUUID(), "LIC-0001",
            LocalDate.now().plusYears(1), null, null, "test-staff");
    }

    // ---- AgentProfile.canAccrueCommission -------------------------------------------------

    @Test
    void activeLicenseCanAccrueCommission() {
        AgentProfile agent = newAgent();
        assertThat(agent.getLicenseStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(agent.canAccrueCommission()).isTrue();
    }

    @Test
    void expiredLicenseCannotAccrueCommission() {
        AgentProfile agent = newAgent();
        agent.setLicenseStatus(LicenseStatus.EXPIRED);
        assertThat(agent.canAccrueCommission()).isFalse();
    }

    @Test
    void suspendedLicenseCannotAccrueCommission() {
        AgentProfile agent = newAgent();
        agent.setLicenseStatus(LicenseStatus.SUSPENDED);
        assertThat(agent.canAccrueCommission()).isFalse();
    }

    // ---- CommissionRule THRESHOLD_BONUS rejection -----------------------------------------

    @Test
    void commissionRuleRejectsThresholdBonusTierType() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        assertThrows(DistributionValidationException.class, () -> new CommissionRule(
            tenantId, planId, TierType.THRESHOLD_BONUS, new BigDecimal("0.05"), null, null, null));
    }

    @Test
    void commissionRuleAcceptsEveryOtherTierType() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        CommissionRule rule = new CommissionRule(
            tenantId, planId, TierType.FIRST_YEAR, new BigDecimal("0.10"), null, null, null);
        assertThat(rule.getTierType()).isEqualTo(TierType.FIRST_YEAR);
        assertThat(rule.getRate()).isEqualByComparingTo("0.10");
    }
}
