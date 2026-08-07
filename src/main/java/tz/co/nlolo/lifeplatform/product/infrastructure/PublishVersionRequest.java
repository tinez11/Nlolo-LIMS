package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;

import java.time.LocalDate;
import java.util.List;

public record PublishVersionRequest(IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                     List<RatingFactorRequest> ratingTable, List<BenefitRequest> benefitSchedule,
                                     List<FundDefinitionRequest> fundDefinitions) {}
