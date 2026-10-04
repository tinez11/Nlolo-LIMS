package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.annuity.domain.Vesting;

import java.time.LocalDate;
import java.util.List;

public interface VestingRepository extends JpaRepository<Vesting, String> {

    /**
     * Every held vesting in the tenant, oldest hold first. The tenant is filtered explicitly, not
     * left to RLS: a connection that owns the table bypasses RLS (D1's finding), so a tenant-wide
     * read must say whose rows it wants.
     */
    List<Vesting> findByTenantIdAndHoldReasonIsNotNullOrderByHeldAtAsc(java.util.UUID tenantId);

    /** [policy_number, tenant_id] due to vest on or before {@code asOf}, across tenants (annuity V2). */
    @Query(value = "SELECT policy_number, tenant_id FROM annuity.vestings_due(:asOf)", nativeQuery = true)
    List<Object[]> vestingsDue(@org.springframework.data.repository.query.Param("asOf") LocalDate asOf);

    /** [policy_number, tenant_id] whose vesting date is within 90 days, across tenants (annuity V2). */
    @Query(value = "SELECT policy_number, tenant_id FROM annuity.vesting_reminders_due(:asOf)", nativeQuery = true)
    List<Object[]> remindersDue(@org.springframework.data.repository.query.Param("asOf") LocalDate asOf);
}
