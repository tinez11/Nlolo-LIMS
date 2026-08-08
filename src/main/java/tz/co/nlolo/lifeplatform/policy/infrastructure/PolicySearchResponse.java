package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import org.springframework.data.domain.Page;

import java.util.List;

public record PolicySearchResponse(List<PolicyResponseDto> items, PolicySearchResponse.PageMetaDto page) {
    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static PolicySearchResponse from(Page<PolicyView> springPage) {
        return new PolicySearchResponse(
            springPage.getContent().stream().map(PolicyResponseDto::from).toList(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
