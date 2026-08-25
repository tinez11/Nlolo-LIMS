package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameAccountRequestDto(@NotBlank @Size(max = 200) String name) {}
