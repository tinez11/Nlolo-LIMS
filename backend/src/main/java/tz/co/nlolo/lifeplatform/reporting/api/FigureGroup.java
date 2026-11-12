package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/**
 * Figure definitions not tied to one report (2026-11-11, analytics plan 2): the Collections figures shared by the
 * follow-up list and the finance dashboard.
 */
public record FigureGroup(String title, List<FigureDefinition> figures) {
    public FigureGroup {
        figures = List.copyOf(figures);
    }
}
