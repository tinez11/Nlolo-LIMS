package tz.co.nlolo.lifeplatform.organisation.api;

/** A setting that cannot be saved, in words an administrator can act on. A 400. */
public class OrganisationSettingsException extends RuntimeException {
    public OrganisationSettingsException(String message) {
        super(message);
    }
}
