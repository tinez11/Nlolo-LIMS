package tz.co.nlolo.lifeplatform.party.infrastructure;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(List<T> items, PageMeta page) {

    public record PageMeta(int page, int pageSize, long totalElements) {}

    public static <T> PageResponse<T> from(Page<T> springPage) {
        return new PageResponse<>(springPage.getContent(),
            new PageMeta(springPage.getNumber(), springPage.getSize(), springPage.getTotalElements()));
    }
}
