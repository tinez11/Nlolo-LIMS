package tz.co.nlolo.lifeplatform.refdata.infrastructure;

import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;

import java.util.List;

public record ReferenceCodeSetResponseDto(String codeSetKey, List<ReferenceCodeEntryDto> values) {

    /** {@code value} stays a String even when it holds "24" or "12.0" -- the column is
     * VARCHAR(255) and its meaning varies per key, so coercing to a number would be wrong for
     * POLICY_SUSPENSION_ELIGIBLE_CATEGORIES and lossy for the rest. */
    public record ReferenceCodeEntryDto(String code, String label, String value, String jurisdiction) {
        static ReferenceCodeEntryDto from(ReferenceCodeView view) {
            return new ReferenceCodeEntryDto(view.code(), view.label(), view.value(), view.jurisdiction());
        }
    }

    public static ReferenceCodeSetResponseDto of(String codeSetKey, List<ReferenceCodeView> views) {
        return new ReferenceCodeSetResponseDto(codeSetKey,
            views.stream().map(ReferenceCodeEntryDto::from).toList());
    }
}
