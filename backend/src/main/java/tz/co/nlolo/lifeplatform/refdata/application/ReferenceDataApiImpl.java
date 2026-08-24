package tz.co.nlolo.lifeplatform.refdata.application;

import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.refdata.domain.ReferenceCodeSet;
import tz.co.nlolo.lifeplatform.refdata.infrastructure.ReferenceCodeSetRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * NON-PRODUCTION NOTICE: four seeded code_set_keys --
 * TZ_CONTESTABILITY_MONTHS, TZ_REINSTATEMENT_WINDOW_MONTHS,
 * TZ_SUSPENSION_TO_LAPSE_MONTHS, OFFLINE_RECEIPT_SLA_HOURS -- are explicit
 * PLACEHOLDER values (db-migrations/refdata/V1__create_refdata_schema.sql),
 * pending Legal/Product/Actuarial/Operational sign-off per
 * docs/03-aggregate-design.md Rev 2 §13 item 4 and
 * docs/06-database-schema.md §6 item 5. Callers must not treat values
 * returned for these four keys as statutorily or operationally confirmed.
 */
@Service
public class ReferenceDataApiImpl implements ReferenceDataApi {

    private final ReferenceCodeSetRepository repository;

    public ReferenceDataApiImpl(ReferenceCodeSetRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ReferenceCodeView> getCodes(String codeSetKey) {
        return repository.findByCodeSetKeyOrderByCode(codeSetKey).stream()
            .map(e -> new ReferenceCodeView(e.getCode(), e.getLabel(), e.getValue(), e.getJurisdiction()))
            .toList();
    }

    @Override
    public String getValue(String codeSetKey, String jurisdiction) {
        return repository.findByCodeSetKeyAndJurisdictionOrderByCode(codeSetKey, jurisdiction).stream()
            .filter(e -> "DEFAULT".equals(e.getCode()))
            .map(ReferenceCodeSet::getValue)
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException(
                "No refdata value for codeSetKey=" + codeSetKey + " jurisdiction=" + jurisdiction));
    }
}
