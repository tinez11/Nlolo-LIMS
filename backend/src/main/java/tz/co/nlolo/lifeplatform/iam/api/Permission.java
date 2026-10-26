package tz.co.nlolo.lifeplatform.iam.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * Everything a staff member may be allowed to see or do on the platform (2026-10-26), grouped by area. Each area has
 * its own "view" (the user's decision: a finance officer with no underwriting permission cannot even open a case),
 * then the acts within it.
 *
 * <p>The code is what is stored, checked and sent to the console -- never rename one; retire it and add another. The
 * catalogue lives in code, not the database, because each permission is only real where an endpoint checks it.
 */
public enum Permission {

    CLIENTS_VIEW("clients.view", Area.CLIENTS, "See clients", "Open client records, their documents and KYC history."),
    CLIENTS_EDIT("clients.edit", Area.CLIENTS, "Register and correct clients",
        "Register individuals and corporates, correct their details, upload documents, add group members."),
    CLIENTS_KYC("clients.kyc", Area.CLIENTS, "Decide KYC", "Verify or reject a client's identity."),
    CLIENTS_PORTAL("clients.portal", Area.CLIENTS, "Customer portal access",
        "Invite a client to the customer portal, re-send or revoke the invitation."),

    UNDERWRITING_VIEW("underwriting.view", Area.UNDERWRITING, "See underwriting", "Open the queue and the cases in it."),
    UNDERWRITING_PROPOSE("underwriting.propose", Area.UNDERWRITING, "Open and complete cases",
        "Open a case for an applicant and record what they ask for, their disclosures and choices."),
    UNDERWRITING_ASSESS("underwriting.assess", Area.UNDERWRITING, "Assess cases", "Record an assessment and a recommendation."),
    UNDERWRITING_DECIDE("underwriting.decide", Area.UNDERWRITING, "Decide cases",
        "Accept, rate, postpone or decline a case, and return it to the agent."),
    UNDERWRITING_DECIDE_AGAINST("underwriting.decide-against-recommendation", Area.UNDERWRITING,
        "Decide against the recommendation", "Overrule an assessor's recommendation (senior underwriting)."),
    UNDERWRITING_REFER("underwriting.refer", Area.UNDERWRITING, "Refer cases", "Refer a case to a senior underwriter."),

    POLICIES_VIEW("policies.view", Area.POLICIES, "See policies", "Open policies, their invoices, documents and history."),
    POLICIES_ISSUE("policies.issue", Area.POLICIES, "Issue by hand", "Issue a policy from a decided case manually."),
    POLICIES_SERVICE("policies.service", Area.POLICIES, "Service policies",
        "Endorsements, beneficiaries, adding or removing covered lives, takeovers."),
    POLICIES_STATUS("policies.status", Area.POLICIES, "Suspend, reinstate, make paid-up",
        "Change a policy's status: suspend, resume, reinstate, paid-up, free-look cancellation."),
    POLICIES_SURRENDER("policies.surrender", Area.POLICIES, "Request surrenders", "Start a surrender."),
    POLICIES_SURRENDER_APPROVE("policies.surrender-approve", Area.POLICIES, "Approve surrenders",
        "The second signature that pays a surrender."),

    SCHEMES_VIEW("schemes.view", Area.SCHEMES, "See group schemes", "Open group, funeral and credit-life schemes."),
    SCHEMES_MANAGE("schemes.manage", Area.SCHEMES, "Underwrite schemes",
        "Put a scheme on risk, admit members and families, set the free cover limit."),
    SCHEMES_ENROLMENTS("schemes.enrolments", Area.SCHEMES, "Credit-life schedules",
        "Upload, accept or withdraw credit-life enrolment and exit schedules."),

    CLAIMS_VIEW("claims.view", Area.CLAIMS, "See claims", "Open claims and their evidence."),
    CLAIMS_REGISTER("claims.register", Area.CLAIMS, "Register claims", "Register a claim and add evidence to it."),
    CLAIMS_ASSESS("claims.assess", Area.CLAIMS, "Assess claims",
        "Assess a claim, ask for documents, register a funeral life as a client."),
    CLAIMS_SETTLE("claims.settle", Area.CLAIMS, "Decide settlements", "Approve or decline a claim's settlement, reopen it."),

    ACCOUNTS_VIEW("accounts.view", Area.ACCOUNTS, "See accounts and values",
        "Savings accounts, units, bonuses, annuities and loans on a policy."),
    ACCOUNTS_TRANSACT("accounts.transact", Area.ACCOUNTS, "Account transactions",
        "Withdrawals, top-ups, transfers in, switches, statements, maturity and vesting instructions."),
    ACCOUNTS_APPROVE("accounts.approve", Area.ACCOUNTS, "Approve account transactions",
        "The second signature on withdrawals, adjustments and deposit payouts."),
    LOANS_MANAGE("loans.manage", Area.ACCOUNTS, "Policy loans", "Grant loans, record repayments, review forced lapses."),

    COLLECTIONS_VIEW("collections.view", Area.COLLECTIONS, "See collections", "Arrears, field receipts and bank transfers."),
    COLLECTIONS_MANAGE("collections.manage", Area.COLLECTIONS, "Manage collections", "Reconcile receipts and waive invoices."),

    PAYOUTS_VIEW("payouts.view", Area.PAYOUTS, "See payouts", "Payouts, payment runs, disbursements and maturities."),
    PAYOUTS_MANAGE("payouts.manage", Area.PAYOUTS, "Prepare payouts",
        "Review and retry payouts, withholding rules, execute bank transfers."),
    PAYOUTS_APPROVE("payouts.approve", Area.PAYOUTS, "Approve payouts", "Approve payouts, payment runs and withholding rules."),

    LEDGER_VIEW("ledger.view", Area.LEDGER, "See the ledger", "GL postings, chart of accounts, periods and journals."),
    LEDGER_MANAGE("ledger.manage", Area.LEDGER, "Keep the ledger",
        "Chart of accounts, manual journals, templates, period closing, accounting policies."),
    LEDGER_APPROVE("ledger.approve", Area.LEDGER, "Approve journals", "Approve or reject manual journals and period reopenings."),

    IFRS17_VIEW("ifrs17.view", Area.IFRS17, "See IFRS 17", "Extracts, engine runs, allocations and year-end close."),
    IFRS17_MANAGE("ifrs17.manage", Area.IFRS17, "Run IFRS 17", "Prepare extracts, engine runs, allocations and the year-end close."),
    IFRS17_APPROVE("ifrs17.approve", Area.IFRS17, "Approve IFRS 17", "Approve or reject engine runs, allocations and the close."),

    REINSURANCE_VIEW("reinsurance.view", Area.REINSURANCE, "See reinsurance", "Treaties, cessions, bordereaux and statements."),
    REINSURANCE_MANAGE("reinsurance.manage", Area.REINSURANCE, "Manage reinsurance", "Set up treaties and prepare statements."),
    REINSURANCE_APPROVE("reinsurance.approve", Area.REINSURANCE, "Approve reinsurance statements",
        "Approve or reject a reinsurance statement."),

    RETURNS_VIEW("returns.view", Area.RETURNS, "See regulatory returns", "The returns filed with TIRA."),
    RETURNS_MANAGE("returns.manage", Area.RETURNS, "Prepare regulatory returns", "Prepare a regulatory return."),

    AGENTS_VIEW("agents.view", Area.DISTRIBUTION, "See agents", "Agents, their teams, plans and commission."),
    AGENTS_MANAGE("agents.manage", Area.DISTRIBUTION, "Manage agents",
        "Onboard, place, suspend and reactivate agents; set commission rates and plans."),
    COMMISSION_PAY("commission.pay", Area.DISTRIBUTION, "Pay commission", "Pay out a commission statement."),

    FUNDS_VIEW("funds.view", Area.FUNDS, "See funds", "Unit-linked funds and their prices."),
    FUNDS_MANAGE("funds.manage", Area.FUNDS, "Manage funds", "Open and close funds, post prices, settle price adjustments."),
    FUNDS_APPROVE("funds.approve", Area.FUNDS, "Approve fund prices", "The second signature on a fund price."),

    PRODUCTS_VIEW("products.view", Area.PRODUCTS, "See products", "The catalogue, versions and rating."),
    PRODUCTS_MANAGE("products.manage", Area.PRODUCTS, "Design products",
        "Create products, publish versions, descriptions, online listing, exclusions."),
    PRODUCTS_CHARGES("products.charges", Area.PRODUCTS, "Account charges", "The account charges catalogue."),
    PRODUCTS_DECLARE("products.declare", Area.PRODUCTS, "Declare rates and bonuses",
        "Propose interest rates and bonus declarations."),
    PRODUCTS_DECLARE_APPROVE("products.declare-approve", Area.PRODUCTS, "Approve rates and bonuses",
        "The second signature on a declared rate or bonus."),

    REPORTS_VIEW("reports.view", Area.REPORTS, "See reports", "Management and accounting reports."),
    REPORTS_REBUILD("reports.rebuild", Area.REPORTS, "Rebuild reports", "Rebuild the reporting store."),

    COMMUNICATIONS_VIEW("communications.view", Area.COMMUNICATIONS, "See messages sent", "Messages sent and the templates."),
    COMMUNICATIONS_TEMPLATES("communications.templates", Area.COMMUNICATIONS, "Edit message templates",
        "Change what the platform's messages say."),

    AUDIT_VIEW("audit.view", Area.AUDIT, "See the event journal", "Every recorded event."),

    USERS_VIEW("users.view", Area.ADMINISTRATION, "See users and roles", "Staff users, roles and the history of changes."),
    USERS_MANAGE("users.manage", Area.ADMINISTRATION, "Manage users",
        "Add, deactivate and reset staff users; give them roles and permissions."),
    ROLES_MANAGE("roles.manage", Area.ADMINISTRATION, "Manage roles", "Create roles and change what they carry.");

    /** The console's grouping, in the order the screens show it. */
    public enum Area {
        CLIENTS("Clients"), UNDERWRITING("Underwriting"), POLICIES("Policies"), SCHEMES("Group schemes"),
        CLAIMS("Claims"), ACCOUNTS("Accounts & loans"), COLLECTIONS("Collections"), PAYOUTS("Paying out"),
        LEDGER("Ledger"), IFRS17("IFRS 17"), REINSURANCE("Reinsurance"), RETURNS("Regulatory returns"),
        DISTRIBUTION("Distribution"), FUNDS("Funds"), PRODUCTS("Products"), REPORTS("Reports"),
        COMMUNICATIONS("Communications"), AUDIT("Audit"), ADMINISTRATION("Administration");

        private final String label;

        Area(String label) { this.label = label; }

        public String label() { return label; }
    }

    private final String code;
    private final Area area;
    private final String label;
    private final String description;

    Permission(String code, Area area, String label, String description) {
        this.code = code;
        this.area = area;
        this.label = label;
        this.description = description;
    }

    public String code() { return code; }
    public Area area() { return area; }
    public String label() { return label; }
    public String description() { return description; }

    public static Optional<Permission> fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
