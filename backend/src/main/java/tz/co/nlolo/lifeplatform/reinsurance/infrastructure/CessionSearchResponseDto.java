package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.CessionView;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of a treaty's cessions.
 *
 * <p>The same {@code {items, page}} envelope every other paged search on this platform uses, so a
 * caller normalising one of them normalises all of them.
 *
 * <p>Paged only on the TREATY path. {@code GET /policies/{n}/cessions} stays a bare array: a
 * policy has a handful of cessions and a pager over a fully-downloaded array lies about the
 * network. A treaty accumulates one per policy it covers for as long as it runs, which is the
 * unbounded case.
 */
public record CessionSearchResponseDto(List<CessionResponseDto> items, PageMetaDto page) {

    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static CessionSearchResponseDto from(Page<CessionView> springPage) {
        return new CessionSearchResponseDto(
            springPage.getContent().stream().map(CessionResponseDto::from).toList(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(),
                (int) springPage.getTotalElements()));
    }
}
