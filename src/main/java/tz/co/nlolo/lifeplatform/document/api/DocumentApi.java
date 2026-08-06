package tz.co.nlolo.lifeplatform.document.api;

import java.io.InputStream;

public interface DocumentApi {
    String upload(String ownerContext, DocumentType documentType, String uploadedBy,
                  InputStream content, long contentLength, String contentType);
    byte[] download(String documentRef);
    DocumentMetadataView getMetadata(String documentRef);
}
