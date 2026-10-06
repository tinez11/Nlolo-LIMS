package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.BordereauView;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsuranceTreatyRepository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The monthly reinsurance bordereau (IFRS 17 I3c, posting guide K-01..K-03; month-end step 3). Hourly, it writes the
 * bordereau of every treaty for every closed month (Dar es Salaam calendar) it has none for, from the treaty's
 * effective-from, and publishes {@code reinsurance.BordereauPosted}, which finaccounting posts: the ceded premium
 * Dr 1436 / Cr 1430 and the commission not contingent on claims Dr 1431 / Cr 1436. A bordereau is final once written.
 */
@Component
public class BordereauJob {

    private static final Logger log = LoggerFactory.getLogger(BordereauJob.class);
    static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    static final String EVENT = "reinsurance.BordereauPosted";

    private final JdbcTemplate jdbc;
    private final Bordereaux bordereaux;
    private final ReinsuranceTreatyRepository treaties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate requiresNew;

    BordereauJob(JdbcTemplate jdbc, Bordereaux bordereaux, ReinsuranceTreatyRepository treaties,
                 ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.bordereaux = bordereaux;
        this.treaties = treaties;
        this.events = events;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${reinsurance.bordereau-interval-ms:3600000}",
        initialDelayString = "${reinsurance.bordereau-interval-ms:3600000}")
    public void drain() {
        drain(LocalDate.now(CIVIL).withDayOfMonth(1));
    }

    /** Writes every bordereau due for a month before {@code monthStart}. */
    public void drain(LocalDate monthStart) {
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM reinsurance.bordereaux_due(?)",
                Date.valueOf(monthStart))) {
            UUID tenantId = (UUID) row.get("tenant_id");
            UUID treatyId = (UUID) row.get("treaty_id");
            YearMonth month = YearMonth.parse((String) row.get("period"));
            UUID previous = TenantContext.getOrNull();
            TenantContext.set(tenantId);
            try {
                requiresNew.executeWithoutResult(status -> write(tenantId, treatyId, month, "system:bordereau"));
            } catch (Exception e) {
                // One treaty's month failing must not cost every other its bordereau.
                log.error("Could not write the {} bordereau of treaty {} in tenant {}", month, treatyId, tenantId, e);
            } finally {
                if (previous != null) TenantContext.set(previous); else TenantContext.clear();
            }
        }
    }

    /** Writes and publishes one bordereau, in the caller's transaction and tenant; nothing when it already exists. */
    Optional<UUID> write(UUID tenantId, UUID treatyId, YearMonth month, String by) {
        if (bordereaux.exists(tenantId, treatyId, month)) {
            return Optional.empty();
        }
        ReinsuranceTreaty treaty = treaties.findByTreatyIdAndTenantId(treatyId, tenantId).orElseThrow();
        BordereauCalculator.Result result = calculate(tenantId, treaty, month);
        Optional<UUID> written = bordereaux.insert(tenantId, treatyId, month, treaty.getRetentionLimitCurrency(), result, by);
        written.ifPresent(id -> {
            log.info("Bordereau {} of treaty {} ({}): {} policies, premium {}, commission {}, recoveries {}", month,
                treatyId, treaty.getReinsurerName(), result.policyCount(), result.premium(), result.commission(),
                result.recoveries());
            if (result.premium().signum() > 0 || result.commission().signum() > 0) {
                String currency = treaty.getRetentionLimitCurrency();
                events.publishEvent(DomainEventEnvelope.of(EVENT, tenantId, Map.of(
                    "bordereauId", id.toString(),
                    "treatyId", treatyId.toString(),
                    "reinsurerName", treaty.getReinsurerName(),
                    "period", month.toString(),
                    "policyCount", result.policyCount(),
                    "premium", Map.of("amount", result.premium().toPlainString(), "currencyCode", currency),
                    "commission", Map.of("amount", result.commission().toPlainString(), "currencyCode", currency))));
            }
        });
        return written;
    }

    /** The month as it would be written now -- the posted one if it already exists. */
    BordereauView preview(UUID tenantId, ReinsuranceTreaty treaty, YearMonth month) {
        Optional<UUID> posted = bordereaux.idOf(tenantId, treaty.getTreatyId(), month);
        if (posted.isPresent()) {
            return bordereaux.find(tenantId, posted.get()).orElseThrow();
        }
        BordereauCalculator.Result r = calculate(tenantId, treaty, month);
        List<BordereauView.Line> lines = r.lines().stream().map(l -> new BordereauView.Line(l.type().name(),
            l.policyNumber(), l.claimId(), l.premiumShare(), l.policyPremium(), l.premium(), l.commission(),
            l.recovery())).toList();
        return new BordereauView(null, treaty.getTreatyId(), month.toString(), treaty.getRetentionLimitCurrency(),
            r.policyCount(), r.premium(), r.commission(), r.recoveries(), null, lines);
    }

    private BordereauCalculator.Result calculate(UUID tenantId, ReinsuranceTreaty treaty, YearMonth month) {
        BordereauCalculator.Treaty terms = new BordereauCalculator.Treaty(treaty.getRetentionLimitCurrency(),
            treaty.getCommissionPercent(), treaty.getXolAnnualPremium());
        return BordereauCalculator.calculate(month, terms, bordereaux.cededTo(tenantId, treaty.getTreatyId()),
            bordereaux.recoveriesIn(tenantId, treaty.getTreatyId(), month));
    }
}
