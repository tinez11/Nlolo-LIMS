/**
 * A precondition on an action, evaluated and explained.
 *
 * The console's business rules used to live inline in page JSX, where the only
 * way to test one was to render a page. A gate is a plain value instead: the
 * rule set for a screen is a pure function of already-fetched data, so it can be
 * exercised against fixtures with no DOM, and one component renders any of them.
 */
export interface Gate {
  ok: boolean;
  /**
   * A hard gate failing means the action is wrong, not merely questionable — the
   * platform will refuse it, so the UI should too.
   *
   * A soft gate failing is a flag: it routes the case for a human look without
   * blocking it. The distinction matters most in claims, where "the policy had
   * lapsed by the date of event" must NOT auto-refuse a claim — it must send it
   * to investigation, because refusing without verifying the lapse notices were
   * provably sent is how an insurer ends up in front of a regulator.
   */
  hard: boolean;
  title: string;
  detail: string;
}
