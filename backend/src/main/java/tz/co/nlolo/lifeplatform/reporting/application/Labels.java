package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Names for ids, resolved through the owning modules' APIs and cached for one report run. A name that cannot
 * be resolved falls back to the id: a label is cosmetic, and a report must not fail because a product was
 * deleted or an agent record moved.
 */
public class Labels {

    static final String DIRECT = "Direct (no agent)";
    static final String UNKNOWN_PRODUCT = "Unknown product";

    private final ProductApi productApi;
    private final DistributionApi distributionApi;
    private final PartyApi partyApi;
    private final Map<UUID, String> products = new HashMap<>();
    private final Map<UUID, String> agents = new HashMap<>();

    Labels(ProductApi productApi, DistributionApi distributionApi, PartyApi partyApi) {
        this.productApi = productApi;
        this.distributionApi = distributionApi;
        this.partyApi = partyApi;
    }

    public String product(UUID productId) {
        if (productId == null || ReportingEventHandlers.UNKNOWN_PRODUCT.equals(productId)) return UNKNOWN_PRODUCT;
        return products.computeIfAbsent(productId, id -> {
            try {
                return productApi.getProduct(id).productName();
            } catch (RuntimeException e) {
                return id.toString();
            }
        });
    }

    public String agent(UUID agentId) {
        if (agentId == null) return DIRECT;
        return agents.computeIfAbsent(agentId, id -> {
            try {
                AgentView agent = distributionApi.getAgent(id);
                return partyApi.getParty(agent.partyId()).displayName() + " (" + agent.licenseNumber() + ")";
            } catch (RuntimeException e) {
                return id.toString();
            }
        });
    }

    /** A code as a reader writes it: {@code PAID_UP} reads "Paid up", {@code MONTHLY} "Monthly". Blank for none --
     * the console shows its own dash, and an export cell stays empty rather than saying "null". */
    public static String words(String code) {
        if (code == null || code.isBlank()) return "";
        String spaced = code.trim().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    public String dimension(GroupBy groupBy, String raw) {
        return switch (groupBy) {
            case PRODUCT -> product(UUID.fromString(raw));
            case AGENT -> "DIRECT".equals(raw) ? DIRECT : agent(UUID.fromString(raw));
            default -> raw;
        };
    }
}
