package tz.co.nlolo.lifeplatform.finaccounting.api;

/** No year-end close by that id for the current tenant (404, IFRS 17 I6). */
public class YearEndCloseNotFoundException extends RuntimeException {
    public YearEndCloseNotFoundException(String message) {
        super(message);
    }
}
