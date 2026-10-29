package tz.co.nlolo.lifeplatform.payment.infrastructure;

import org.springframework.data.domain.Page;
import tz.co.nlolo.lifeplatform.payment.api.PaymentRecordView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of {@code GET /payments}: money in or out, its money a decimal string (2026-10-27). */
public record PaymentRegisterResponseDto(UUID id, String direction, String purpose, String method, String status,
                                         MoneyDto amount, String counterparty, String reference, String sourceRef,
                                         String policyNumber, Instant createdAt, String executedBy,
                                         Instant executedAt) {

    public record PageResponse(List<PaymentRegisterResponseDto> items, PageMetaDto page) {

        public record PageMetaDto(int page, int pageSize, int totalElements) {}

        public static PageResponse from(Page<PaymentRecordView> springPage) {
            return new PageResponse(springPage.getContent().stream().map(PaymentRegisterResponseDto::from).toList(),
                new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
        }
    }

    static PaymentRegisterResponseDto from(PaymentRecordView v) {
        return new PaymentRegisterResponseDto(v.id(), v.direction(), v.purpose(), v.method(), v.status(),
            new MoneyDto(v.amount().toPlainString(), v.currency()), v.counterparty(), v.reference(), v.sourceRef(),
            v.policyNumber(), v.createdAt(), v.executedBy(), v.executedAt());
    }
}
