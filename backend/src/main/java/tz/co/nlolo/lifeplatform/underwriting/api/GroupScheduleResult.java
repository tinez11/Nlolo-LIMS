package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.List;

/**
 * A group funeral schedule uploaded onto a proposal (2026-10-07): replaced when every row reads and every family
 * passes the plan's rules ({@code accepted}), left as it was otherwise -- with every problem, each naming its row or
 * its member, so the association corrects the file in one pass.
 */
public record GroupScheduleResult(boolean accepted, int families, int lives, List<String> problems) {

    public GroupScheduleResult {
        problems = problems != null ? List.copyOf(problems) : List.of();
    }
}
