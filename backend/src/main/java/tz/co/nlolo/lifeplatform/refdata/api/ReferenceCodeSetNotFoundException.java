package tz.co.nlolo.lifeplatform.refdata.api;

/** 404 at the REST boundary. Task 5's allowlist throws this for BOTH a denied key and an unknown
 * key, with an identical message, so a caller cannot distinguish "not found" from "not allowed". */
public class ReferenceCodeSetNotFoundException extends RuntimeException {
    public ReferenceCodeSetNotFoundException(String message) { super(message); }
}
