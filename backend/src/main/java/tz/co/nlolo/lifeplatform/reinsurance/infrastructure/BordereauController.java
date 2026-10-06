package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The monthly bordereaux (IFRS 17 I3c): what each treaty was charged month by month, and the current month as it
 * would be written now. Read-only -- the month-end job writes them. Same FINANCE_OFFICER/ADMIN gate as
 * {@code TreatyController}.
 */
@RestController
public class BordereauController {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final ReinsuranceApi reinsuranceApi;

    public BordereauController(ReinsuranceApi reinsuranceApi) {
        this.reinsuranceApi = reinsuranceApi;
    }

    @GetMapping("/treaties/{treatyId}/bordereaux")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<List<BordereauResponseDto>> listBordereaux(@PathVariable UUID treatyId) {
        return ResponseEntity.ok(reinsuranceApi.listBordereaux(treatyId).stream().map(BordereauResponseDto::from).toList());
    }

    /** {@code period} YYYY-MM; the current month (Dar es Salaam) when absent. */
    @GetMapping("/treaties/{treatyId}/bordereau-preview")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<BordereauResponseDto> previewBordereau(@PathVariable UUID treatyId,
            @RequestParam(required = false) String period) {
        YearMonth month;
        try {
            month = period == null ? YearMonth.from(LocalDate.now(CIVIL)) : YearMonth.parse(period);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("period must be YYYY-MM, got " + period);
        }
        return ResponseEntity.ok(BordereauResponseDto.from(reinsuranceApi.previewBordereau(treatyId, month)));
    }

    @GetMapping("/bordereaux/{bordereauId}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<BordereauResponseDto> getBordereau(@PathVariable UUID bordereauId) {
        return ResponseEntity.ok(BordereauResponseDto.from(reinsuranceApi.getBordereau(bordereauId)));
    }
}
