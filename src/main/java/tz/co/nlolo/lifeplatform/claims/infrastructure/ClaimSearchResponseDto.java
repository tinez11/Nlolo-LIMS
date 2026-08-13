package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import org.springframework.data.domain.Page;

import java.util.List;

public record ClaimSearchResponseDto(List<ClaimResponseDto> items, PageMetaDto page) {

    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static ClaimSearchResponseDto from(Page<ClaimView> springPage) {
        return new ClaimSearchResponseDto(
            springPage.getContent().stream().map(ClaimResponseDto::from).toList(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
