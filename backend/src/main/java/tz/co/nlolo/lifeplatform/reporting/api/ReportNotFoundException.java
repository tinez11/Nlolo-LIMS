package tz.co.nlolo.lifeplatform.reporting.api;

public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(String key) {
        super("No report called '" + key + "'");
    }
}
