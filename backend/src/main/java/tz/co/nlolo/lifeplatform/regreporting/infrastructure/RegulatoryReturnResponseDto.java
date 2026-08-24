package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.api.RegulatoryReturnView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A generated return and its lines, on the wire. {@code documentRef} is always null: rendering a
 * submission artifact requires TIRA's file format, which is C2-blocked (see
 * {@link RegulatoryReturnView}'s javadoc) -- exposed rather than omitted, so a consumer sees the
 * field exists and is unpopulated instead of discovering later it was silently left out.
 */
public record RegulatoryReturnResponseDto(UUID returnId, String returnType, String period, String status,
                                           String documentRef, Instant generatedAt, String generatedBy,
                                           List<ReturnLineResponseDto> lines) {

    public static RegulatoryReturnResponseDto from(RegulatoryReturnView view) {
        return new RegulatoryReturnResponseDto(view.returnId(), view.returnType(), view.period(), view.status(),
            view.documentRef(), view.generatedAt(), view.generatedBy(),
            view.lines().stream().map(ReturnLineResponseDto::from).toList());
    }
}
