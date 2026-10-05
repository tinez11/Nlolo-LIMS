package tz.co.nlolo.lifeplatform.unitlinked.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.RequestKey;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.RequestKeyRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A request that creates something, done once per Idempotency-Key (U2: a top-up) -- accumulation's arrangement, for its
 * reason: a top-up retried after a timeout was once collected and credited twice. A repeat of the key answers with what
 * the first request created; the create and the key row commit in ONE transaction, so two racing copies cannot both
 * commit -- the loser's insert hits the primary key, rolls back with its event, and is answered with the winner's result.
 */
@Component("unitLinkedIdempotentRequests")
class IdempotentRequests {

    private final RequestKeyRepository keys;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager entityManager;

    IdempotentRequests(RequestKeyRepository keys, PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.tx = new TransactionTemplate(transactionManager);
    }

    <V> V once(String idempotencyKey, String operation, String target, String by,
               Supplier<V> create, Function<V, UUID> idOf, Function<UUID, V> load) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
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
                // persist, not save: the id is assigned, so save() would MERGE rather than the INSERT that must fail.
                entityManager.persist(new RequestKey(tenantId, idempotencyKey, operation, target, idOf.apply(created), by));
                entityManager.flush();
                return created;
            });
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            return answered(tenantId, idempotencyKey, operation, target, load).orElseThrow(() -> e);
        }
    }

    private <V> Optional<V> answered(UUID tenantId, String key, String operation, String target, Function<UUID, V> load) {
        return keys.findById(new RequestKey.Id(tenantId, key)).map(existing -> {
            if (!existing.getOperation().equals(operation) || !existing.getTarget().equals(target)) {
                throw new UnitLinkedStateException("This Idempotency-Key was already used for a different request. "
                    + "A new request needs a new key.");
            }
            return load.apply(existing.getResourceId());
        });
    }
}
