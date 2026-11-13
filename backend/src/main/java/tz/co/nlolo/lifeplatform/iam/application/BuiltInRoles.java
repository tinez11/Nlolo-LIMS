package tz.co.nlolo.lifeplatform.iam.application;

import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static tz.co.nlolo.lifeplatform.iam.api.Permission.*;

/**
 * The ten roles every tenant starts with -- Keycloak's staff realm roles, carried over by name -- and what each
 * carries on day one (2026-10-26). An administrator may change any of them afterwards; these are only the seed.
 *
 * <p>Each mirrors what the role could do before the platform held permissions, with two deliberate changes the
 * user chose: every area now has its own "view", so a role sees only the areas its work needs; and the 55 acts any
 * staff member could perform (surrender, manual issue, withdrawals, loans, registering claims...) now belong to the
 * roles whose work they are -- customer service for servicing, underwriting for issuing.
 *
 * <p>Listed in precedence order: the console's user badge shows the first role a person holds.
 */
public final class BuiltInRoles {

    public record Definition(String code, String name, String description, Set<Permission> permissions) {}

    private static final Set<Permission> APPROVALS_ONLY_BY_APPROVER =
        EnumSet.of(LEDGER_APPROVE, IFRS17_APPROVE, REINSURANCE_APPROVE);

    /** Signatures that name a person's office (2026-11-12, findings 99 and 66): finance's and the actuary's, never an
     * administrator's. */
    private static final Set<Permission> SIGN_OFFS = EnumSet.of(LEDGER_BASELINE_SIGN_OFF, IFRS17_ACTUARY_SIGN_OFF);

    /**
     * Acting alone where two people are the rule (2026-11-04, the user: a company with one underwriter or one finance
     * person must still finish its work). No built-in role carries any of these; an administrator gives them to the
     * role or the person who works alone. Declared before {@link #ALL}, which reads it.
     */
    public static final Set<Permission> APPROVE_OWN_WORK = EnumSet.of(UNDERWRITING_DECIDE_OWN,
        POLICIES_SURRENDER_APPROVE_OWN, SCHEMES_ENROLMENTS_OWN, CLAIMS_SETTLE_OWN, ACCOUNTS_APPROVE_OWN,
        COLLECTIONS_APPROVE_OWN, PAYOUTS_APPROVE_OWN, LEDGER_APPROVE_OWN, IFRS17_APPROVE_OWN, REINSURANCE_APPROVE_OWN,
        FUNDS_APPROVE_OWN, PRODUCTS_DECLARE_APPROVE_OWN, COMMISSION_APPROVE_OWN);

    private static final Set<Permission> SPECIALIST_UNDERWRITING_AND_CLAIMS = EnumSet.of(
        UNDERWRITING_ASSESS, UNDERWRITING_DECIDE, UNDERWRITING_DECIDE_AGAINST, UNDERWRITING_REFER, SCHEMES_MANAGE,
        CLAIMS_ASSESS, CLAIMS_SETTLE);

    public static final List<Definition> ALL = List.of(
        new Definition("ADMIN", "Administrator",
            "Runs the platform: products, users and roles, and every finance surface. Does not underwrite, assess "
                + "claims or give the approver's second signature.",
            admin()),
        // Analytics plan 5, D1 (2026-11-12): sees every area and the overview, and changes nothing. Not Administration:
        // only administrators see who holds which access.
        new Definition("MANAGEMENT", "Management",
            "Sees every area, the reports and the management overview. Changes nothing.",
            seeingOnly()),
        new Definition("SENIOR_UNDERWRITER", "Senior underwriter",
            "Decides against an assessor's recommendation. Held alongside Underwriter.",
            EnumSet.of(UNDERWRITING_VIEW, UNDERWRITING_DECIDE_AGAINST, CLIENTS_VIEW, POLICIES_VIEW, PRODUCTS_VIEW)),
        new Definition("UNDERWRITER", "Underwriter",
            "Assesses and decides cases, underwrites group schemes, issues policies.",
            EnumSet.of(CLIENTS_VIEW, CLIENTS_EDIT, UNDERWRITING_VIEW, UNDERWRITING_PROPOSE, UNDERWRITING_ASSESS,
                UNDERWRITING_DECIDE, UNDERWRITING_REFER, POLICIES_VIEW, POLICIES_ISSUE, SCHEMES_VIEW, SCHEMES_MANAGE,
                SCHEMES_ENROLMENTS, CLAIMS_VIEW, PRODUCTS_VIEW)),
        // Not claims.assess: the assessor recommends and the manager decides, so the two signatures stay two people.
        new Definition("CLAIMS_MANAGER", "Claims manager",
            "Decides settlements and reopens claims; asks for documents and reads the assessments.",
            EnumSet.of(CLIENTS_VIEW, POLICIES_VIEW, SCHEMES_VIEW, CLAIMS_VIEW, CLAIMS_REGISTER, CLAIMS_SETTLE,
                ACCOUNTS_VIEW, PAYOUTS_VIEW, PRODUCTS_VIEW)),
        new Definition("CLAIMS_ASSESSOR", "Claims assessor",
            "Registers and assesses claims.",
            EnumSet.of(CLIENTS_VIEW, POLICIES_VIEW, SCHEMES_VIEW, CLAIMS_VIEW, CLAIMS_REGISTER, CLAIMS_ASSESS,
                ACCOUNTS_VIEW, PAYOUTS_VIEW, PRODUCTS_VIEW)),
        new Definition("FINANCE_APPROVER", "Finance approver",
            "The second signature on manual journals, IFRS 17 runs and reinsurance statements.",
            EnumSet.of(LEDGER_VIEW, LEDGER_APPROVE, LEDGER_BASELINE_SIGN_OFF, IFRS17_VIEW, IFRS17_APPROVE, REINSURANCE_VIEW,
                REINSURANCE_APPROVE,
                REPORTS_VIEW)),
        new Definition("FINANCE_OFFICER", "Finance officer",
            "Collections, payouts, the ledger, IFRS 17, reinsurance, returns, agents and funds.",
            finance()),
        // 2026-11-12, findings 99 and 66 (the user: "can add role actuary"): reads the books and IFRS 17, and co-signs
        // the accounting baseline. Changes nothing else.
        new Definition("ACTUARY", "Actuary",
            "Reads policies, products, the ledger and IFRS 17, and co-signs the accounting baseline.",
            EnumSet.of(POLICIES_VIEW, PRODUCTS_VIEW, LEDGER_VIEW, IFRS17_VIEW, IFRS17_ACTUARY_SIGN_OFF, REPORTS_VIEW)),
        new Definition("CUSTOMER_SERVICE_REP", "Customer service",
            "Registers and services clients and their policies: endorsements, surrenders, account transactions, "
                + "loans, claims registration, portal invitations.",
            EnumSet.of(CLIENTS_VIEW, CLIENTS_EDIT, CLIENTS_KYC, CLIENTS_PORTAL, UNDERWRITING_VIEW, UNDERWRITING_PROPOSE,
                POLICIES_VIEW, POLICIES_SERVICE, POLICIES_STATUS, POLICIES_SURRENDER, SCHEMES_VIEW, CLAIMS_VIEW,
                CLAIMS_REGISTER, ACCOUNTS_VIEW, ACCOUNTS_TRANSACT, LOANS_MANAGE, COLLECTIONS_VIEW, PAYOUTS_VIEW,
                PRODUCTS_VIEW, COMMUNICATIONS_VIEW)));

    private static Set<Permission> finance() {
        return EnumSet.of(CLIENTS_VIEW, POLICIES_VIEW, POLICIES_SURRENDER_APPROVE, SCHEMES_VIEW, CLAIMS_VIEW,
            ACCOUNTS_VIEW, ACCOUNTS_APPROVE, COLLECTIONS_VIEW, COLLECTIONS_MANAGE, COLLECTIONS_APPROVE, PAYOUTS_VIEW,
            PAYOUTS_MANAGE,
            PAYOUTS_APPROVE, LEDGER_VIEW, LEDGER_MANAGE, LEDGER_BASELINE_SIGN_OFF, IFRS17_VIEW, IFRS17_MANAGE, REINSURANCE_VIEW,
            REINSURANCE_MANAGE, RETURNS_VIEW, RETURNS_MANAGE, AGENTS_VIEW, AGENTS_MANAGE, COMMISSION_PAY, COMMISSION_APPROVE, FUNDS_VIEW,
            FUNDS_MANAGE, FUNDS_APPROVE, PRODUCTS_VIEW, PRODUCTS_CHARGES, PRODUCTS_DECLARE_APPROVE, REPORTS_VIEW,
            COMMUNICATIONS_VIEW);
    }

    /** Every "see" permission (the codes ending ".view") outside Administration. */
    private static Set<Permission> seeingOnly() {
        Set<Permission> seeing = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) {
            if (p.code().endsWith(".view") && p.area() != Permission.Area.ADMINISTRATION) seeing.add(p);
        }
        return seeing;
    }

    private static Set<Permission> admin() {
        Set<Permission> all = EnumSet.allOf(Permission.class);
        all.removeAll(APPROVALS_ONLY_BY_APPROVER);
        all.removeAll(SPECIALIST_UNDERWRITING_AND_CLAIMS);
        all.removeAll(APPROVE_OWN_WORK);
        all.removeAll(SIGN_OFFS);
        return all;
    }

    public static Definition byCode(String code) {
        return ALL.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null);
    }

    /** Where a role sits in the badge's precedence; roles of an administrator's own making come after the built-ins. */
    public static int precedence(String code) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).code().equals(code)) return i;
        }
        return ALL.size();
    }

    private BuiltInRoles() {}
}
