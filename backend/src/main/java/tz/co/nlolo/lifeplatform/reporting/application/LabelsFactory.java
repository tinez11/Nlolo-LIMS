package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

@Component
public class LabelsFactory {

    private final ProductApi productApi;
    private final DistributionApi distributionApi;
    private final PartyApi partyApi;
    private final OrganisationApi organisationApi;

    public LabelsFactory(ProductApi productApi, DistributionApi distributionApi, PartyApi partyApi,
                         OrganisationApi organisationApi) {
        this.productApi = productApi;
        this.distributionApi = distributionApi;
        this.partyApi = partyApi;
        this.organisationApi = organisationApi;
    }

    public Labels create() {
        return new Labels(productApi, distributionApi, partyApi, organisationApi);
    }
}
