package tz.co.nlolo.lifeplatform.organisation.api;

/** Who the company is, as its documents print it (2026-11-09). Also what a save sends. */
public record ProfileView(String legalName, String tradingName, String registrationNumber, String tin, String address,
                          String phone, String email, String website, String tagline, String headOfficeBranch) {}
