package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.ArrearsCaseView;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One arrears case on the wire, and one page of them.
 *
 * <p>Money is a nested {@code {amount, currencyCode}} object with the amount as a STRING, which
 * is this platform's money contract everywhere: a JSON number would hand a premium to a
 * consumer's float. It is null rather than zero when the invoice behind the case cannot be
 * resolved -- a zero on a collections screen reads as "nothing owed".
 *
 * <p>The page envelope is field-for-field the one {@code JournalEntrySearchResponseDto} and the
 * claim/policy searches already use, mapping onto {@code openapi-common.yaml}'s shared
 * {@code PageMeta}.
 */
public record ArrearsCaseResponseDto(
    UUID arrearsCaseId,
    String policyNumber,
    UUID invoiceId,
    int dunningLevel,
    int lastNotifiedDunningLevel,
    Instant openedAt,
    Instant resolvedAt,
    MoneyDto amount,
    LocalDate dueDate,
    String invoiceStatus) {

    public record MoneyDto(String amount, String currencyCode) {}

    public record PageResponse(List<ArrearsCaseResponseDto> items, PageMetaDto page) {

        public record PageMetaDto(int page, int pageSize, int totalElements) {}

        public static PageResponse from(Page<ArrearsCaseView> springPage) {
            return new PageResponse(
                springPage.getContent().stream().map(ArrearsCaseResponseDto::from).toList(),
                new PageMetaDto(springPage.getNumber(), springPage.getSize(),
                    (int) springPage.getTotalElements()));
        }
    }

    public static ArrearsCaseResponseDto from(ArrearsCaseView view) {
        return new ArrearsCaseResponseDto(
            view.arrearsCaseId(), view.policyNumber(), view.invoiceId(),
            view.dunningLevel(), view.lastNotifiedDunningLevel(),
            view.openedAt(), view.resolvedAt(),
            view.amount() != null ? new MoneyDto(view.amount().toPlainString(), view.currency()) : null,
            view.dueDate(),
            view.invoiceStatus() != null ? view.invoiceStatus().name() : null);
    }
}
