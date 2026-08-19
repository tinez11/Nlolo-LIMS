package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of journal entries. Field-for-field the same envelope {@code ClaimSearchResponseDto},
 * {@code PolicySearchResponseDto} and {@code GroupMembershipSearchResponseDto} already use, and its
 * {@code page} object maps onto {@code openapi-common.yaml}'s shared {@code PageMeta} schema --
 * {@code GET /gl-postings} was the one list endpoint on the platform still answering with a bare
 * unbounded array (M9 final review, finding I3).
 */
public record JournalEntrySearchResponseDto(List<JournalEntryResponseDto> items, PageMetaDto page) {

    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static JournalEntrySearchResponseDto from(Page<JournalEntryView> springPage) {
        return new JournalEntrySearchResponseDto(
            springPage.getContent().stream().map(JournalEntryResponseDto::from).toList(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
