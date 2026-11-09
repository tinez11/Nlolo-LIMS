package tz.co.nlolo.lifeplatform.organisation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedDocument;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedPdf;
import tz.co.nlolo.lifeplatform.organisation.api.BranchView;
import tz.co.nlolo.lifeplatform.organisation.api.BrandingView;
import tz.co.nlolo.lifeplatform.organisation.api.DocumentKind;
import tz.co.nlolo.lifeplatform.organisation.api.Letterhead;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationApi;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationSettingsException;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationSettingsView;
import tz.co.nlolo.lifeplatform.organisation.api.ProfileView;
import tz.co.nlolo.lifeplatform.organisation.api.SignatoryInput;
import tz.co.nlolo.lifeplatform.organisation.api.SignatoryView;
import tz.co.nlolo.lifeplatform.organisation.api.SignatureStamp;
import tz.co.nlolo.lifeplatform.organisation.infrastructure.OrganisationStore;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The company's own settings (2026-11-09, docs/superpowers/specs/2026-11-09-organisation-settings-design.md): read by
 * every document and export, written by an administrator on Settings → Organisation.
 *
 * <p>The document reads never fail a document: a branding that cannot be read prints the platform's own letterhead.
 * They are cached per tenant (a receipt is printed far more often than the branding changes) and the cache is dropped
 * on every save.
 *
 * <p>Branches: a company that has saved none uses the shared BRANCH reference list, unchanged; its first save copies
 * that list into its own register, so nothing it already used disappears.
 */
@Service
public class Organisation implements OrganisationApi {

    private static final Logger log = LoggerFactory.getLogger(Organisation.class);
    private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern BRANCH_CODE = Pattern.compile("[A-Z0-9_]{2,20}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Set<String> TYPEFACES = Set.of("SANS", "SERIF");
    private static final Set<String> LAYOUTS = Set.of("CLASSIC", "BANDED", "MINIMAL");
    private static final Set<String> PAPERS = Set.of("A4", "LETTER");
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg");
    private static final long MAX_IMAGE_BYTES = 1024 * 1024;

    private final OrganisationStore store;
    private final DocumentApi documents;
    private final ReferenceDataApi refdata;
    private final ApplicationEventPublisher events;
    private final Map<UUID, Letterhead> letterheads = new ConcurrentHashMap<>();
    private final Map<String, byte[]> images = new ConcurrentHashMap<>();

    public Organisation(OrganisationStore store, DocumentApi documents, ReferenceDataApi refdata,
                        ApplicationEventPublisher events) {
        this.store = store;
        this.documents = documents;
        this.refdata = refdata;
        this.events = events;
    }

    // ---- what documents read ------------------------------------------------------------------------------------

    @Override
    public Letterhead letterhead() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null || !store.installed()) return Letterhead.fallback();
        try {
            return letterheads.computeIfAbsent(tenantId, this::readLetterhead);
        } catch (RuntimeException e) {
            log.warn("organisation: the letterhead could not be read; printing the platform's own", e);
            return Letterhead.fallback();
        }
    }

    private Letterhead readLetterhead(UUID tenantId) {
        Letterhead fallback = Letterhead.fallback();
        ProfileView p = store.profile(tenantId).orElse(null);
        OrganisationStore.Branding b = store.branding(tenantId).orElse(null);
        return letterhead(p, b == null ? null : view(b), b == null ? null : image(b.logoRef()), fallback);
    }

    private static Letterhead letterhead(ProfileView p, BrandingView b, byte[] logo, Letterhead f) {
        return new Letterhead(
            p != null ? p.legalName() : f.legalName(), p == null ? null : p.tradingName(),
            p == null ? null : p.registrationNumber(), p == null ? null : p.tin(), p == null ? null : p.address(),
            p == null ? null : p.phone(), p == null ? null : p.email(), p == null ? null : p.website(),
            p == null ? null : p.tagline(),
            b != null ? b.primaryColour() : f.primaryColour(), b != null ? b.secondaryColour() : f.secondaryColour(),
            b != null ? b.typeface() : f.typeface(), b != null ? b.layout() : f.layout(), b != null ? b.paper() : f.paper(),
            b == null ? null : b.footerText(),
            b != null && b.reportConfidentiality() != null ? b.reportConfidentiality() : f.reportConfidentiality(),
            logo);
    }

    @Override
    public Optional<SignatureStamp> signatory(DocumentKind kind, LocalDate onDay) {
        UUID tenantId = TenantContext.get();
        if (tenantId == null || !store.installed()) return Optional.empty();
        try {
            LocalDate day = onDay == null ? LocalDate.now() : onDay;
            return store.signatories(tenantId).stream()
                .filter(s -> s.signs().contains(kind.name()))
                .filter(s -> !s.effectiveFrom().isAfter(day) && (s.retiredOn() == null || s.retiredOn().isAfter(day)))
                .max(Comparator.comparing(OrganisationStore.Signatory::effectiveFrom))
                .map(s -> new SignatureStamp(s.name(), s.title(), image(s.signatureRef())));
        } catch (RuntimeException e) {
            log.warn("organisation: the signatory could not be read; the document goes unsigned", e);
            return Optional.empty();
        }
    }

    /** An image from the document store, kept once read: a stored image is never changed, only replaced. */
    private byte[] image(String ref) {
        if (ref == null) return null;
        try {
            return images.computeIfAbsent(ref, documents::download);
        } catch (RuntimeException e) {
            log.warn("organisation: image {} could not be read", ref, e);
            return null;
        }
    }

    @Override
    public List<BranchView> branches() {
        UUID tenantId = TenantContext.get();
        String headOffice = headOfficeBranch();
        if (tenantId != null && store.installed() && store.hasBranches(tenantId)) {
            List<BranchView> own = new ArrayList<>(store.branches(tenantId, headOffice));
            own.sort(Comparator.comparing((BranchView b) -> !b.headOffice()).thenComparing(BranchView::code));
            return own;
        }
        return sharedBranches(headOffice);
    }

    /** The shared BRANCH list, as a company that has saved none of its own still uses. */
    private List<BranchView> sharedBranches(String headOffice) {
        List<BranchView> out = new ArrayList<>();
        for (ReferenceCodeView c : refdata.getCodes("BRANCH")) {
            out.add(new BranchView(c.code(), c.label(), null, null, null, null, true, c.code().equals(headOffice)));
        }
        out.sort(Comparator.comparing((BranchView b) -> !b.headOffice()).thenComparing(BranchView::code));
        return out;
    }

    @Override
    public boolean isActiveBranch(String code) {
        return code != null && branches().stream().anyMatch(b -> b.active() && b.code().equals(code));
    }

    @Override
    public String headOfficeBranch() {
        UUID tenantId = TenantContext.get();
        String own = tenantId == null || !store.installed() ? null
            : store.profile(tenantId).map(ProfileView::headOfficeBranch).orElse(null);
        if (own != null && !own.isBlank()) return own;
        try {
            return refdata.getValue("HEAD_OFFICE_BRANCH", "TZ");
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- the settings page --------------------------------------------------------------------------------------

    public OrganisationSettingsView settings() {
        UUID tenantId = TenantContext.get();
        String headOffice = headOfficeBranch();
        ProfileView profile = store.profile(tenantId).orElse(new ProfileView(Letterhead.DEFAULT_NAME, null, null, null,
            null, null, null, null, null, headOffice));
        Letterhead f = Letterhead.fallback();
        BrandingView branding = store.branding(tenantId).map(Organisation::view).orElse(new BrandingView(
            f.primaryColour(), f.secondaryColour(), f.typeface(), f.layout(), f.paper(), null, f.reportConfidentiality(),
            false));
        List<SignatoryView> signatories = store.signatories(tenantId).stream().map(Organisation::view).toList();
        return new OrganisationSettingsView(profile, branding, signatories, branches());
    }

    @Transactional
    public ProfileView saveProfile(ProfileView in, String by) {
        if (blank(in.legalName())) throw new OrganisationSettingsException("Give the company's legal name.");
        length("The legal name", in.legalName(), 200);
        length("The trading name", in.tradingName(), 200);
        length("The address", in.address(), 400);
        length("The tagline", in.tagline(), 200);
        if (!blank(in.email()) && !EMAIL.matcher(in.email().trim()).matches()) {
            throw new OrganisationSettingsException(in.email() + " is not an email address.");
        }
        if (!blank(in.headOfficeBranch()) && !isActiveBranch(in.headOfficeBranch())) {
            throw new OrganisationSettingsException("The head office must be one of the company's active branches.");
        }
        UUID tenantId = TenantContext.get();
        ProfileView clean = new ProfileView(in.legalName().trim(), trim(in.tradingName()), trim(in.registrationNumber()),
            trim(in.tin()), trim(in.address()), trim(in.phone()), trim(in.email()), trim(in.website()), trim(in.tagline()),
            trim(in.headOfficeBranch()));
        store.saveProfile(tenantId, clean, by);
        changed(tenantId, "organisation.ProfileSaved", Map.of("legalName", clean.legalName(), "savedBy", by));
        return clean;
    }

    @Transactional
    public BrandingView saveBranding(BrandingView in, String by) {
        validate(in);
        UUID tenantId = TenantContext.get();
        String logoRef = store.branding(tenantId).map(OrganisationStore.Branding::logoRef).orElse(null);
        store.saveBranding(tenantId, branding(in, logoRef), by);
        changed(tenantId, "organisation.BrandingSaved", Map.of("layout", in.layout(), "primaryColour", in.primaryColour(),
            "savedBy", by));
        return view(branding(in, logoRef));
    }

    @Transactional
    public void uploadLogo(byte[] bytes, String contentType, String fileName, String by) {
        checkImage("The logo", bytes, contentType);
        UUID tenantId = TenantContext.get();
        String ref = documents.upload("organisation:logo", DocumentType.BRANDING_IMAGE, by, new ByteArrayInputStream(bytes),
            bytes.length, contentType, fileName == null ? "logo" : fileName);
        BrandingView current = settings().branding();
        store.saveBranding(tenantId, branding(current, ref), by);
        changed(tenantId, "organisation.LogoChanged", Map.of("documentRef", ref, "savedBy", by));
    }

    @Transactional
    public void removeLogo(String by) {
        UUID tenantId = TenantContext.get();
        BrandingView current = settings().branding();
        store.saveBranding(tenantId, branding(current, null), by);
        changed(tenantId, "organisation.LogoRemoved", Map.of("savedBy", by));
    }

    public byte[] logo() {
        return store.branding(TenantContext.get()).map(b -> image(b.logoRef())).orElse(null);
    }

    @Transactional
    public SignatoryView addSignatory(SignatoryInput in, String by) {
        validate(in);
        UUID tenantId = TenantContext.get();
        UUID id = store.addSignatory(tenantId, in, by);
        changed(tenantId, "organisation.SignatoryAdded", Map.of("signatoryId", id, "name", in.name(), "signs",
            in.signs(), "savedBy", by));
        return view(store.signatory(tenantId, id).orElseThrow());
    }

    @Transactional
    public SignatoryView updateSignatory(UUID signatoryId, SignatoryInput in, String by) {
        validate(in);
        UUID tenantId = TenantContext.get();
        signatoryOrThrow(signatoryId);
        store.updateSignatory(tenantId, signatoryId, in);
        changed(tenantId, "organisation.SignatoryChanged", Map.of("signatoryId", signatoryId, "name", in.name(),
            "signs", in.signs(), "savedBy", by));
        return view(store.signatory(tenantId, signatoryId).orElseThrow());
    }

    @Transactional
    public SignatoryView uploadSignature(UUID signatoryId, byte[] bytes, String contentType, String fileName, String by) {
        checkImage("The signature", bytes, contentType);
        UUID tenantId = TenantContext.get();
        signatoryOrThrow(signatoryId);
        // owner_context is VARCHAR(50): "signatory:" + a 36-character id is 46.
        String ref = documents.upload("signatory:" + signatoryId, DocumentType.BRANDING_IMAGE, by,
            new ByteArrayInputStream(bytes), bytes.length, contentType, fileName == null ? "signature" : fileName);
        store.setSignature(tenantId, signatoryId, ref);
        changed(tenantId, "organisation.SignatureUploaded", Map.of("signatoryId", signatoryId, "documentRef", ref,
            "savedBy", by));
        return view(store.signatory(tenantId, signatoryId).orElseThrow());
    }

    public byte[] signature(UUID signatoryId) {
        return image(signatoryOrThrow(signatoryId).signatureRef());
    }

    @Transactional
    public BranchView saveBranch(BranchView in, String by) {
        String code = in.code() == null ? "" : in.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (!BRANCH_CODE.matcher(code).matches()) {
            throw new OrganisationSettingsException("A branch code is 2 to 20 capital letters, digits or underscores.");
        }
        if (blank(in.name())) throw new OrganisationSettingsException("Give the branch a name.");
        if (!blank(in.email()) && !EMAIL.matcher(in.email().trim()).matches()) {
            throw new OrganisationSettingsException(in.email() + " is not an email address.");
        }
        UUID tenantId = TenantContext.get();
        String headOffice = headOfficeBranch();
        if (code.equals(headOffice) && !in.active()) {
            throw new OrganisationSettingsException("The head office cannot be closed. Make another branch the head office"
                + " first.");
        }
        // The first branch a company saves copies the shared list in, so nothing it already used disappears.
        if (!store.hasBranches(tenantId)) {
            for (BranchView shared : sharedBranches(headOffice)) store.saveBranch(tenantId, shared, by);
        }
        BranchView clean = new BranchView(code, in.name().trim(), trim(in.address()), trim(in.phone()), trim(in.email()),
            trim(in.manager()), in.active(), code.equals(headOffice));
        store.saveBranch(tenantId, clean, by);
        changed(tenantId, "organisation.BranchSaved", Map.of("code", code, "name", clean.name(), "active", in.active(),
            "savedBy", by));
        return clean;
    }

    /**
     * A sample receipt in a draft branding, before it is saved: what the preview on the settings page shows. The logo
     * and signatory are the company's current ones.
     */
    public byte[] preview(ProfileView profile, BrandingView branding) {
        validate(branding);
        Letterhead head = letterhead(profile, branding, logo(), Letterhead.fallback());
        BrandedDocument sample = new BrandedDocument("Receipt RC-2026-000123", null,
            List.of(new BrandedDocument.Field("Policyholder", "Amina Hassan"),
                new BrandedDocument.Field("Policy number", "POL-0F16C3B8"),
                new BrandedDocument.Field("Product", "Family Protector"),
                new BrandedDocument.Field("Receipt number", "RC-2026-000123"),
                new BrandedDocument.Field("Received on", "09/11/2026"),
                new BrandedDocument.Field("How", "Mobile money MM-58201")),
            List.of(), List.of(), null,
            List.of(new BrandedDocument.Table(null, List.of(new BrandedDocument.Column("For", false, 4f),
                    new BrandedDocument.Column("Amount (TZS)", true, 1.4f)),
                List.of(BrandedDocument.Row.of(List.of("Premium, cover 01/11/2026 to 30/11/2026", "45,000.00")),
                    BrandedDocument.Row.of(List.of("Held on the policy's balance for the next premiums", "5,000.00"))))),
            List.of(new BrandedDocument.Field("Amount received", "TZS 50,000.00")),
            List.of("Received with thanks. Quote the receipt number in any question about this payment."),
            false, false, signatory(DocumentKind.RECEIPT, LocalDate.now()).orElse(null));
        return BrandedPdf.render(sample, head);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private OrganisationStore.Signatory signatoryOrThrow(UUID signatoryId) {
        return store.signatory(TenantContext.get(), signatoryId)
            .orElseThrow(() -> new OrganisationSettingsException("There is no such signatory."));
    }

    private void changed(UUID tenantId, String type, Map<String, Object> payload) {
        letterheads.remove(tenantId);
        events.publishEvent(DomainEventEnvelope.of(type, tenantId, new HashMap<>(payload)));
    }

    private static void validate(BrandingView in) {
        if (in.primaryColour() == null || !COLOUR.matcher(in.primaryColour()).matches()
            || in.secondaryColour() == null || !COLOUR.matcher(in.secondaryColour()).matches()) {
            throw new OrganisationSettingsException("Colours are written #RRGGBB, for example #17324D.");
        }
        if (!TYPEFACES.contains(in.typeface())) throw new OrganisationSettingsException("Choose Sans or Serif.");
        if (!LAYOUTS.contains(in.layout())) throw new OrganisationSettingsException("Choose Classic, Banded or Minimal.");
        if (!PAPERS.contains(in.paper())) throw new OrganisationSettingsException("Choose A4 or Letter.");
        length("The footer", in.footerText(), 300);
        length("The confidentiality line", in.reportConfidentiality(), 200);
    }

    private static void validate(SignatoryInput in) {
        if (blank(in.name())) throw new OrganisationSettingsException("Give the signatory's name.");
        if (blank(in.title())) throw new OrganisationSettingsException("Give the signatory's title, such as Chief Executive.");
        if (in.effectiveFrom() == null) throw new OrganisationSettingsException("Say from when they sign.");
        if (in.retiredOn() != null && in.retiredOn().isBefore(in.effectiveFrom())) {
            throw new OrganisationSettingsException("They cannot stop signing before they start.");
        }
        if (in.signs() == null) throw new OrganisationSettingsException("Choose what they sign.");
        for (String kind : in.signs()) {
            try {
                DocumentKind.valueOf(kind);
            } catch (IllegalArgumentException e) {
                throw new OrganisationSettingsException(kind + " is not a document a signatory signs.");
            }
        }
    }

    private static void checkImage(String what, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) throw new OrganisationSettingsException(what + " file is empty.");
        if (bytes.length > MAX_IMAGE_BYTES) throw new OrganisationSettingsException(what + " must be 1 MB or smaller.");
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
            throw new OrganisationSettingsException(what + " must be a PNG or JPEG image.");
        }
        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw new OrganisationSettingsException(what + " could not be read as an image.");
            }
        } catch (IOException e) {
            throw new OrganisationSettingsException(what + " could not be read as an image.");
        }
    }

    private static OrganisationStore.Branding branding(BrandingView v, String logoRef) {
        return new OrganisationStore.Branding(v.primaryColour().toUpperCase(java.util.Locale.ROOT),
            v.secondaryColour().toUpperCase(java.util.Locale.ROOT), v.typeface(), v.layout(), v.paper(), trim(v.footerText()),
            trim(v.reportConfidentiality()), logoRef);
    }

    private static BrandingView view(OrganisationStore.Branding b) {
        return new BrandingView(b.primaryColour(), b.secondaryColour(), b.typeface(), b.layout(), b.paper(),
            b.footerText(), b.reportConfidentiality(), b.logoRef() != null);
    }

    private static SignatoryView view(OrganisationStore.Signatory s) {
        return new SignatoryView(s.signatoryId(), s.name(), s.title(), s.effectiveFrom(), s.retiredOn(), s.signs(),
            s.signatureRef() != null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }

    private static void length(String what, String value, int max) {
        if (value != null && value.length() > max) {
            throw new OrganisationSettingsException(what + " is longer than " + max + " characters.");
        }
    }
}
