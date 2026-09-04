package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.FieldReceiptView;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One field receipt on the wire, and one page of them.
 *
 * <p>Named for the QUEUE rather than the receipt because {@code FieldReceiptResponseDto} already
 * exists next to this and is the CAPTURE response -- a different shape for a different job (it
 * answers a POST with an id and a status). Two DTOs named the same thing a directory apart is how
 * somebody ends up returning the wrong one.
 *
 * <p>Money is the platform's usual {@code {amount, currencyCode}} with a string amount. The page
 * envelope is the shared one.
 */
public record FieldReceiptQueueResponseDto(
    UUID receiptId,
    String policyNumber,
    UUID agentId,
    MoneyDto amount,
    Instant capturedAtClient,
    Instant capturedAtServer,
    String status,
    Instant reconciledAt) {

    public record MoneyDto(String amount, String currencyCode) {}

    public record PageResponse(List<FieldReceiptQueueResponseDto> items, PageMetaDto page) {

        public record PageMetaDto(int page, int pageSize, int totalElements) {}

        public static PageResponse from(Page<FieldReceiptView> springPage) {
            return new PageResponse(
                springPage.getContent().stream().map(FieldReceiptQueueResponseDto::from).toList(),
                new PageMetaDto(springPage.getNumber(), springPage.getSize(),
                    (int) springPage.getTotalElements()));
        }
    }

    public static FieldReceiptQueueResponseDto from(FieldReceiptView view) {
        return new FieldReceiptQueueResponseDto(
            view.receiptId(), view.policyNumber(), view.agentId(),
            view.amount() != null ? new MoneyDto(view.amount().toPlainString(), view.currency()) : null,
            view.capturedAtClient(), view.capturedAtServer(), view.status(), view.reconciledAt());
    }
}
