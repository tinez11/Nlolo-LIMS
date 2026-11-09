package tz.co.nlolo.lifeplatform.organisation.api;

/** Who signs a document, and their signature image (PNG or JPEG), or null for a typed name only. */
public record SignatureStamp(String name, String title, byte[] image) {}
