package com.akto.dto.type;

import com.akto.dto.SensitiveParamInfo;

import java.util.HashMap;
import java.util.Map;

public class urlParams {
    Map<Integer, KeyTypes> urlParams = new HashMap<Integer, KeyTypes>();

    public urlParams() {
    }

    public Map<Integer, KeyTypes> getUrlParams() {
        return urlParams;
    }

    public void setUrlParams(Map<Integer, KeyTypes> urlParams) {
        this.urlParams = urlParams;
    }// unit tests for "fillUrlParams" written in TestApiCatalogSync

    public void fillUrlParams(String[] tokenizedUrl, URLTemplate urlTemplate, int apiCollectionId) {
        if (this.getUrlParams() == null) this.setUrlParams(new HashMap<Integer, KeyTypes>());

        SingleTypeInfo.SuperType[] types = urlTemplate.getTypes();
        String url = urlTemplate.getTemplateString();
        String method = urlTemplate.getMethod().name();

        if (tokenizedUrl.length != types.length) return;

        for (int idx = 0; idx < types.length; idx++) {
            SingleTypeInfo.SuperType superType = types[idx];
            if (superType == null) continue;
            Object val = tokenizedUrl[idx];

            if (superType.equals(SingleTypeInfo.SuperType.INTEGER)) {
                val = Integer.parseInt(val.toString());
            } else if (superType.equals(SingleTypeInfo.SuperType.FLOAT)) {
                val = Float.parseFloat(val.toString());
            }

            KeyTypes keyTypes = this.getUrlParams().get(idx);
            if (keyTypes == null) {
                keyTypes = new KeyTypes(new HashMap<SingleTypeInfo.SubType, SingleTypeInfo>(), false);
                this.getUrlParams().put(idx, keyTypes);
            }

            String userId = "";
            keyTypes.process(url, method, -1, false, idx + "", val, userId, apiCollectionId, "", new HashMap<SensitiveParamInfo, Boolean>(), true);

        }
    }
}