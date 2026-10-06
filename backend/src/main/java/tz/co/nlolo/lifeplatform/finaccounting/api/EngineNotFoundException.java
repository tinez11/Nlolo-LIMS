package tz.co.nlolo.lifeplatform.finaccounting.api;

/** No engine extract or engine run with this id in the caller's tenant (404, IFRS 17 I5a). */
public class EngineNotFoundException extends RuntimeException {
    public EngineNotFoundException(String message) {
        super(message);
    }
}
