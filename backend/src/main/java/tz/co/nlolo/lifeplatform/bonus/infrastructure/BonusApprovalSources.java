package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

/** A bonus declaration waiting for its second signature (2026-10-27). */
@Configuration
class BonusApprovalSources {

    @Bean
    ApprovalSource bonusDeclarationApprovals(DeclarationRepository declarations, ProductApi products) {
        return ApprovalSource.of("bonus-declaration", "Bonus declarations", "Approve", "products.declare-approve",
            limit -> ApprovalSource.Pending.of(declarations.findByStatusOrderByProposedAtAsc("PROPOSED").stream()
                .map(d -> new ApprovalSource.Item(d.getProductId().toString(), null,
                    products.getProduct(d.getProductId()).productName() + ": reversionary "
                        + d.getReversionaryRatePercent().stripTrailingZeros().toPlainString() + "%, valued "
                        + ApprovalSource.day(d.getValuationDate()), null, null, d.getProposedBy(), d.getProposedAt()))
                .toList(), limit));
    }
}
