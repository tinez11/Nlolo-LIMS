package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingValidationException;
import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import tz.co.nlolo.lifeplatform.regreporting.domain.RegulatoryReturn;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinition;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinitionLine;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnLine;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.RegulatoryReturnRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReturnDefinitionLineRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReturnDefinitionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReturnLineRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The module's core write path: resolves a seeded {@link ReturnDefinition}'s lines through
 * {@link MetricReaderRegistry} and writes the result as {@link RegulatoryReturn} plus its
 * {@link ReturnLine} rows (design spec §5, §7).
 *
 * <p>Idempotent per {@code (tenant, returnType, period)} -- see {@link #generate}'s javadoc.
 */
@Component
public class ReturnGenerator {

    /** {@code YYYY-Qn}, n in 1..4 -- regreporting/V2 section 7's {@code period_kind = 'QUARTERLY'}. */
    private static final Pattern QUARTERLY_PERIOD = Pattern.compile("^\\d{4}-Q[1-4]$");
    /** {@code YYYY} -- regreporting/V2 section 7's {@code period_kind = 'ANNUAL'}. */
    private static final Pattern ANNUAL_PERIOD = Pattern.compile("^\\d{4}$");

    /** Every fact table in this module defaults its currency column to TZS (regreporting/V2
     * sections 4-6) and none carries any other currency today -- this module has no source of a
     * per-tenant reporting currency yet, so a monetary line's currency is this same constant
     * until one exists. */
    private static final String REPORTING_CURRENCY = "TZS";

    private final ReturnDefinitionRepository definitionRepository;
    private final ReturnDefinitionLineRepository definitionLineRepository;
    private final RegulatoryReturnRepository returnRepository;
    private final ReturnLineRepository lineRepository;
    private final MetricReaderRegistry metricReaderRegistry;

    public ReturnGenerator(ReturnDefinitionRepository definitionRepository,
                            ReturnDefinitionLineRepository definitionLineRepository,
                            RegulatoryReturnRepository returnRepository,
                            ReturnLineRepository lineRepository,
                            MetricReaderRegistry metricReaderRegistry) {
        this.definitionRepository = definitionRepository;
        this.definitionLineRepository = definitionLineRepository;
        this.returnRepository = returnRepository;
        this.lineRepository = lineRepository;
        this.metricReaderRegistry = metricReaderRegistry;
    }

    /**
     * Generates (or REGENERATES) the return for {@code (tenantId, returnType, period)}.
     *
     * <p>Regeneration REPLACES: the existing {@code regulatory_return} row for this triple (if
     * any) is reused -- only {@code generatedAt}/{@code generatedBy} change -- and its existing
     * lines are deleted before the fresh ones are written, so a repeat call never leaves two
     * return rows or duplicated lines for one triple (the {@code ux_regulatory_return_once}
     * constraint backs this, but the read path must not rely on hitting it).
     *
     * @throws RegreportingValidationException if no definition exists for {@code returnType}, if
     *         {@code period}'s format does not match that definition's {@code periodKind}, or if
     *         a definition line names a metric absent from {@link MetricName}
     */
    @Transactional
    public RegulatoryReturn generate(UUID tenantId, String returnType, String period, String generatedBy) {
        ReturnDefinition definition = definitionRepository.findByTenantIdAndReturnType(tenantId, returnType)
            .orElseThrow(() -> new RegreportingValidationException(
                "No return definition exists for returnType '" + returnType + "'"));

        // Validated BEFORE anything else touches the registry or the lines table -- this is what
        // stops a quarterly and an annual period ever being cumulative-summed together.
        validatePeriodMatchesKind(definition.getPeriodKind(), period);

        RegulatoryReturn regulatoryReturn = returnRepository
            .findByTenantIdAndReturnTypeAndPeriod(tenantId, returnType, period)
            .map(existing -> {
                existing.regenerate(generatedBy);
                return existing;
            })
            .orElseGet(() -> new RegulatoryReturn(tenantId, returnType, period, generatedBy));
        regulatoryReturn = returnRepository.save(regulatoryReturn);

        // Delete-before-write, never write-before-delete: a new return's id has no prior lines
        // (a no-op delete), and an existing return's prior lines must be gone before the fresh
        // set is written, or ux_return_line_no (return_id, line_no) would collide. The explicit
        // flush is NOT optional: Hibernate's action queue always executes pending INSERTs before
        // pending DELETEs within one flush, regardless of the order the calls below register
        // them, so without this the new lines' INSERTs would hit the unique index before the old
        // rows' DELETEs ever ran, on every regeneration.
        lineRepository.deleteByTenantIdAndReturnId(tenantId, regulatoryReturn.getReturnId());
        lineRepository.flush();

        List<ReturnDefinitionLine> definitionLines =
            definitionLineRepository.findByTenantIdAndReturnTypeOrderByLineNoAsc(tenantId, returnType);

        UUID returnId = regulatoryReturn.getReturnId();
        List<ReturnLine> lines = new ArrayList<>(definitionLines.size());
        for (ReturnDefinitionLine definitionLine : definitionLines) {
            MetricName metric = resolveMetric(definitionLine.getMetricName());
            BigDecimal value = metricReaderRegistry.read(
                tenantId, metric, period, definitionLine.getDimensionFilter());
            String currency = metric.isMonetary() ? REPORTING_CURRENCY : null;
            lines.add(new ReturnLine(tenantId, returnId, definitionLine.getLineNo(),
                definitionLine.getLineCode(), definitionLine.getLabel(), metric, value, currency));
        }
        lineRepository.saveAll(lines);

        return regulatoryReturn;
    }

    private void validatePeriodMatchesKind(String periodKind, String period) {
        Pattern expected = switch (periodKind) {
            case "QUARTERLY" -> QUARTERLY_PERIOD;
            case "ANNUAL" -> ANNUAL_PERIOD;
            default -> throw new RegreportingValidationException(
                "Return definition has an unrecognised period_kind '" + periodKind + "'");
        };
        if (period == null || !expected.matcher(period).matches()) {
            throw new RegreportingValidationException(
                "period '" + period + "' does not match this return type's period_kind '" + periodKind + "'");
        }
    }

    /** Never lets a definition line naming something outside the seventeen {@link MetricName}
     * values emit a null-valued line -- fails loudly instead, naming the bad metric. */
    private MetricName resolveMetric(String metricName) {
        try {
            return MetricName.valueOf(metricName);
        } catch (IllegalArgumentException e) {
            throw new RegreportingValidationException(
                "Return definition line names an unknown metric: '" + metricName + "'");
        }
    }
}
