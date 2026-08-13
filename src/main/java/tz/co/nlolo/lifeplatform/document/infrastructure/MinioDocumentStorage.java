package tz.co.nlolo.lifeplatform.document.infrastructure;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Bucket routing matches infra/docker-compose.yml's minio-init job, which
 * pre-creates exactly four buckets: policy-documents, kyc-evidence,
 * underwriting-evidence, and claim-evidence.
 */
@Component
public class MinioDocumentStorage {

    private static final String KYC_BUCKET = "kyc-evidence";
    private static final String UNDERWRITING_BUCKET = "underwriting-evidence";
    private static final String CLAIM_BUCKET = "claim-evidence";
    private static final String GENERAL_BUCKET = "policy-documents";

    private final MinioClient minioClient;

    public MinioDocumentStorage(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    public void put(String objectKey, DocumentType documentType, InputStream content, long contentLength, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketFor(documentType))
                .object(objectKey)
                .stream(content, contentLength, -1)
                .contentType(contentType)
                .build());
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to upload document " + objectKey, e);
        }
    }

    public byte[] get(String objectKey, DocumentType documentType) {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketFor(documentType))
                .object(objectKey)
                .build());
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to download document " + objectKey, e);
        }
    }

    private static String bucketFor(DocumentType documentType) {
        return switch (documentType) {
            case KYC_EVIDENCE -> KYC_BUCKET;
            case UNDERWRITING_EVIDENCE -> UNDERWRITING_BUCKET;
            case CLAIM_EVIDENCE -> CLAIM_BUCKET;
            default -> GENERAL_BUCKET;
        };
    }
}
