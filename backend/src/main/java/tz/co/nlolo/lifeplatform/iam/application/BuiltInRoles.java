package tz.co.nlolo.lifeplatform.iam.application;

import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static tz.co.nlolo.lifeplatform.iam.api.Permission.*;

/**
 * The eight roles every tenant starts with -- Keycloak's staff realm roles, carried over by name -- and what each
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

    private static final Set<Permission> SPECIALIST_UNDERWRITING_AND_CLAIMS = EnumSet.of(
        UNDERWRITING_ASSESS, UNDERWRITING_DECIDE, UNDERWRITING_DECIDE_AGAINST, UNDERWRITING_REFER, SCHEMES_MANAGE,
        CLAIMS_ASSESS, CLAIMS_SETTLE);

    public static final List<Definition> ALL = List.of(
        new Definition("ADMIN", "Administrator",
            "Runs the platform: products, users and roles, and every finance surface. Does not underwrite, assess "
                + "claims or give the approver's second signature.",
            admin()),
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
            EnumSet.of(LEDGER_VIEW, LEDGER_APPROVE, IFRS17_VIEW, IFRS17_APPROVE, REINSURANCE_VIEW, REINSURANCE_APPROVE,
                REPORTS_VIEW)),
        new Definition("FINANCE_OFFICER", "Finance officer",
            "Collections, payouts, the ledger, IFRS 17, reinsurance, returns, agents and funds.",
            finance()),
        new Definition("CUSTOMER_SERVICE_REP", "Customer service",
            "Registers and services clients and their policies: endorsements, surrenders, account transactions, "
                + "loans, claims registration, portal invitations.",
            EnumSet.of(CLIENTS_VIEW, CLIENTS_EDIT, CLIENTS_KYC, CLIENTS_PORTAL, UNDERWRITING_VIEW, UNDERWRITING_PROPOSE,
                POLICIES_VIEW, POLICIES_SERVICE, POLICIES_STATUS, POLICIES_SURRENDER, SCHEMES_VIEW, CLAIMS_VIEW,
                CLAIMS_REGISTER, ACCOUNTS_VIEW, ACCOUNTS_TRANSACT, LOANS_MANAGE, COLLECTIONS_VIEW, PAYOUTS_VIEW,
                PRODUCTS_VIEW, COMMUNICATIONS_VIEW)));

    private static Set<Permission> finance() {
        return EnumSet.of(CLIENTS_VIEW, POLICIES_VIEW, POLICIES_SURRENDER_APPROVE, SCHEMES_VIEW, CLAIMS_VIEW,
            ACCOUNTS_VIEW, ACCOUNTS_APPROVE, COLLECTIONS_VIEW, COLLECTIONS_MANAGE, PAYOUTS_VIEW, PAYOUTS_MANAGE,
            PAYOUTS_APPROVE, LEDGER_VIEW, LEDGER_MANAGE, IFRS17_VIEW, IFRS17_MANAGE, REINSURANCE_VIEW,
            REINSURANCE_MANAGE, RETURNS_VIEW, RETURNS_MANAGE, AGENTS_VIEW, AGENTS_MANAGE, COMMISSION_PAY, FUNDS_VIEW,
            FUNDS_MANAGE, FUNDS_APPROVE, PRODUCTS_VIEW, PRODUCTS_CHARGES, PRODUCTS_DECLARE_APPROVE, REPORTS_VIEW,
            COMMUNICATIONS_VIEW);
    }

    private static Set<Permission> admin() {
        Set<Permission> all = EnumSet.allOf(Permission.class);
        all.removeAll(APPROVALS_ONLY_BY_APPROVER);
        all.removeAll(SPECIALIST_UNDERWRITING_AND_CLAIMS);
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
