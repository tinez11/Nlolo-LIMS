package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.CommissionRuleView;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;

import java.util.UUID;

/**
 * <b>{@code rate} is a decimal STRING on the wire, not a JSON number.</b> openapi-distribution.yaml
 * declared it {@code type: number} until M7; docs/06-database-schema.md:31 forbids floats "anywhere
 * in the stack, wire format or storage", and M6 fixed this exact class of defect for
 * {@code DisabilityClaimDetails.impairmentPercent} -- a {@code type: string} field emitted as a
 * bare JSON number, which {@code openApi().isValid()} does NOT catch (measured). A rate like
 * {@code 0.0725} is precisely the value a float round-trip corrupts, and it multiplies money.
 *
 * <p>Exactly one of {@code rate}/{@code flatAmount} is non-null -- the domain's rate-XOR-flat
 * invariant, enforced by {@code commission_rule_rate_xor_flat}.
 */
public record CommissionRuleDto(UUID commissionRuleId, TierType tierType, String rate, MoneyDto flatAmount) {

    public static CommissionRuleDto from(CommissionRuleView view) {
        return new CommissionRuleDto(
            view.commissionRuleId(),
            view.tierType(),
            view.rate() == null ? null : view.rate().toPlainString(),
            view.flatAmount() == null ? null : new MoneyDto(view.flatAmount().toPlainString(), view.flatCurrency()));
    }
}
