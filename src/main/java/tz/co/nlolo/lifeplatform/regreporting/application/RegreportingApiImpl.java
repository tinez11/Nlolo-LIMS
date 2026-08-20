package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingApi;
import tz.co.nlolo.lifeplatform.regreporting.api.RegulatoryReturnView;
import tz.co.nlolo.lifeplatform.regreporting.api.ReturnLineView;
import tz.co.nlolo.lifeplatform.regreporting.api.ReturnNotFoundException;
import tz.co.nlolo.lifeplatform.regreporting.domain.RegulatoryReturn;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnLine;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.RegulatoryReturnRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReturnLineRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements {@link RegreportingApi}. Every method is tenant-scoped via {@link TenantContext#get()}
 * -- there is no other way into this module's data (design spec §5's read-only surface).
 */
@Service
public class RegreportingApiImpl implements RegreportingApi {

    private final ReturnGenerator returnGenerator;
    private final RegulatoryReturnRepository returnRepository;
    private final ReturnLineRepository lineRepository;

    public RegreportingApiImpl(ReturnGenerator returnGenerator,
                                RegulatoryReturnRepository returnRepository,
                                ReturnLineRepository lineRepository) {
        this.returnGenerator = returnGenerator;
        this.returnRepository = returnRepository;
        this.lineRepository = lineRepository;
    }

    @Override
    public RegulatoryReturnView generateReturn(String returnType, String period, String generatedBy) {
        UUID tenantId = TenantContext.get();
        RegulatoryReturn generated = returnGenerator.generate(tenantId, returnType, period, generatedBy);
        List<ReturnLine> lines = lineRepository
            .findByTenantIdAndReturnIdOrderByLineNoAsc(tenantId, generated.getReturnId());
        return toView(generated, lines);
    }

    @Override
    public List<RegulatoryReturnView> listReturns(String period) {
        UUID tenantId = TenantContext.get();
        List<RegulatoryReturn> returns = period == null
            ? returnRepository.findByTenantIdOrderByGeneratedAtDesc(tenantId)
            : returnRepository.findByTenantIdAndPeriodOrderByGeneratedAtDesc(tenantId, period);

        List<UUID> returnIds = returns.stream().map(RegulatoryReturn::getReturnId).toList();
        // Batch-loads every return's lines in ONE query and groups in memory -- the exact N+1
        // shape M9's final review found (and had to fix) in finaccounting's equivalent list
        // endpoint. A per-return query in the loop below must never be reintroduced.
        Map<UUID, List<ReturnLine>> linesByReturnId = lineRepository
            .findByTenantIdAndReturnIdInOrderByReturnIdAscLineNoAsc(tenantId, returnIds).stream()
            .collect(Collectors.groupingBy(ReturnLine::getReturnId));

        return returns.stream()
            .map(r -> toView(r, linesByReturnId.getOrDefault(r.getReturnId(), List.of())))
            .toList();
    }

    @Override
    public RegulatoryReturnView getReturn(UUID returnId) {
        UUID tenantId = TenantContext.get();
        // Tenant-scoped in the query itself (not merely relying on RLS) -- a return belonging to
        // another tenant must be exactly as invisible as an unknown id, never merely absent.
        RegulatoryReturn regulatoryReturn = returnRepository.findByReturnIdAndTenantId(returnId, tenantId)
            .orElseThrow(() -> new ReturnNotFoundException("Return " + returnId + " not found"));
        List<ReturnLine> lines = lineRepository
            .findByTenantIdAndReturnIdOrderByLineNoAsc(tenantId, returnId);
        return toView(regulatoryReturn, lines);
    }

    private RegulatoryReturnView toView(RegulatoryReturn r, List<ReturnLine> lines) {
        List<ReturnLineView> lineViews = lines.stream()
            .sorted(Comparator.comparingInt(ReturnLine::getLineNo))
            .map(this::toLineView)
            .toList();
        return new RegulatoryReturnView(r.getReturnId(), r.getReturnType(), r.getPeriod(), r.getStatus(),
            r.getDocumentRef(), r.getGeneratedAt(), r.getGeneratedBy(), lineViews);
    }

    private ReturnLineView toLineView(ReturnLine line) {
        return new ReturnLineView(line.getReturnLineId(), line.getLineNo(), line.getLineCode(),
            line.getLabel(), line.getMetricName().name(), line.getNumericValue(), line.getCurrency());
    }
}
