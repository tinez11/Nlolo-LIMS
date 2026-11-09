package tz.co.nlolo.lifeplatform.organisation.api;

/** One of the company's branches; {@code headOffice} marks the one its documents carry. */
public record BranchView(String code, String name, String address, String phone, String email, String manager,
                         boolean active, boolean headOffice) {}
