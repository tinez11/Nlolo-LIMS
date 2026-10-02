package tz.co.nlolo.lifeplatform.bonus.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;
import tz.co.nlolo.lifeplatform.bonus.domain.BonusRequestKey;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.BonusRequestKeyRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A request that creates something, done once per Idempotency-Key -- accumulation's
 * IdempotentRequests, for this module (each module owns its tables). A repeat of the key answers with
 * what the first created and creates nothing; a key reused for a different request is refused. The
 * create and its key commit in one transaction, so two racing copies cannot both commit.
 */
@Component("bonusIdempotentRequests")
public class BonusIdempotentRequests {

    private final BonusRequestKeyRepository keys;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager entityManager;

    public BonusIdempotentRequests(BonusRequestKeyRepository keys, PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public <V> V once(String idempotencyKey, String operation, String target, String by,
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
                // persist, not save: the id is assigned, so save() would MERGE rather than INSERT.
                entityManager.persist(new BonusRequestKey(tenantId, idempotencyKey, operation, target, idOf.apply(created), by));
                entityManager.flush();
                return created;
            });
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            return answered(tenantId, idempotencyKey, operation, target, load).orElseThrow(() -> e);
        }
    }

    private <V> Optional<V> answered(UUID tenantId, String key, String operation, String target, Function<UUID, V> load) {
        return keys.findById(new BonusRequestKey.Id(tenantId, key)).map(existing -> {
            if (!existing.getOperation().equals(operation) || !existing.getTarget().equals(target)) {
                throw new BonusStateException("This Idempotency-Key was already used for a different request. "
                    + "A new request needs a new key.");
            }
            return load.apply(existing.getResourceId());
        });
    }
}
