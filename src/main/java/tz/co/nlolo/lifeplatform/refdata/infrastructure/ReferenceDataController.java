package tz.co.nlolo.lifeplatform.refdata.infrastructure;

import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeSetNotFoundException;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads one reference code set.
 *
 * <p>{@code refdata.reference_code_set} is deliberately NOT tenant-scoped and carries no RLS
 * policy -- refdata/V1's own header says so ("there is no tenant to isolate") -- so there is no
 * tenant check here, by design.
 *
 * <p><b>But "global" is not "readable by every realm."</b> Two of the nine seeded keys are
 * commercially sensitive: TZ_BASE_PREMIUM_RATE_PER_MILLE is the premium pricing basis, and
 * TZ_COMMISSION_CLAWBACK_MONTHS is agent commercial terms. The Phase 0 draft spec carried a
 * blanket customers/agents/staff security block, which would have served the pricing basis to any
 * customer token. Hence an explicit per-realm allowlist, DEFAULTING TO DENY: a newly seeded key is
 * invisible to every non-staff realm until someone deliberately adds it, which is the right
 * default for a table that grows by migration and whose sensitivity varies per row.
 *
 * <p>A denied key returns 404 identical to a nonexistent key, so the allowlist cannot be used to
 * enumerate which keys exist -- the same principle DocumentApiImpl.findOrThrow applies to
 * cross-tenant document refs.
 */
@RestController
public class ReferenceDataController {

    /** Disclosed in policy terms or directly charged to the customer -- safe for every realm. */
    private static final Set<String> PUBLICLY_DISCLOSED = Set.of(
        "TZ_CONTESTABILITY_MONTHS",
        "TZ_REINSTATEMENT_WINDOW_MONTHS",
        "TZ_SUSPENSION_TO_LAPSE_MONTHS",
        "TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE",
        "DUNNING_ESCALATION_DAYS");

    /** Operational parameters an agent needs but a policyholder has no business reading. */
    private static final Set<String> AGENT_OPERATIONAL = Set.of(
        "OFFLINE_RECEIPT_SLA_HOURS",
        "POLICY_SUSPENSION_ELIGIBLE_CATEGORIES",
        "TZ_COMMISSION_CLAWBACK_MONTHS");

    private static final Map<String, Set<String>> READABLE_BY_REALM = Map.of(
        "ROLE_REALM_CUSTOMERS", PUBLICLY_DISCLOSED,
        "ROLE_REALM_REGULATORS", PUBLICLY_DISCLOSED,
        "ROLE_REALM_AGENTS", union(PUBLICLY_DISCLOSED, AGENT_OPERATIONAL),
        "ROLE_REALM_STAFF", Set.of());   // sentinel: staff read everything, see mayRead

    private final ReferenceDataApi referenceDataApi;

    public ReferenceDataController(ReferenceDataApi referenceDataApi) {
        this.referenceDataApi = referenceDataApi;
    }

    @GetMapping("/reference-codes/{codeSetKey}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF') or hasRole('REALM_REGULATORS')")
    public ResponseEntity<ReferenceCodeSetResponseDto> getCodeSet(@PathVariable String codeSetKey,
            Authentication authentication) {
        if (!mayRead(codeSetKey, authentication)) {
            // Identical exception AND identical message to the not-found path below, deliberately:
            // if a denied key were distinguishable from an unknown one, this endpoint would become
            // an oracle for which keys exist.
            throw new ReferenceCodeSetNotFoundException("No reference code set found for key " + codeSetKey);
        }
        List<ReferenceCodeView> values = referenceDataApi.getCodes(codeSetKey);
        if (values.isEmpty()) {
            throw new ReferenceCodeSetNotFoundException("No reference code set found for key " + codeSetKey);
        }
        return ResponseEntity.ok(ReferenceCodeSetResponseDto.of(codeSetKey, values));
    }

    private static boolean mayRead(String codeSetKey, Authentication authentication) {
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String authority = granted.getAuthority();
            if ("ROLE_REALM_STAFF".equals(authority)) {
                return true;
            }
            Set<String> readable = READABLE_BY_REALM.get(authority);
            if (readable != null && readable.contains(codeSetKey)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
