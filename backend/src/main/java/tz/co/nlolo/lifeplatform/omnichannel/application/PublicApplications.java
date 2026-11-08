package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.domain.PublicApplication;
import tz.co.nlolo.lifeplatform.omnichannel.infrastructure.PublicApplicationRepository;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.party.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.CaseSource;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applying through the public quote link (2026-11-08, phase 2 of the user's 2026-11-02 design). The visitor's phone is
 * already confirmed by a texted code; here they become a client -- KYC pending, for staff to verify -- and the same
 * application the portal opens goes to underwriting with source "Online (public)".
 *
 * <p>Somebody already on the books is never merged into (the user's decision): a new client is always registered, and
 * anyone with the same phone, or the same name and date of birth, is recorded beside the case for staff to look at.
 */
@Service
public class PublicApplications {

    /** Who the platform records as having registered and opened these: nobody signed in, so the link itself. */
    public static final String OPENED_BY = "public-quote-link";
    private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");

    private final PartyApi parties;
    private final UnderwritingApi underwriting;
    private final CustomerProducts products;
    private final PublicApplicationRepository applications;
    private final Clock clock;

    public PublicApplications(PartyApi parties, UnderwritingApi underwriting, CustomerProducts products,
                              PublicApplicationRepository applications) {
        this.parties = parties;
        this.underwriting = underwriting;
        this.products = products;
        this.applications = applications;
        this.clock = Clock.systemUTC();
    }

    /** The visitor's own facts, as they typed them; the phone is the one the code confirmed, not one from here. */
    public record Applicant(String fullName, LocalDate dateOfBirth, String sex, String smokerStatus, String email) {}

    /** What the visitor saw and ticked: the wording's version must be the one in force now. */
    public record Consent(String version, boolean agreed) {}

    /** What staff see beside a public application's case. */
    public record StaffView(UUID caseId, String phoneNumber, String consentVersion, String consentText, String language,
                            java.time.Instant appliedAt, List<Match> possibleExistingClients) {}

    public record Match(UUID partyId, String displayName) {}

    @Transactional
    public CustomerProductView.Application apply(String confirmedPhone, Applicant applicant, Consent consent,
                                                 CustomerProductView.ApplicationRequest request, String remoteAddress) {
        boolean swahili = "SW".equalsIgnoreCase(request.language());
        if (consent == null || !consent.agreed() || !PublicConsent.VERSION.equals(consent.version())) {
            throw new CustomerRequestRefusedException(swahili ? "Soma na ukubali masharti kabla ya kutuma."
                : "Read and agree to the declaration before you send.");
        }
        if (applicant == null || applicant.fullName() == null || applicant.fullName().isBlank()) {
            throw new CustomerRequestRefusedException(swahili ? "Andika jina lako kamili." : "Give your full name.");
        }
        if (applicant.dateOfBirth() == null || applicant.dateOfBirth().isAfter(LocalDate.now(DAR))) {
            throw new CustomerRequestRefusedException(swahili ? "Weka tarehe yako ya kuzaliwa." : "Give your date of birth.");
        }
        UUID tenantId = TenantContext.get();
        alreadyApplied(tenantId, confirmedPhone, request.productId(), swahili);

        String name = applicant.fullName().trim().replaceAll("\\s+", " ");
        // Looked for BEFORE registering, so the newcomer is not their own match.
        List<UUID> matches = parties.possibleSamePerson(confirmedPhone, name, applicant.dateOfBirth()).stream()
            .map(PartyView::partyId).toList();

        String email = applicant.email() == null || applicant.email().isBlank() ? null : applicant.email().trim();
        PartyView party = parties.registerIndividual(new IndividualRegistration(name, applicant.dateOfBirth(),
            confirmedPhone, email, sex(applicant.sex()), smoker(applicant.smokerStatus()), null, null, null, null, null,
            null), OPENED_BY, "Public quote link", null);
        parties.setPreferredLanguage(party.partyId(), swahili ? "sw" : "en", OPENED_BY);

        CustomerProductView.Application opened = products.apply(party.partyId(), request, OPENED_BY, CaseSource.PUBLIC_LINK);
        applications.save(new PublicApplication(tenantId, party.partyId(), opened.caseId(), confirmedPhone,
            PublicConsent.VERSION, PublicConsent.text(swahili), swahili ? "sw" : "en", remoteAddress, matches,
            clock.instant()));
        return opened;
    }

    /** For staff on the case page; empty when the case did not come through the public link. */
    @Transactional(readOnly = true)
    public Optional<StaffView> forCase(UUID caseId) {
        return applications.findByTenantIdAndCaseId(TenantContext.get(), caseId).map(a -> {
            Map<UUID, String> names = parties.displayNames(a.getPossibleMatches());
            List<Match> matches = a.getPossibleMatches().stream()
                .map(id -> new Match(id, names.getOrDefault(id, null)))
                .toList();
            return new StaffView(a.getCaseId(), a.getPhoneNumber(), a.getConsentVersion(), a.getConsentText(),
                a.getLanguage(), a.getAppliedAt(), matches);
        });
    }

    /** One application at a time for a product from one phone: a second while the first is reviewed is refused. */
    private void alreadyApplied(UUID tenantId, String phone, UUID productId, boolean swahili) {
        boolean open = applications.findByTenantIdAndPhoneNumber(tenantId, phone).stream()
            .map(a -> underwriting.getCase(a.getCaseId()))
            .anyMatch(c -> c.productId().equals(productId) && c.status() != UnderwritingCaseStatus.DECIDED);
        if (open) {
            throw new CustomerRequestRefusedException(swahili
                ? "Tayari umeomba bima hii kwa namba hii ya simu. Tutawasiliana nawe."
                : "You have already applied for this cover from this phone. We will contact you.", true);
        }
    }

    private static Sex sex(String value) {
        try {
            return value == null || value.isBlank() ? null : Sex.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new CustomerRequestRefusedException("Choose your sex from the list");
        }
    }

    private static SmokerStatus smoker(String value) {
        try {
            return value == null || value.isBlank() ? SmokerStatus.UNKNOWN : SmokerStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new CustomerRequestRefusedException("Choose whether you smoke from the list");
        }
    }
}
