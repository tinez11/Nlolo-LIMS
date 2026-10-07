package tz.co.nlolo.lifeplatform.policy.api;

import java.util.List;

/**
 * What a joining file did to a group funeral scheme (2026-10-07). A family is all or nothing: every family with no
 * problem joined; every family any row or rule refused is listed with each problem, by row where it has one. Problems
 * no family owns (the header, a row naming no member) refuse nothing else but are reported.
 */
public record GroupFuneralJoiningReport(List<Joined> joined, List<Refused> refused, List<String> fileProblems) {

    public record Joined(String memberReference, String mainMemberName, int lives) {}

    public record Refused(String memberReference, List<String> problems) {}
}
