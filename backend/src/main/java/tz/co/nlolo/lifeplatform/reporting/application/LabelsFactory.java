package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

@Component
public class LabelsFactory {

    private final ProductApi productApi;
    private final DistributionApi distributionApi;
    private final PartyApi partyApi;

    public LabelsFactory(ProductApi productApi, DistributionApi distributionApi, PartyApi partyApi) {
        this.productApi = productApi;
        this.distributionApi = distributionApi;
        this.partyApi = partyApi;
    }

    public Labels create() {
        return new Labels(productApi, distributionApi, partyApi);
    }
}
