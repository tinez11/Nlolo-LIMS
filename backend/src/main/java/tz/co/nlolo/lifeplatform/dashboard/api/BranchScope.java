package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;

/**
 * The branch a dashboard was cut by (2026-11-23, branch views); a view's {@code branch} is null for every branch.
 *
 * @param code        the branch's code, as the reports' copy records a policy's branch
 * @param name        its name from the company's register, the code when it is not there
 * @param allBranches the keys of the figures that could NOT be cut by branch and still cover every branch -- the
 *                    console says "All branches" under each, rather than showing the company's figure as the branch's
 */
public record BranchScope(String code, String name, List<String> allBranches) {
    public BranchScope {
        allBranches = allBranches == null ? List.of() : List.copyOf(allBranches);
    }
}
