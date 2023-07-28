package com.akto.test_editor.filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.akto.dao.test_editor.TestEditorEnums;
import com.akto.log.LoggerMaker;

import com.akto.dao.test_editor.TestEditorEnums.ExtractOperator;
import com.akto.dao.test_editor.TestEditorEnums.OperandTypes;
import com.akto.dto.ApiInfo;
import com.akto.dto.RawApi;
import com.akto.dto.test_editor.DataOperandsFilterResponse;
import com.akto.dto.test_editor.FilterActionRequest;
import com.akto.dto.test_editor.FilterNode;
import com.mongodb.BasicDBObject;

public class Filter {

    private FilterAction filterAction;
    private static final LoggerMaker loggerMaker = new LoggerMaker(Filter.class);

    public Filter() {
        this.filterAction = new FilterAction();
    }
    
    public DataOperandsFilterResponse isEndpointValid(FilterNode node, RawApi rawApi, RawApi testRawApi, ApiInfo.ApiInfoKey apiInfoKey, List<String> matchingKeySet, List<BasicDBObject> contextEntities, boolean keyValOperandSeen, String context, Map<String, Object> varMap, String logId) {

        return new endPointValid(node, rawApi, testRawApi, apiInfoKey, matchingKeySet, contextEntities, keyValOperandSeen, context, varMap, logId).invoke();

    }

    public List<String> evaluateMatchingKeySet(List<String> oldSet, List<String> newMatches, String operand) {
        Set<String> s1 = new HashSet<>();
        if (newMatches == null) {
            return new ArrayList<>();
        }
        if (oldSet == null) {
            // doing this for initial step where oldset would be null, hence assigning initially with newmatches
            s1 = new HashSet<>(newMatches);
        } else {
            s1 = new HashSet<>(oldSet);
        }
        Set<String> s2 = new HashSet<>(newMatches);

        if (operand == "and") {
            s1.retainAll(s2);
        } else {
            s1.addAll(s2);
        }

        List<String> output = new ArrayList<>();
        for (String s: s1) {
            output.add(s);
        }
        return output;
    }

}
