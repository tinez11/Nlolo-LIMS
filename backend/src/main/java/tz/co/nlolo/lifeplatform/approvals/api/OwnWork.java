package tz.co.nlolo.lifeplatform.approvals.api;

/**
 * Acting alone where two people are the rule (2026-11-04, the user: "some companies only have one underwriter or
 * finance", so to decide and to accept can be one person if they hold the permission).
 *
 * <p>Every two-person check on the platform asks this before refusing: a person holding the area's "approve their own
 * work" permission may do it, and is recorded doing it. Nobody holds those permissions unless an administrator gives
 * them. Changing one's own access is not among them: that stays two people whatever anyone holds.
 */
public interface OwnWork {

    /** The event every act done alone is journalled as, so an auditor lists them in one place. */
    String EVENT = "access.ActedAlone";

    /** Whether the signed-in person holds {@code permission} -- one of the areas' "approve their own work". */
    boolean mayActAlone(String permission);

    /**
     * Journal an act done alone: what it was, its record, and who both prepared and approved it. Published in the
     * caller's transaction, so it is recorded only if the act is.
     *
     * @param what      in words: "Manual journal approved", "Claim settlement decided"
     * @param reference the record's own id
     * @param by        the person, who prepared it and approved it
     */
    void record(String what, String reference, String policyNumber, String by);
}
