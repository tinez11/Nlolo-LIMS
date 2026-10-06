package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingQueueApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingRulesView;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventResolvedException;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventView;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleSet;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PostingQueueApiImpl implements PostingQueueApi {

    private final PostingRules rules;
    private final UnpostedEvents queue;
    private final PostingEngine engine;

    PostingQueueApiImpl(PostingRules rules, UnpostedEvents queue, PostingEngine engine) {
        this.rules = rules;
        this.queue = queue;
        this.engine = engine;
    }

    @Override
    public PostingRulesView postingRules() {
        PostingRuleSet set = rules.ruleSet();
        Map<String, String> names = ChartOfAccountBlueprint.accounts().stream()
            .collect(Collectors.toMap(ChartOfAccountBlueprint.Seed::code, ChartOfAccountBlueprint.Seed::name,
                (a, b) -> a));
        return new PostingRulesView(set.version(), set.versionLabel(), set.rules().stream()
            .map(r -> new PostingRulesView.Rule(r.id(), r.event(), r.models().stream().sorted().toList(), r.when(),
                r.effectiveFrom(), r.effectiveTo(), r.description(), r.lines().stream()
                    .map(l -> new PostingRulesView.Line(l.side(), l.account(), names.get(l.account()), l.amount(),
                        l.movement()))
                    .toList()))
            .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnpostedEventView> unpostedEvents(boolean openOnly) {
        return queue.list(TenantContext.get(), openOnly);
    }

    @Override
    public UnpostedEventView retryUnpostedEvent(UUID id, String by) {
        UUID tenantId = TenantContext.get();
        engine.retry(tenantId, id, by);
        return find(tenantId, id);
    }

    @Override
    @Transactional
    public UnpostedEventView dismissUnpostedEvent(UUID id, String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw new FinaccountingValidationException("Say why this event is dismissed rather than posted");
        }
        UUID tenantId = TenantContext.get();
        UnpostedEventView row = find(tenantId, id);
        if (row.resolution() != null || !queue.dismiss(tenantId, id, reason.trim(), by)) {
            throw new UnpostedEventResolvedException("This event was already "
                + (row.resolution() == null ? "resolved" : row.resolution().toLowerCase()));
        }
        return find(tenantId, id);
    }

    private UnpostedEventView find(UUID tenantId, UUID id) {
        return queue.find(tenantId, id)
            .orElseThrow(() -> new UnpostedEventNotFoundException("Unposted event " + id + " not found"));
    }
}
