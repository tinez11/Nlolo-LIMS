package tz.co.nlolo.lifeplatform.approvals.api;

import java.util.Collection;
import java.util.Map;

/**
 * Whose policy a waiting row is on (2026-11-09, the user: the approvals page "doesn't show more details"). Implemented
 * by the module that holds policies, which already depends on this one -- approvals never reads policies itself, so
 * there is no cycle.
 */
public interface PolicyHolders {

    /** The policyholder's display name for each of these policy numbers that has one; others are left out. */
    Map<String, String> names(Collection<String> policyNumbers);
}
