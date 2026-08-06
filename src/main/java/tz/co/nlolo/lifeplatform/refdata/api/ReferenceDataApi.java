package tz.co.nlolo.lifeplatform.refdata.api;

import java.util.List;

public interface ReferenceDataApi {
    List<ReferenceCodeView> getCodes(String codeSetKey);
    String getValue(String codeSetKey, String jurisdiction);
}
