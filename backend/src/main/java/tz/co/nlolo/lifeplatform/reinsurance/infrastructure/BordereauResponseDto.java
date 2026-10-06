package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.BordereauView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A bordereau on the wire: amounts as decimal STRINGS in {@code currency}, never JSON numbers (docs/06:32). */
public record BordereauResponseDto(UUID bordereauId, UUID treatyId, String period, String currency, int policyCount,
                                   String premium, String commission, String recoveries, Instant createdAt,
                                   List<LineDto> lines) {

    public record LineDto(String type, String policyNumber, UUID claimId, String premiumShare, String policyPremium,
                          String premium, String commission, String recovery) {}

    public static BordereauResponseDto from(BordereauView v) {
        return new BordereauResponseDto(v.bordereauId(), v.treatyId(), v.period(), v.currency(), v.policyCount(),
            plain(v.premium()), plain(v.commission()), plain(v.recoveries()), v.createdAt(),
            v.lines().stream().map(l -> new LineDto(l.type(), l.policyNumber(), l.claimId(), plain(l.premiumShare()),
                plain(l.policyPremium()), plain(l.premium()), plain(l.commission()), plain(l.recovery()))).toList());
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
