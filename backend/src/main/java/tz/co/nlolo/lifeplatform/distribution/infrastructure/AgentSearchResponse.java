package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.data.domain.Page;

import java.util.List;

/** Mirrors UnderwritingCaseSearchResponse and PolicySearchResponse -- the platform's
 *  paged `{items, page}` envelope, not a third convention. */
public record AgentSearchResponse(List<AgentResponseDto> items, AgentSearchResponse.PageMetaDto page) {
    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static AgentSearchResponse from(Page<tz.co.nlolo.lifeplatform.distribution.api.AgentView> springPage) {
        return new AgentSearchResponse(
            springPage.getContent().stream().map(AgentResponseDto::from).toList(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
