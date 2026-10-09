package tz.co.nlolo.lifeplatform.product.api;

import java.util.List;
import java.util.UUID;

/**
 * A product as the customer portal offers it (2026-10-08, the customer portal design step 5; product V31).
 *
 * @param available whether customers see it at all
 * @param summary what it is for, in a sentence; required while available
 * @param benefits its key benefits, in order
 */
public record OnlineListingView(UUID productId, String productName, ProductCategory category, String defaultCurrency,
                                boolean available, String summary, List<String> benefits) {}
