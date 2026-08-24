package tz.co.nlolo.lifeplatform.billing.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WaiverRequestDto(@NotBlank @Size(min = 10) String reason) {}
