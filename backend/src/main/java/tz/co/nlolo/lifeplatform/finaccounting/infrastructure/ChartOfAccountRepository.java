package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface ChartOfAccountRepository extends JpaRepository<ChartOfAccount, ChartOfAccountId> {
    List<ChartOfAccount> findByTenantIdOrderByAccountCodeAsc(UUID tenantId);
    Optional<ChartOfAccount> findByTenantIdAndAccountCode(UUID tenantId, String accountCode);
    boolean existsByTenantId(UUID tenantId);
    boolean existsByTenantIdAndAccountCode(UUID tenantId, String accountCode);
    void deleteByTenantIdAndAccountCode(UUID tenantId, String accountCode);
}
