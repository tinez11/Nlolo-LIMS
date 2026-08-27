package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import org.springframework.data.domain.Page;

import java.util.List;

/** The platform's paged `{items, page}` envelope, as used by policy, underwriting
 *  and distribution -- not a fourth convention. */
public record AuditLogSearchResponse(List<AuditEntryView> items, AuditLogSearchResponse.PageMetaDto page) {
    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static AuditLogSearchResponse from(Page<AuditEntryView> springPage) {
        return new AuditLogSearchResponse(springPage.getContent(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
