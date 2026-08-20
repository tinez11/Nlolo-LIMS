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

    /** {@code YYYY-Qn}, n in 1..4 -- regreporting/V2 section 7's {@code period_kind = 'QUARTERLY'},
     * and the ONLY implemented period kind (see {@link #validatePeriodMatchesKind}). */
    private static final Pattern QUARTERLY_PERIOD = Pattern.compile("^\\d{4}-Q[1-4]$");

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
     *         that definition's {@code periodKind} is {@code ANNUAL} (unsupported -- see
     *         {@link #validatePeriodMatchesKind}), if {@code period}'s format does not match that
     *         definition's {@code periodKind}, or if a definition line names a metric absent from
     *         {@link MetricName}
     */
    @Transactional
    public RegulatoryReturn generate(UUID tenantId, String returnType, String period, String generatedBy) {
        ReturnDefinition definition = definitionRepository.findByTenantIdAndReturnType(tenantId, returnType)
            .orElseThrow(() -> new RegreportingValidationException(
                "No return definition exists for returnType '" + returnType + "'"));

        // Validated BEFORE anything else touches the registry or the lines table -- this is what
        // stops a quarterly and an annual period ever being cumulative-summed together, and (M10
        // final review C2) what rejects an ANNUAL-kind definition outright rather than reporting
        // zeros and a year-stale position for it.
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

    /**
     * <b>{@code ANNUAL} is REJECTED, not validated</b> (M10 final review, C2). {@code
     * return_definition.period_kind}'s CHECK admits it and this method used to accept an
     * annual-shaped {@code ^\d{4}$} period against it -- but NO listener in this module ever writes
     * an annual-shaped period. Every movement is written {@code YYYY-Qn}. Generating against an
     * ANNUAL-kind definition therefore produced, with no exception whatsoever:
     * <ul>
     *   <li>ZERO for every FLOW metric -- {@code period = '2026'} matches no movement row; and</li>
     *   <li>a YEAR-STALE figure for every STOCK metric -- {@code period <= '2026'} excludes every
     *       2026 quarter, because {@code '2026-Q1' > '2026'} LEXICALLY, so the "position as of
     *       end-2026" was really the position as of end-2025.</li>
     * </ul>
     * A regulatory figure that is quietly wrong is worse than one that fails, so annual generation
     * fails loudly instead. Implementing it properly (rolling four quarters up, or recording annual
     * movements alongside quarterly ones) needs to know what TIRA's annual return actually asks
     * for -- which is exactly what C2 has not supplied -- so building it now would be inventing a
     * catalog, against this milestone's own minimalism principle. {@code 'ANNUAL'} stays in the DB
     * CHECK as a schema-level placeholder; see regreporting/V3's comment on {@code period_kind}.
     */
    private void validatePeriodMatchesKind(String periodKind, String period) {
        if ("ANNUAL".equals(periodKind)) {
            throw new RegreportingValidationException(
                "annual returns are not supported yet -- no annual movement periods exist; this "
                + "platform tracks only quarterly movements (period_kind 'ANNUAL' is a schema-level "
                + "placeholder pending the TIRA return catalog, C2)");
        }
        if (!"QUARTERLY".equals(periodKind)) {
            throw new RegreportingValidationException(
                "Return definition has an unrecognised period_kind '" + periodKind + "'");
        }
        if (period == null || !QUARTERLY_PERIOD.matcher(period).matches()) {
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
