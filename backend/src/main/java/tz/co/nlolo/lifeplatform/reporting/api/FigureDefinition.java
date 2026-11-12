package tz.co.nlolo.lifeplatform.reporting.api;

/**
 * One figure explained (PRD 19, decision K1, 2026-11-11): read-only, written beside the code that works the figure out,
 * so a change to the formula is a code change that must change these words too -- the same rule as posting rules (D10).
 *
 * @param meaning   one plain sentence: what the figure is
 * @param counts    what is added up, and how
 * @param leavesOut what is deliberately not in it
 * @param comesFrom where the numbers are read from
 * @param dateBasis which date decides whether something falls in the period
 */
public record FigureDefinition(String key, String name, String meaning, String counts, String leavesOut,
                               String comesFrom, String dateBasis) {}
