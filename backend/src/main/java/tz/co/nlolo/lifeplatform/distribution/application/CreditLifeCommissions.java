package tz.co.nlolo.lifeplatform.distribution.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CreditLifeEarlyRefund;
import tz.co.nlolo.lifeplatform.distribution.domain.CreditLifeFileCommission;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CreditLifeEarlyRefundRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CreditLifeFileCommissionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * A lender's commission on a credit-life file, from acceptance to its statement (distribution V9, 2026-10-30).
 *
 * <p>The user: the bank pays the full premium, then its commission is sent. So a file's commission is HELD at
 * acceptance and BOOKED only when billing says that file's invoice is paid. Booked at acceptance, as it was, the
 * month-end close made it payable on a file the lender had not paid for.
 *
 * <p>A borrower refunded before the lender paid leaves nothing booked to claw back from yet: the refund is remembered
 * and reversed from the commission the moment it is booked, exactly as a later refund would be.
 */
@Component
public class CreditLifeCommissions {

    private static final Logger log = LoggerFactory.getLogger(CreditLifeCommissions.class);

    private final CreditLifeFileCommissionRepository files;
    private final CreditLifeEarlyRefundRepository earlyRefunds;
    private final DistributionApiImpl distributionApiImpl;

    public CreditLifeCommissions(CreditLifeFileCommissionRepository files, CreditLifeEarlyRefundRepository earlyRefunds,
                                 DistributionApiImpl distributionApiImpl) {
        this.files = files;
        this.earlyRefunds = earlyRefunds;
        this.distributionApiImpl = distributionApiImpl;
    }

    /** At acceptance: what the file will earn its lender, held until its premium is paid. Redelivery is a no-op. */
    void hold(UUID tenantId, UUID submissionId, String policyNumber, UUID agentId, BigDecimal amount, String currency,
              BigDecimal filePremium) {
        if (amount.signum() <= 0 || files.findByTenantIdAndSubmissionId(tenantId, submissionId).isPresent()) {
            return;
        }
        files.saveAndFlush(new CreditLifeFileCommission(tenantId, submissionId, policyNumber, agentId, amount,
            currency, filePremium));
        log.info("Holding {} {} of commission for agent {} on scheme {} until submission {}'s premium is paid",
            amount, currency, agentId, policyNumber, submissionId);
    }

    /**
     * The file's invoice is paid: book its commission to the lender's open statement, then reverse any borrower
     * refunded before now. Redelivery finds it released and does nothing.
     *
     * @return whether this invoice was a held credit-life file's -- the caller then accrues nothing else on it
     */
    boolean release(UUID tenantId, UUID submissionId) {
        Optional<CreditLifeFileCommission> held = files.findByTenantIdAndSubmissionId(tenantId, submissionId);
        if (held.isEmpty()) {
            return false;
        }
        CreditLifeFileCommission file = held.get();
        if (file.isReleased()) {
            return true;
        }
        Optional<CommissionAccrual> booked = distributionApiImpl.persistAccrual(tenantId, file.getAgentId(),
            file.getPolicyNumber(), TierType.FIRST_YEAR, file.getAmount(), file.getCurrency(), currentPeriod(),
            submissionId.toString(), null, "system:billing.PremiumCollected");
        if (booked.isEmpty()) {
            return true;
        }
        file.released(booked.get().getAccrualId());
        files.save(file);
        log.info("Released {} {} of commission to agent {}: submission {}'s premium on scheme {} is paid",
            file.getAmount(), file.getCurrency(), file.getAgentId(), submissionId, file.getPolicyNumber());
        for (CreditLifeEarlyRefund early : earlyRefunds.findByTenantIdAndSubmissionId(tenantId, submissionId)) {
            clawBack(tenantId, booked.get(), early.getPolicyMemberId(), early.getRefunded(), file.getFilePremium(),
                "system:billing.PremiumRefundDue");
        }
        return true;
    }

    /**
     * A borrower refunded while the file's commission is still held: remembered, to be reversed when it is booked.
     *
     * @return whether the file's commission was held and unreleased -- nothing booked yet to claw back from
     */
    boolean rememberEarlyRefund(UUID tenantId, UUID submissionId, UUID policyMemberId, BigDecimal refunded) {
        Optional<CreditLifeFileCommission> held = files.findByTenantIdAndSubmissionId(tenantId, submissionId);
        if (held.isEmpty() || held.get().isReleased()) {
            return false;
        }
        if (!earlyRefunds.existsByTenantIdAndSubmissionIdAndPolicyMemberId(tenantId, submissionId, policyMemberId)) {
            earlyRefunds.saveAndFlush(new CreditLifeEarlyRefund(tenantId, submissionId, policyMemberId, refunded));
            log.info("Borrower {} on submission {} was refunded {} before the lender paid; reversed from the"
                + " commission when it is booked", policyMemberId, submissionId, refunded);
        }
        return true;
    }

    /**
     * The commission on a refunded borrower's premium comes back: the accrual scaled by the refunded share of the
     * file's premium, rounded once and never more than was booked. Keyed on the borrower, so each reverses once.
     */
    Optional<CommissionAccrual> clawBack(UUID tenantId, CommissionAccrual original, UUID policyMemberId,
                                         BigDecimal refunded, BigDecimal filePremium, String createdBy) {
        BigDecimal share = refunded.divide(filePremium, 10, RoundingMode.HALF_UP);
        BigDecimal reversal = original.getAmount().multiply(share).setScale(2, RoundingMode.HALF_UP)
            .min(original.getAmount());
        if (reversal.signum() <= 0) {
            log.info("Refund of {} rounds to no commission reversal of accrual {} -- nothing clawed back",
                refunded.toPlainString(), original.getAccrualId());
            return Optional.empty();
        }
        return distributionApiImpl.persistAccrual(tenantId, original.getAgentId(), original.getPolicyNumber(),
            TierType.FIRST_YEAR, reversal.negate(), original.getCurrency(), currentPeriod(), policyMemberId.toString(),
            original.getAccrualId(), createdBy);
    }

    private static String currentPeriod() {
        return YearMonth.now().toString();
    }
}
