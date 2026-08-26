package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.springframework.data.domain.Page;

import java.util.List;

public record UnderwritingCaseSearchResponse(List<UnderwritingCaseView> items, UnderwritingCaseSearchResponse.PageMetaDto page) {
    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static UnderwritingCaseSearchResponse from(Page<UnderwritingCaseView> springPage) {
        return new UnderwritingCaseSearchResponse(
            springPage.getContent(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
