package com.akto.dto.type;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.akto.dao.context.Context;
import com.akto.dto.HttpResponseParams;
import com.akto.dto.HttpRequestParams;
import com.akto.dto.SensitiveParamInfo;
import com.akto.dto.traffic.Key;
import com.akto.dto.traffic.TrafficInfo;
import com.akto.dto.type.SingleTypeInfo.ParamId;
import com.akto.dto.type.SingleTypeInfo.SubType;
import com.akto.dto.type.SingleTypeInfo.SuperType;
import com.akto.dto.type.URLMethods.Method;
import com.akto.types.CappedSet;
import com.akto.util.JSONUtils;
import com.akto.util.Pair;
import com.akto.util.Trie;
import com.akto.util.Trie.Node;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestTemplate {

    private com.akto.dto.type.urlParams urlParams = new urlParams();

    public static Logger getLogger() {
        return logger;
    }

    public static long getInsertTime() {
        return insertTime;
    }

    public static void setInsertTime(long insertTime) {
        RequestTemplate.insertTime = insertTime;
    }

    public static long getProcessTime() {
        return processTime;
    }

    public static void setProcessTime(long processTime) {
        RequestTemplate.processTime = processTime;
    }

    public static long getDeleteTime() {
        return deleteTime;
    }

    public static void setDeleteTime(long deleteTime) {
        RequestTemplate.deleteTime = deleteTime;
    }

    public AllParams getAllParams() {
        return allParams;
    }

    public void setAllParams(AllParams allParams) {
        this.allParams = allParams;
    }

    public Trie getKeyTrie() {
        return keyTrie;
    }

    public void setKeyTrie(Trie keyTrie) {
        this.keyTrie = keyTrie;
    }

    public int getMergeTimestamp() {
        return mergeTimestamp;
    }

    public void setMergeTimestamp(int mergeTimestamp) {
        this.mergeTimestamp = mergeTimestamp;
    }

    private static class AllParams {
        int lastKnownParamMapSize = 0;
        Set<String> paramNames = new HashSet<>();

        AllParams() {}

        public void rebuild(Set<String> parameterKeys) {
            lastKnownParamMapSize = -1;

            for(String p: parameterKeys) {
                paramNames.add(getParamName(p));
            }
            lastKnownParamMapSize = parameterKeys.size();
        }

        private static String getParamName(String param) {
            String paramName = param.substring(param.lastIndexOf('#')+1);
            int depth = StringUtils.countMatches(param, '#');
            return depth+"-"+paramName;
        }

    }

    private static final Logger logger = LoggerFactory.getLogger(RequestTemplate.class);

    private Map<String, KeyTypes> parameters;
    private AllParams allParams = new AllParams();
    private Map<String, KeyTypes> headers;
    private Map<Integer, RequestTemplate> responseTemplates;
    private Set<String> userIds = new HashSet<>();
    private TrafficRecorder trafficRecorder = new TrafficRecorder();
    private Trie keyTrie = new Trie();

    public RequestTemplate() {
    }

    public RequestTemplate(
        Map<String,KeyTypes> parameters, 
        Map<Integer, RequestTemplate> responseTemplates, 
        Map<String,KeyTypes> headers,
        TrafficRecorder trafficRecorder
    ) {
        this.setParameters(parameters);
        this.setHeaders(headers);
        this.setResponseTemplates(responseTemplates);
        this.setTrafficRecorder(trafficRecorder);
    }

    private int mergeTimestamp = 0;

    private void add(Set<String> set, String userId) {
        if (set.size() < 10) set.add(userId);
    }

    public Set<String> getParamNames() {
        Set<String> parameterKeys = getParameters().keySet();
        if (parameterKeys.size() != getAllParams().lastKnownParamMapSize) {
            getAllParams().rebuild(parameterKeys);
        }
        return getAllParams().paramNames;
    }

    private void insert(Object obj, String userId, Trie.Node<String, Pair<KeyTypes, Set<String>>> root, String url, String method, int responseCode, String prefix, int apiCollectionId, String rawMessage, Map<SensitiveParamInfo, Boolean> sensitiveParamInfoBooleanMap) {

        prefix += ("#"+root.getPathElem());
        if (prefix.startsWith("#")) {
            prefix = prefix.substring(1);
        }

        if (obj instanceof BasicDBObject) {
            BasicDBObject basicDBObject = (BasicDBObject) obj;
            for(String key: basicDBObject.keySet()) {
                Object value = basicDBObject.get(key);
                Trie.Node<String, Pair<KeyTypes, Set<String>>> curr = root.get(key);

                if (curr == null) {
                    curr = root.getOrCreate(key, new Pair<>(new KeyTypes(new HashMap<>(), false), new HashSet<String>()));
                    // logger.info("creating new node for " + key + " in " + url);
                }

                add(curr.getValue().getSecond(), userId);
                insert(value, userId, curr, url, method, responseCode, prefix, apiCollectionId, rawMessage, sensitiveParamInfoBooleanMap);
            }
        } else if (obj instanceof BasicDBList) {
            for(Object elem: (BasicDBList) obj) {
                Trie.Node<String, Pair<KeyTypes, Set<String>>> listNode = root.getOrCreate("$", new Pair<>(new KeyTypes(new HashMap<>(), false), new HashSet<String>()));
                add(listNode.getValue().getSecond(), userId);
                insert(elem, userId, listNode, url, method, responseCode, prefix, apiCollectionId, rawMessage, sensitiveParamInfoBooleanMap);
            }
        } else {
            //url, method, responseCode, true, header, value, userId
            root.getValue().getFirst().process(url, method, responseCode, false, prefix, obj, userId, apiCollectionId, rawMessage, sensitiveParamInfoBooleanMap, false);
            add(root.getValue().getSecond(), userId);   
        }
    }

    public void processHeaders(Map<String, List<String>> headerPayload, String url, String method, int responseCode, String userId, int apiCollectionId, String rawMessage, Map<SensitiveParamInfo, Boolean> sensitiveParamInfoBooleanMap) {
        for (String header: headerPayload.keySet()) {
            KeyTypes keyTypes = this.getHeaders().get(header);
            if (keyTypes == null) {
                keyTypes = new KeyTypes(new HashMap<>(), false);
                this.getHeaders().put(header, keyTypes);
            }

            for(String value: headerPayload.get(header)) {
                keyTypes.process(url, method, responseCode, true, header, value, userId, apiCollectionId, rawMessage,  sensitiveParamInfoBooleanMap, false);
            }
        }
    }

    public void processTraffic(int timestamp) {
        getTrafficRecorder().incr(timestamp);
    }

    public void recordMessage(String message) {
        if (message != null && message.length() > 0) {
            getTrafficRecorder().recordMessage(message);
        }
    }

    private static long insertTime = 0;
    private static long processTime = 0;
    private static long deleteTime = 0;

    public List<SingleTypeInfo> process2(BasicDBObject payload, String url, String method, int responseCode, String userId, int apiCollectionId, String rawMessage, Map<SensitiveParamInfo, Boolean> sensitiveParamInfoBooleanMap) {
            List<SingleTypeInfo> deleted = new ArrayList<>();
        
            if(getUserIds().size() < 10) getUserIds().add(userId);

            Trie.Node<String, Pair<KeyTypes, Set<String>>> root = this.getKeyTrie().getRoot();

            long s = System.currentTimeMillis();
            // insert(payload, userId, root, url, method, responseCode, "", apiCollectionId);
            setInsertTime(getInsertTime() + (System.currentTimeMillis() - s));
            int now = Context.now();

            Map<String, Set<Object>> flattened = JSONUtils.flatten(payload);
            s = System.currentTimeMillis();
            for(String param: flattened.keySet()) {
                if (getParameters().size() > 1000) {
                    continue;
                }
                KeyTypes keyTypes = getParameters().get(param);
                if (keyTypes == null) {

                    boolean isParentPresent = false;
                    int curr = param.indexOf("#");
                    while (curr < param.length() && curr > 0) {
                        KeyTypes occ = getParameters().get(param.substring(0, curr));
                        if (occ != null && occ.occurrences.containsKey(SingleTypeInfo.DICT)) {
                            isParentPresent = true;
                            break;
                        }
                        curr = param.indexOf("#", curr+1);
                    }


                    if (isParentPresent) {
                        continue;
                    } else {
                        keyTypes = new KeyTypes(new HashMap<>(), false);
                        getParameters().put(param, keyTypes);
                    }
                }

                for (Object obj: flattened.get(param)) {
                    keyTypes.process(url, method, responseCode, false, param, obj, userId, apiCollectionId, rawMessage, sensitiveParamInfoBooleanMap, false);
                }
            }

            setProcessTime(getProcessTime() + (System.currentTimeMillis() - s));

            s = System.currentTimeMillis();
            if (now - getMergeTimestamp() > 60 * 2) {
//                deleted = tryMergeNodesInTrie(url, method, responseCode, apiCollectionId);
                setMergeTimestamp(now);
            }

            setDeleteTime(getDeleteTime() + (System.currentTimeMillis() - s));
            return deleted;
    }
    
    public List<SingleTypeInfo> tryMergeNodesInTrie(String url, String method, int responseCode, int apiCollectionId) {
        List<SingleTypeInfo> deletedInfo = new ArrayList<>();
        List<String> mergedNodes = tryMergeNodes(getKeyTrie().getRoot(), 5, url, method, "", responseCode, apiCollectionId);

        if (mergedNodes == null) {
            mergedNodes = new ArrayList<>();
        }

        for(String prefix: mergedNodes) {
            Iterator<String> iter = getParameters().keySet().iterator();

            while(iter.hasNext()) {
                String paramPath = iter.next();
                if (paramPath.startsWith(prefix)) {
                    deletedInfo.addAll(getParameters().get(paramPath).getAllTypeInfo());
                    iter.remove();
                }
            }
        }

        getKeyTrie().flatten(getParameters());

        return deletedInfo;
    }

    public void buildTrie() {
        this.setKeyTrie(new Trie());

        for(String paramPathStr: this.getParameters().keySet()) {
            try { 
                String[] paramPath = paramPathStr.split("#");

                Node<String, Pair<KeyTypes, Set<String>>> curr = this.getKeyTrie().getRoot();
                for (String path: paramPath) {
                    curr = curr.getOrCreate(path, new Pair<>(new KeyTypes(new HashMap<>(), false), new HashSet<>()));
                }

                curr.getValue().setFirst(this.getParameters().get(paramPathStr));
                curr.getValue().setSecond(this.getParameters().get(paramPathStr).occurrences.values().iterator().next().userIds);
            } catch (Exception e) {
                getLogger().error("exception in " + paramPathStr, e);
            }

        }
    }

    public Map<String,KeyTypes> getParameters() {
        return this.parameters;
    }

    public void setParameters(Map<String,KeyTypes> parameters) {
        this.parameters = parameters;
    }

    public Set<String> getUserIds() {
        return this.userIds;
    }

    public void setUserIds(Set<String> userIds) {
        this.userIds = userIds;
    }

    public Map<Integer, RequestTemplate> getResponseTemplates() {
        return this.responseTemplates;
    }

    public void setResponseTemplates(Map<Integer, RequestTemplate> responseTemplates) {
        this.responseTemplates = responseTemplates;
    }

    public Map<String,KeyTypes> getHeaders() {
        return this.headers;
    }

    public void setHeaders(Map<String,KeyTypes> headers) {
        this.headers = headers;
    }

    public TrafficRecorder getTrafficRecorder() {
        return this.trafficRecorder;
    }

    public void setTrafficRecorder(TrafficRecorder trafficRecorder) {
        this.trafficRecorder = trafficRecorder;
    }

    public RequestTemplate copy() {
        RequestTemplate ret = new RequestTemplate(new HashMap<>(), new HashMap<>(), new HashMap<>(), new TrafficRecorder(new HashMap<>()));
        
        for(String parameter: getParameters().keySet()) {
            ret.getParameters().put(parameter, getParameters().get(parameter).copy());
        }

        for(String header: getHeaders().keySet()) {
            ret.getHeaders().put(header, getHeaders().get(header).copy());
        }

        if (getResponseTemplates() != null) {
            for(int code: getResponseTemplates().keySet()) {
                ret.getResponseTemplates().put(code, getResponseTemplates().get(code).copy());
            }
        }

        ret.setUserIds(new HashSet<>());
        ret.getUserIds().addAll(this.getUserIds());

        ret.setKeyTrie(this.getKeyTrie());
        ret.setTrafficRecorder(this.getTrafficRecorder());

        return ret;
    }

    private double varianceDecrease(Set<Node<String, Pair<KeyTypes, Set<String>>>> nodes, int thresh) {

        if (nodes == null || nodes.size() < 2) return -1;

        Set<String> userIds = new HashSet<>();

        for(Node<String, Pair<KeyTypes, Set<String>>> node: nodes) {
            userIds.addAll(node.getValue().getSecond()); 
        }

        if (userIds.size() < thresh) return -1;

        double currVariance = 0.0;

        for(Node<String, Pair<KeyTypes, Set<String>>> node: nodes) {
            currVariance += Math.pow(1 - (node.getValue().getSecond().size()*1.0f)/userIds.size(), 2);
        }

        double mergeVariance = 0;

        return currVariance - mergeVariance;
    }

    private List<String> tryMergeNodesHelper(Node<String, Pair<KeyTypes, Set<String>>> node, int thresh, String url, String method, String prefix, int responseCode, int apiCollectionId) {

        double diff = varianceDecrease(node.getChildren().keySet(), thresh);

        if (diff < 0.5) return null;

        getLogger().info("flattening trie @" + node.getPathElem() + " in " + url);

        Map<SubType, SingleTypeInfo> occ = new HashMap<>();

        ParamId paramId = new ParamId(url, method, responseCode, false, prefix + "#" + node.getPathElem(),SingleTypeInfo.DICT, apiCollectionId, false);
        occ.put(SingleTypeInfo.DICT, new SingleTypeInfo(paramId, new HashSet<>(), node.getValue().getSecond(), 1, Context.now(), 0, new CappedSet<>(), SingleTypeInfo.Domain.ENUM, SingleTypeInfo.ACCEPTED_MAX_VALUE, SingleTypeInfo.ACCEPTED_MIN_VALUE));
        node.getValue().setFirst(new KeyTypes(occ, false));

        node.getChildren().clear();
        List<String> ret = new ArrayList<>();

        ret.add(prefix);
        return ret;
    }

    public List<String> tryMergeNodes(Node<String, Pair<KeyTypes, Set<String>>> node, int thresh, String url, String method, String prefix, int responseCode, int apiCollectionId) {
        prefix += ("#"+node.getPathElem());
        if (prefix.startsWith("#")) {
            prefix = prefix.substring(1);
        }

        List<String> mergedNodes = tryMergeNodesHelper(node, thresh, url, method, prefix, responseCode, apiCollectionId);
        boolean merged = mergedNodes != null && mergedNodes.size() > 0;

        if (!merged && node.getChildren().size() > 0) {
            for(Node<String, Pair<KeyTypes, Set<String>>> child: node.getChildren().keySet()) {
                List<String> childMerges = tryMergeNodes(child, thresh, url, method, prefix, responseCode, apiCollectionId);
                if (childMerges != null) {
                    if (mergedNodes == null) {
                        mergedNodes = childMerges;
                    } else {
                        mergedNodes.addAll(childMerges);
                    }
                }
            }
        }

        return mergedNodes;
    }
 
    @Override
    public String toString() {
        return "{" +
            " parameters='" + getParameters() + "'" +
            ", responseTemplates='" + getResponseTemplates() + "'" +
            ", headers='" + getHeaders() + "'" +
            ", userIds='" + getUserIds() + "'" +
            "}";
    }

    public void mergeFrom(RequestTemplate that) {
        for(String paramName: that.getParameters().keySet()) {
            KeyTypes thisKeyTypes = this.getParameters().get(paramName);
            KeyTypes thatKeyTypes = that.getParameters().get(paramName);
            if (thisKeyTypes == null) {
                this.getParameters().put(paramName, thatKeyTypes.copy());
            } else {
                thisKeyTypes.merge(thatKeyTypes);
            }
        }

        for(String header: that.getHeaders().keySet()) {
            KeyTypes thisKeyTypes = this.getHeaders().get(header);
            KeyTypes thatKeyTypes = that.getHeaders().get(header);
            if (thisKeyTypes == null) {
                this.getHeaders().put(header, thatKeyTypes.copy());
            } else {
                thisKeyTypes.merge(thatKeyTypes);
            }
        }


        if (that.getResponseTemplates() != null) {

            for(int statusCode: that.getResponseTemplates().keySet()) {
                RequestTemplate thisResp = this.getResponseTemplates().get(statusCode);
                RequestTemplate thatResp = that.getResponseTemplates().get(statusCode);
                if (thisResp == null) {
                    this.getResponseTemplates().put(statusCode, thatResp.copy());
                } else {
                    thisResp.mergeFrom(thatResp);
                }
            }
        }

        this.getUserIds().addAll(that.getUserIds());

        this.getKeyTrie().getRoot().mergeFrom(that.getKeyTrie().getRoot(), MergeTrieKeyFunc.instance);

        this.getTrafficRecorder().mergeFrom(that.getTrafficRecorder());
    }

    private static class MergeTrieKeyFunc implements BiConsumer<Pair<KeyTypes,Set<String>>,Pair<KeyTypes,Set<String>>> {

        public static final MergeTrieKeyFunc instance = new MergeTrieKeyFunc();

        @Override
        public void accept(Pair<KeyTypes, Set<String>> t, Pair<KeyTypes, Set<String>> u) {
            t.getFirst().getOccurrences().putAll(u.getFirst().getOccurrences());
            t.getSecond().addAll(u.getSecond());
        }
            
    }

    public List<SingleTypeInfo> getAllTypeInfo() {
        List<SingleTypeInfo> ret = new ArrayList<>();

        for(KeyTypes k: getParameters().values()) {
            ret.addAll(k.getAllTypeInfo());
        }

        for(KeyTypes k: getHeaders().values()) {
            ret.addAll(k.getAllTypeInfo());
        }

        for (KeyTypes k: urlParams.getUrlParams().values()) {
            ret.addAll(k.getAllTypeInfo());
        }

        if (getResponseTemplates() != null) {
            for(RequestTemplate responseParams: getResponseTemplates().values()) {
                ret.addAll(responseParams.getAllTypeInfo());
            }
        }
        return ret;
    }

    public List<TrafficInfo> removeAllTrafficInfo(int apiCollectionId, String url, Method method, int responseCode) {
        List<TrafficInfo> ret = new ArrayList<>();
        if (!getTrafficRecorder().isEmpty()) {
            Set<String> hoursSince1970 = getTrafficRecorder().getTrafficMapSinceLastSync().keySet();
            int start = Integer.parseInt(hoursSince1970.iterator().next())/24/30;
            int end = start + 1;
            TrafficInfo trafficInfo = new TrafficInfo(new Key(apiCollectionId, url, method, responseCode, start, end), getTrafficRecorder().getTrafficMapSinceLastSync());
            ret.add(trafficInfo);
        }

        getTrafficRecorder().setTrafficMapSinceLastSync(new HashMap<>());

        if (getResponseTemplates() != null && getResponseTemplates().size() > 0) {
            for(Map.Entry<Integer, RequestTemplate> entry: getResponseTemplates().entrySet()) {
                ret.addAll(entry.getValue().removeAllTrafficInfo(apiCollectionId, url, method, entry.getKey()));
            }
        }

        return ret;
    }

    public List<String> removeAllSampleMessage() {
        List<String> ret = new ArrayList<>();
        ret.addAll(getTrafficRecorder().getSampleMessages().get());
        getTrafficRecorder().getSampleMessages().get().clear();
        return ret;
    }

    public static boolean isMergedOnStr(URLTemplate urlTemplate) {
        for(SuperType superType: urlTemplate.getTypes()) {
            if (superType == SuperType.STRING) {
                return true;
            }
        }

        return false;
    }

    private static boolean isWithin20Percent(Set<String> xSet, Set<String> ySet) {

        if (xSet == null) xSet = new HashSet<>();
        if (ySet == null) ySet = new HashSet<>();
                
        int xs = xSet.size();
        int ys = ySet.size();
        if (xs == 0) return ys == 0;
        if (ys == 0) return xs == 0;

        int allowedDiffs = (int) (0.2 * Math.min(xs, ys));

        if (Math.abs(xs - ys) > allowedDiffs) return false;

        int absentInY = 0;
        for(String x: xSet) {
            if (!ySet.contains(x)) {
                absentInY ++;
                if (absentInY > allowedDiffs) return false;
            }
        }

        allowedDiffs = allowedDiffs - absentInY;

        int absentInX = 0;
        for(String y: ySet) {
            if (!xSet.contains(y)) {
                absentInX ++;
                if (absentInX > allowedDiffs) return false;
            }
        }

        return absentInX <= allowedDiffs;
    }


    private static Map<Integer, Set<String>> groupByResponseCode(Set<String> xSet) {
        Map<Integer, Set<String>> ret = new HashMap<>();
        for(String x: xSet) {
            int responseCode = Integer.parseInt(x.split(" ")[0]);
            String param = x.split(" ")[1];

            Set<String> params = ret.get(responseCode);
            if (params == null) {
                params = new HashSet<>();
                ret.put(responseCode, params);
            }

            params.add(param);
        }
        return ret;
    }

    public static boolean compareKeys(Set<String> aReq, Set<String> bReq, URLTemplate mergedUrl) {
        Map<Integer, Set<String>> aParams = groupByResponseCode(aReq);
        Map<Integer, Set<String>> bParams = groupByResponseCode(bReq);

        if (!isMergedOnStr(mergedUrl)) {
            return true;
        }

        if (!isWithin20Percent(aParams.get(-1), bParams.get(-1))) {
            return false;
        }

        aParams.remove(-1);
        bParams.remove(-1);

        for (int aStatus: aParams.keySet()) {
            if (HttpResponseParams.validHttpResponseCode(aStatus)) {
                if (!bParams.containsKey(aStatus)) {
                    return false;
                }
            }
        }

        for (int bStatus: bParams.keySet()) {
            if (HttpResponseParams.validHttpResponseCode(bStatus)) {
                if (!aParams.containsKey(bStatus)) {
                    return false;
                }
            }
        }

        for (int aStatus: aParams.keySet()) {
            if (HttpResponseParams.validHttpResponseCode(aStatus)) {
                Set<String> aResp = aParams.get(aStatus);
                Set<String> bResp = bParams.get(aStatus);

                if (aResp == null || bResp == null) {
                    return false;
                }

                if (!isWithin20Percent(aResp, bResp)) {
                    return false;
                }    
            }
        }

        return true;
    }
        
    private static boolean compareKeys(RequestTemplate a, RequestTemplate b, URLTemplate mergedUrl) {
        if (!isMergedOnStr(mergedUrl)) {
            return true;
        }

        if (!isWithin20Percent(a.getParamNames(), b.getParamNames())) {
            return false;
        }

        for (int aStatus: a.getResponseTemplates().keySet()) {
            if (HttpResponseParams.validHttpResponseCode(aStatus)) {
                if (!b.getResponseTemplates().containsKey(aStatus)) {
                    return false;
                }
            }
        }

        for (int bStatus: b.getResponseTemplates().keySet()) {
            if (HttpResponseParams.validHttpResponseCode(bStatus)) {
                if (!a.getResponseTemplates().containsKey(bStatus)) {
                    return false;
                }
            }
        }

        boolean has2XXStatus = false;
        for (int aStatus: a.getResponseTemplates().keySet()) {
            if (HttpResponseParams.validHttpResponseCode(aStatus)) {
                has2XXStatus = true;
                RequestTemplate aResp = a.getResponseTemplates().get(aStatus);
                RequestTemplate bResp = b.getResponseTemplates().get(aStatus);

                if (aResp == null || bResp == null) {
                    return false;
                }

                if (!isWithin20Percent(aResp.getParamNames(), bResp.getParamNames())) {
                    return false;
                }    
            }
        }

        return has2XXStatus;
    }

    public boolean compare(RequestTemplate that, URLTemplate mergedUrl) {
        return compareKeys(this, that, mergedUrl);
    }

    public static BasicDBObject parseRequestPayload(HttpRequestParams requestParams, String urlWithParams) {
        String reqPayload = requestParams.getPayload();

        return parseRequestPayload(reqPayload, urlWithParams);
    }

    public static BasicDBObject parseRequestPayload(String reqPayload, String urlWithParams) {

        BasicDBObject queryParams = getQueryJSON(urlWithParams);

        if (reqPayload == null || reqPayload.isEmpty()) {
            reqPayload = "{}";
        }

        if(reqPayload.startsWith("[")) {
            reqPayload = "{\"json\": "+reqPayload+"}";
        }


        BasicDBObject payload = null;
        try {
            payload = BasicDBObject.parse(reqPayload);
        } catch (Exception e) {
            payload = BasicDBObject.parse("{}");
        }

        payload.putAll(queryParams.toMap());

        return payload;
    }

    public static BasicDBObject getQueryJSON(String url) {
        BasicDBObject ret = new BasicDBObject();
        if (url == null) {
            return ret;
        }

        String[] splitURL = url.split("\\?");

        if (splitURL.length != 2) {
            return ret;
        }

        String queryParamsStr = splitURL[1];
        if (queryParamsStr == null) {
            return ret;
        }

        String[] queryParams = queryParamsStr.split("&");

        for (String queryParam : queryParams) {
            String[] keyVal = queryParam.split("=");
            if (keyVal.length != 2) {
                continue;
            }
            try {
                keyVal[0] = URLDecoder.decode(keyVal[0], "UTF-8");
                keyVal[1] = URLDecoder.decode(keyVal[1], "UTF-8");
                ret.put(keyVal[0], keyVal[1]);
            } catch (UnsupportedEncodingException e) {
                continue;
            }
        }

        return ret;
    }

    public Map<Integer, KeyTypes> getUrlParams() {
        return urlParams.getUrlParams();
    }

    public void setUrlParams(Map<Integer, KeyTypes> urlParams) {
        this.urlParams = (com.akto.dto.type.urlParams) urlParams;
    }

    // unit tests for "fillUrlParams" written in TestApiCatalogSync
    public void fillUrlParams(String[] tokenizedUrl, URLTemplate urlTemplate, int apiCollectionId) {

        urlParams.fillUrlParams(tokenizedUrl, urlTemplate, apiCollectionId);
    }
}
