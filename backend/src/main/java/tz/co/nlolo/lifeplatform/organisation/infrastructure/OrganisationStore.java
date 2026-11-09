package tz.co.nlolo.lifeplatform.organisation.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tz.co.nlolo.lifeplatform.organisation.api.BranchView;
import tz.co.nlolo.lifeplatform.organisation.api.ProfileView;
import tz.co.nlolo.lifeplatform.organisation.api.SignatoryInput;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The organisation tables. Every statement names the tenant; row level security is the backstop. */
@Repository
public class OrganisationStore {

    private final JdbcTemplate jdbc;

    public OrganisationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private volatile Boolean installed;

    /**
     * Whether the organisation tables exist -- asked with to_regclass, which never fails, so a database that predates
     * them (a module test's, or one mid-upgrade) reads the shared defaults without an error that would abort the
     * caller's transaction. Remembered once true.
     */
    public boolean installed() {
        if (Boolean.TRUE.equals(installed)) return true;
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('organisation.branch') IS NOT NULL", Boolean.class);
        if (Boolean.TRUE.equals(exists)) installed = true;
        return Boolean.TRUE.equals(exists);
    }

    // ---- profile ------------------------------------------------------------------------------------------------

    public Optional<ProfileView> profile(UUID tenantId) {
        return jdbc.query("SELECT * FROM organisation.profile WHERE tenant_id = ?", (rs, i) -> new ProfileView(
            rs.getString("legal_name"), rs.getString("trading_name"), rs.getString("registration_number"),
            rs.getString("tin"), rs.getString("address"), rs.getString("phone"), rs.getString("email"),
            rs.getString("website"), rs.getString("tagline"), rs.getString("head_office_branch")), tenantId)
            .stream().findFirst();
    }

    public void saveProfile(UUID tenantId, ProfileView p, String by) {
        jdbc.update("""
            INSERT INTO organisation.profile (tenant_id, legal_name, trading_name, registration_number, tin, address, phone,
                email, website, tagline, head_office_branch, updated_by, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (tenant_id) DO UPDATE SET legal_name = EXCLUDED.legal_name, trading_name = EXCLUDED.trading_name,
                registration_number = EXCLUDED.registration_number, tin = EXCLUDED.tin, address = EXCLUDED.address,
                phone = EXCLUDED.phone, email = EXCLUDED.email, website = EXCLUDED.website, tagline = EXCLUDED.tagline,
                head_office_branch = EXCLUDED.head_office_branch, updated_by = EXCLUDED.updated_by, updated_at = now()
            """, tenantId, p.legalName(), p.tradingName(), p.registrationNumber(), p.tin(), p.address(), p.phone(),
            p.email(), p.website(), p.tagline(), p.headOfficeBranch(), by);
    }

    // ---- branding -----------------------------------------------------------------------------------------------

    /** The branding row as stored, with its logo's document reference. */
    public record Branding(String primaryColour, String secondaryColour, String typeface, String layout, String paper,
                           String footerText, String reportConfidentiality, String logoRef) {}

    public Optional<Branding> branding(UUID tenantId) {
        return jdbc.query("SELECT * FROM organisation.branding WHERE tenant_id = ?", (rs, i) -> new Branding(
            rs.getString("primary_colour"), rs.getString("secondary_colour"), rs.getString("typeface"),
            rs.getString("layout"), rs.getString("paper"), rs.getString("footer_text"),
            rs.getString("report_confidentiality"), rs.getString("logo_ref")), tenantId).stream().findFirst();
    }

    public void saveBranding(UUID tenantId, Branding b, String by) {
        jdbc.update("""
            INSERT INTO organisation.branding (tenant_id, primary_colour, secondary_colour, typeface, layout, paper,
                footer_text, report_confidentiality, logo_ref, updated_by, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (tenant_id) DO UPDATE SET primary_colour = EXCLUDED.primary_colour,
                secondary_colour = EXCLUDED.secondary_colour, typeface = EXCLUDED.typeface, layout = EXCLUDED.layout,
                paper = EXCLUDED.paper, footer_text = EXCLUDED.footer_text,
                report_confidentiality = EXCLUDED.report_confidentiality, logo_ref = EXCLUDED.logo_ref,
                updated_by = EXCLUDED.updated_by, updated_at = now()
            """, tenantId, b.primaryColour(), b.secondaryColour(), b.typeface(), b.layout(), b.paper(), b.footerText(),
            b.reportConfidentiality(), b.logoRef(), by);
    }

    // ---- signatories --------------------------------------------------------------------------------------------

    public record Signatory(UUID signatoryId, String name, String title, String signatureRef, LocalDate effectiveFrom,
                            LocalDate retiredOn, List<String> signs) {}

    public List<Signatory> signatories(UUID tenantId) {
        return jdbc.query("SELECT * FROM organisation.signatory WHERE tenant_id = ? ORDER BY effective_from DESC, created_at DESC",
            (rs, i) -> signatory(rs), tenantId);
    }

    public Optional<Signatory> signatory(UUID tenantId, UUID signatoryId) {
        return jdbc.query("SELECT * FROM organisation.signatory WHERE tenant_id = ? AND signatory_id = ?",
            (rs, i) -> signatory(rs), tenantId, signatoryId).stream().findFirst();
    }

    public UUID addSignatory(UUID tenantId, SignatoryInput in, String by) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organisation.signatory (signatory_id, tenant_id, name, title, effective_from, retired_on,"
                + " signs, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ps -> {
                ps.setObject(1, id);
                ps.setObject(2, tenantId);
                ps.setString(3, in.name().trim());
                ps.setString(4, in.title().trim());
                ps.setDate(5, Date.valueOf(in.effectiveFrom()));
                ps.setDate(6, in.retiredOn() == null ? null : Date.valueOf(in.retiredOn()));
                ps.setArray(7, ps.getConnection().createArrayOf("text", in.signs().toArray()));
                ps.setString(8, by);
            });
        return id;
    }

    public void updateSignatory(UUID tenantId, UUID signatoryId, SignatoryInput in) {
        jdbc.update("UPDATE organisation.signatory SET name = ?, title = ?, effective_from = ?, retired_on = ?, signs = ?"
                + " WHERE tenant_id = ? AND signatory_id = ?",
            ps -> {
                ps.setString(1, in.name().trim());
                ps.setString(2, in.title().trim());
                ps.setDate(3, Date.valueOf(in.effectiveFrom()));
                ps.setDate(4, in.retiredOn() == null ? null : Date.valueOf(in.retiredOn()));
                ps.setArray(5, ps.getConnection().createArrayOf("text", in.signs().toArray()));
                ps.setObject(6, tenantId);
                ps.setObject(7, signatoryId);
            });
    }

    public void setSignature(UUID tenantId, UUID signatoryId, String ref) {
        jdbc.update("UPDATE organisation.signatory SET signature_ref = ? WHERE tenant_id = ? AND signatory_id = ?",
            ref, tenantId, signatoryId);
    }

    private static Signatory signatory(ResultSet rs) throws SQLException {
        Array signs = rs.getArray("signs");
        Date retired = rs.getDate("retired_on");
        return new Signatory((UUID) rs.getObject("signatory_id"), rs.getString("name"), rs.getString("title"),
            rs.getString("signature_ref"), rs.getDate("effective_from").toLocalDate(),
            retired == null ? null : retired.toLocalDate(),
            signs == null ? List.of() : Arrays.asList((String[]) signs.getArray()));
    }

    // ---- branches -----------------------------------------------------------------------------------------------

    public List<BranchView> branches(UUID tenantId, String headOffice) {
        return jdbc.query("SELECT * FROM organisation.branch WHERE tenant_id = ? ORDER BY code",
            (rs, i) -> new BranchView(rs.getString("code"), rs.getString("name"), rs.getString("address"),
                rs.getString("phone"), rs.getString("email"), rs.getString("manager"), rs.getBoolean("active"),
                rs.getString("code").equals(headOffice)), tenantId);
    }

    public void saveBranch(UUID tenantId, BranchView b, String by) {
        jdbc.update("""
            INSERT INTO organisation.branch (tenant_id, code, name, address, phone, email, manager, active, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, address = EXCLUDED.address,
                phone = EXCLUDED.phone, email = EXCLUDED.email, manager = EXCLUDED.manager, active = EXCLUDED.active,
                updated_by = EXCLUDED.updated_by, updated_at = now()
            """, tenantId, b.code(), b.name(), b.address(), b.phone(), b.email(), b.manager(), b.active(), by);
    }

    public boolean hasBranches(UUID tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM organisation.branch WHERE tenant_id = ?", Integer.class, tenantId);
        return n != null && n > 0;
    }
}
