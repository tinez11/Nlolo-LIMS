package tz.co.nlolo.lifeplatform.organisation.api;

import java.util.List;

/** Everything on the Organisation settings page, in one read. */
public record OrganisationSettingsView(ProfileView profile, BrandingView branding, List<SignatoryView> signatories,
                                       List<BranchView> branches) {}
