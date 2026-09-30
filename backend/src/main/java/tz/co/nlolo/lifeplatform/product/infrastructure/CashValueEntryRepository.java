package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.CashValueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashValueEntryRepository extends JpaRepository<CashValueEntry, UUID> {

    List<CashValueEntry> findByProductVersionId(UUID productVersionId);

    /**
     * The scale that applies at {@code policyYear} for a life entering at {@code age}: the row for
     * the GREATEST tabulated year at or below {@code policyYear} whose age band contains the age (or
     * the unbanded row for that year). A surrender/cash-value table is a step function tabulated at
     * points -- a policy in year 7 with rows at 5 and 10 reads the year-5 step, not nothing.
     *
     * <p>Overlap is refused at publish, so within a year at most one age row matches. Caller takes
     * the first of the page.
     */
    @Query("""
        select c from CashValueEntry c
        where c.productVersionId = :productVersionId
          and c.policyYear <= :policyYear
          and (c.ageFrom is null or :age between c.ageFrom and c.ageTo)
        order by c.policyYear desc
        """)
    List<CashValueEntry> findApplicableRows(@Param("productVersionId") UUID productVersionId,
                                            @Param("policyYear") int policyYear,
                                            @Param("age") Integer ageAtEntry,
                                            Pageable pageable);

    default Optional<CashValueEntry> findApplicable(UUID productVersionId, int policyYear, Integer ageAtEntry) {
        return findApplicableRows(productVersionId, policyYear, ageAtEntry, Pageable.ofSize(1)).stream().findFirst();
    }
}
