package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.util.Set;

/**
 * The IFRS 17 posting guide's movement types (2.4): the tag on every line posted to 2121-2132 that keeps premiums,
 * penalties, charges and commission visible to management although IFRS 17 has no income line for them. A rule may
 * name only these.
 */
public final class MovementTypes {

    private MovementTypes() {}

    public static final Set<String> CODES = Set.of(
        "PRM_NB", "PRM_REN", "PRM_SGL", "PRM_TOP", "PRM_RID",
        "FEE_POL", "FEE_ALT", "FEE_LOAN",
        "PEN_LATE", "PEN_REV",
        "LN_INT", "LN_PEN",
        "IACF_COM", "IACF_BRK", "IACF_BANC", "IACF_MED", "IACF_OVR", "IACF_CLAW",
        "IC_MAT", "IC_SUR", "IC_WDL", "IC_DTH", "IC_SB",
        "UL_ALLOC", "UL_ADM", "UL_FMC", "UL_MORT", "UL_SURCH", "UL_SWITCH");
}
