package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Name and description. Everything else about an account -- its code, its derived type and
 *  normal balance, its parent and level -- is fixed once created. */
public record UpdateAccountRequestDto(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description) {}
