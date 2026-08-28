package tz.co.nlolo.lifeplatform.document.api;

import java.io.InputStream;
import java.util.List;

public interface DocumentApi {
    String upload(String ownerContext, DocumentType documentType, String uploadedBy,
                  InputStream content, long contentLength, String contentType, String fileName);
    byte[] download(String documentRef);
    DocumentMetadataView getMetadata(String documentRef);

    /**
     * Metadata for every document filed under one owner context, newest first.
     *
     * <p>Internal only, and deliberately not exposed as an HTTP endpoint on this module. Answering
     * "may this caller see these?" means asking the owning aggregate, and this module cannot: party,
     * claims, policy and underwriting all declare {@code document::api}, so a dependency back onto
     * any of them is a cycle {@code NoCircularDependencyTest} fails on. So the owning module calls
     * this and applies its own authorization — {@code PartyController} does exactly that for
     * {@code party:<id>}, the way {@code ClaimEvidenceController} already does for {@code claim:<id>}.
     *
     * <p>Tenant scoping is applied here, not left to the caller.
     */
    List<DocumentMetadataView> listByOwnerContext(String ownerContext);
}
