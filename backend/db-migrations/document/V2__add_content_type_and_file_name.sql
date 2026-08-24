-- Module: document V2 -- M11 Task 1.
--
-- V1 stored no content type and no original filename. DocumentApiImpl.upload already RECEIVES
-- a contentType and hands it to MinIO's putObject, but nothing on the read path can recover it:
-- MinioDocumentStorage.get returns a bare byte[], and document_record has no column for it.
-- The original filename was never captured at all -- ClaimEvidenceController does not read
-- MultipartFile.getOriginalFilename().
--
-- DocumentType is NOT a substitute: MinioDocumentStorage.bucketFor maps it to a BUCKET
-- (kyc-evidence, claim-evidence, underwriting-evidence, policy-documents), not to a media type.
-- A CLAIM_EVIDENCE document is equally likely to be a JPEG or a PDF.
--
-- Without these two columns, M11's download endpoints could only ever serve
-- application/octet-stream named after a UUID -- so an uploaded certificate photo could never
-- render inline in the customer portal, and a saved file would arrive named 3f9a...-b21c.
--
-- BOTH COLUMNS ARE NULLABLE ON PURPOSE. Documents uploaded before this migration genuinely have
-- neither value and cannot be backfilled -- MinIO holds the content type on the object, but
-- reconciling object metadata back into Postgres for rows we cannot even enumerate per tenant
-- under RLS is not worth it for a column whose only consumer degrades gracefully. The read path
-- MUST tolerate null (M11 Task 3 covers that path with its own test).
--
-- WIDTH CHECK (platform convention: verify against real values, never estimate).
--   content_type: the longest IANA media type in real use is well under 100 chars
--     ('application/vnd.openxmlformats-officedocument.presentationml.presentation' is 73);
--     VARCHAR(255) is ample.
--   file_name: browsers cap upload filenames far below 255 bytes; VARCHAR(255) is ample.
-- Also re-verified while here, on V1's existing column: owner_context VARCHAR(50) still fits its
-- only real producer -- "claim:" + a 36-char UUID is 42 characters. That leaves only 8 characters
-- of headroom, so a future "underwriting-case:{uuid}" producer (18 + 36 = 54) would NOT fit and
-- must widen the column in the same migration that introduces it. Recorded because the design
-- spec's 2.1 anticipates exactly such producers.
-- =============================================================================
ALTER TABLE document.document_record ADD COLUMN content_type VARCHAR(255);
ALTER TABLE document.document_record ADD COLUMN file_name VARCHAR(255);

COMMENT ON COLUMN document.document_record.content_type IS
    'IANA media type as declared by the uploading client. NULL for rows created before document/V2; readers must fall back to application/octet-stream.';
COMMENT ON COLUMN document.document_record.file_name IS
    'Original filename as supplied by the uploading client. NULL for rows created before document/V2; readers must fall back to the document_ref.';
