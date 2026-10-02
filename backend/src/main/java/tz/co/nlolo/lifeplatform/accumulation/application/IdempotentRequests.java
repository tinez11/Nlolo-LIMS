package tz.co.nlolo.lifeplatform.accumulation.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.domain.RequestKey;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.RequestKeyRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A request that creates something, done once per Idempotency-Key.
 *
 * <p>A repeat of the key answers with what the first request created, and creates nothing: that
 * is what makes a client retry after a timeout harmless. The create and the key row commit in ONE
 * transaction. So two copies racing each other cannot both commit: the loser's insert hits the
 * primary key, its whole transaction rolls back (its request row AND its event, which is published
 * after commit), and it is answered with the winner's result.
 *
 * <p>Called from outside any transaction (the controller). The TransactionTemplate's default
 * REQUIRED propagation therefore starts a new one, and the create joins it.
 */
@Component("accumulationIdempotentRequests")
public class IdempotentRequests {

    private final RequestKeyRepository keys;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager entityManager;

    public IdempotentRequests(RequestKeyRepository keys, PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public <V> V once(String idempotencyKey, String operation, String target, String by,
                      Supplier<V> create, Function<V, UUID> idOf, Function<UUID, V> load) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // The 400 billing gives, for billing's reason: any default key would make a genuine
            // second request impossible, and no key at all is the bug this class exists to close.
            throw new IllegalArgumentException("Idempotency-Key header is required on this endpoint: the same key is "
                + "treated as the same request, a new key as a new one");
        }
        UUID tenantId = TenantContext.get();
        Optional<V> answered = answered(tenantId, idempotencyKey, operation, target, load);
        if (answered.isPresent()) {
            return answered.get();
        }
        try {
            return tx.execute(status -> {
                V created = create.get();
                // persist, not save: the id is assigned, so save() would MERGE -- a SELECT and then an
                // UPDATE of a row a racing twin had just written, rather than the INSERT that must fail.
                entityManager.persist(new RequestKey(tenantId, idempotencyKey, operation, target, idOf.apply(created), by));
                entityManager.flush();
                return created;
            });
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            // Lost the race to the same request. Answer as the winner was answered. Both types, because
            // a flush through the EntityManager raises JPA's own exception, not Spring's translation of it.
            return answered(tenantId, idempotencyKey, operation, target, load).orElseThrow(() -> e);
        }
    }

    private <V> Optional<V> answered(UUID tenantId, String key, String operation, String target, Function<UUID, V> load) {
        return keys.findById(new RequestKey.Id(tenantId, key)).map(existing -> {
            if (!existing.getOperation().equals(operation) || !existing.getTarget().equals(target)) {
                throw new AccumulationStateException("This Idempotency-Key was already used for a different request. "
                    + "A new request needs a new key.");
            }
            return load.apply(existing.getResourceId());
        });
    }
}
