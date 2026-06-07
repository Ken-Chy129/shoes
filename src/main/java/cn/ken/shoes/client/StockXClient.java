package cn.ken.shoes.client;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.ken.shoes.common.SearchTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.excel.StockXOrderExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.util.BrandUtil;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SizeConvertUtil;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.StringTemplate.STR;

@Slf4j
@Component
public class StockXClient {

    @Value("${stockx.clientId}")
    private String clientId;

    @Value("${stockx.redirectUri}")
    private String redirectUri;

    @Value("${stockx.state}")
    private String state;

    @Value("${stockx.clientSecret}")
    private String clientSecret;

    @Value("${stockx.apiKey}")
    private String apiKey;

    @Value("${stockx.authorization}")
    private String authorization;

    private final String expireTime = "2027-01-06T23:22:45+0800";

    public JSONObject queryOrders(String after) {
        JSONObject jsonObject = queryPro(buildOrder(after));
        if (jsonObject == null) {
            return null;
        }
        JSONObject result = new JSONObject();
        List<StockXOrderExcel> orders = new ArrayList<>();
        JSONObject ask = jsonObject.getJSONObject("data").getJSONObject("viewer").getJSONObject("asks");
        JSONObject pageInfo = ask.getJSONObject("pageInfo");
        result.put("hasMore", pageInfo.getBoolean("hasNextPage"));
        result.put("endCursor", pageInfo.getString("endCursor"));
        result.put("orders", orders);
        for (JSONObject edge : ask.getJSONArray("edges").toJavaList(JSONObject.class)) {
            StockXOrderExcel stockXOrderExcel = new StockXOrderExcel();
            JSONObject node = edge.getJSONObject("node");
            stockXOrderExcel.setOrderNumber(node.getString("orderNumber"));
            stockXOrderExcel.setUsd(node.getInteger("amount"));
            stockXOrderExcel.setSoldOn(TimeUtil.formatISO(node.getString("soldOn"), TimeUtil.MM_DD_YYYY));
            JSONObject productVariant = node.getJSONObject("productVariant");
            JSONObject product = productVariant.getJSONObject("product");
            stockXOrderExcel.setTitle(product.getString("title"));
            stockXOrderExcel.setName(product.getString("name"));
            stockXOrderExcel.setStyleId(product.getString("styleId"));
            List<JSONObject> sizeList = productVariant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class);
            for (JSONObject sizeObject : sizeList) {
                String size = sizeObject.getString("size");
                if (size.contains("US")) {
                    stockXOrderExcel.setUsSize(ShoesUtil.getShoesSizeFrom(size));
                } else if (size.contains("EU")) {
                    stockXOrderExcel.setEuSize(ShoesUtil.getShoesSizeFrom(size));
                }
            }
            orders.add(stockXOrderExcel);
        }
        return result;
    }

    public JSONObject queryToDeal(String after) {
        JSONObject jsonObject = queryPro(buildOrder(after));
        if (jsonObject == null) {
            return null;
        }
        JSONObject result = new JSONObject();
        if (jsonObject.getJSONObject("data") == null || jsonObject.getJSONObject("data").getJSONObject("viewer") == null) {
            log.error("queryToDeal error, {}", jsonObject);
            return null;
        }
        JSONObject ask = jsonObject.getJSONObject("data").getJSONObject("viewer").getJSONObject("asks");
        if (!ask.containsKey("edges")) {
            log.error("queryToDeal error, no edges, result:{}", jsonObject);
            return null;
        }
        JSONObject pageInfo = ask.getJSONObject("pageInfo");
        result.put("hasMore", pageInfo.getBoolean("hasNextPage"));
        result.put("endCursor", pageInfo.getString("endCursor"));
        List<JSONObject> nodes = new ArrayList<>();
        result.put("nodes", nodes);
        for (JSONObject edge : ask.getJSONArray("edges").toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            if (Boolean.parseBoolean(node.getString("shippingExtensionRequested"))) {
                continue;
            }
            nodes.add(node);
        }
        return result;
    }

    public void extendItem(String chainId, String orderId) {
        JSONObject body = new JSONObject();
        body.put("operationName", "ExtendShipDate");
        JSONObject variables = new JSONObject();
        body.put("variables", variables);
        variables.put("chainId", chainId);
        variables.put("orderId", orderId);
        variables.put("note", "Seller self-serve shipping extension");
        body.put("query", "mutation ExtendShipDate($chainId: String, $orderId: String) {\n  requestSellerShippingExtension(\n    input: {chainId: $chainId, orderId: $orderId}\n  ) {\n    approved\n    shipByDateExtendedTo\n    __typename\n  }\n}");
        queryPro(body.toJSONString());
    }

    public void createListingV2(List<Pair<String, Integer>> itemList) {
        if (CollectionUtils.isEmpty(itemList)) {
            return;
        }
        JSONObject body = new JSONObject();
        body.put("operationName", "CreateBatchListings");
        JSONObject variables = new JSONObject();
        body.put("variables", variables);
        List<Map<String, Object>> data = new ArrayList<>();
        variables.put("items", data);
        for (Pair<String, Integer> item : itemList) {
            String variantId = item.getKey();
            String amount = String.valueOf(item.getValue());
            data.add(Map.of("variantID", variantId, "amount", amount, "expiresAt", expireTime, "currency", "USD", "quantity", 1));
        }
        body.put("query", "mutation CreateBatchListings($items: [CreateListingBatchInput]) {\n  createBatchListings(input: $items) {\n    id\n    status\n    __typename\n  }\n}");
        JSONObject jsonObject = queryPro(body.toJSONString());
        if (jsonObject == null) {
            return;
        }
        if (jsonObject.containsKey("data")) {
            String batchId = jsonObject.getJSONObject("data").getJSONObject("createBatchListings").getString("id");
            log.info("createListingV2 success, batchId:{}", batchId);
        }
    }

    public boolean deleteItems(List<String> idList) {
        if (idList.isEmpty()) {
            return false;
        }
        JSONObject body = new JSONObject();
        body.put("operationName", "BulkDeleteSellerListings");
        JSONObject variables = new JSONObject();
        List<Map<String, String>> input = new ArrayList<>();
        for (String id : idList) {
            input.add(Map.of("id", id));
        }
        variables.put("input", input);
        body.put("variables", variables);
        body.put("query", "mutation BulkDeleteSellerListings($input: [DeleteListingBatchInput]) {\n  deleteBatchListings(input: $input) {\n    id\n    status\n    completedAt\n    createdAt\n    updatedAt\n  }\n}");
        JSONObject jsonObject = queryPro(body.toJSONString());
        log.info("deleteItems, result:{}", jsonObject);
        return jsonObject != null && jsonObject.containsKey("data") && jsonObject.getJSONObject("data").containsKey("deleteBatchListings");
    }

    public List<StockXPriceDO> queryPrice(String productId) {
        JSONObject jsonObject = queryPro(buildPriceQueryRequest(productId));
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        List<JSONObject> variants = jsonObject.getJSONObject("data").getJSONObject("product").getJSONArray("variants").toJavaList(JSONObject.class);
        String modelNo = jsonObject.getJSONObject("data").getJSONObject("product").getString("styleId");
        if (modelNo == null) {
            log.error("queryPrice.modelNo is null, productId:{}", productId);
            return Collections.emptyList();
        }
        List<StockXPriceDO> result = new ArrayList<>();
        for (JSONObject variant : variants) {
            StockXPriceDO stockXPriceDO = new StockXPriceDO();
            stockXPriceDO.setProductId(productId);
            String variantId = variant.getString("id");
            stockXPriceDO.setVariantId(variantId);
            JSONObject euOption = variant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class).stream().filter(option -> option.getString("type").equals("eu")).findFirst().orElse(null);
            if (euOption == null) {
                log.error("queryPrice.euOption is null, productId:{}, variantId:{}, variant:{}", productId, variantId, variant);
                continue;
            }
            stockXPriceDO.setEuSize(ShoesUtil.getShoesSizeFrom(euOption.getString("size")));
            JSONObject highestBid = variant.getJSONObject("market").getJSONObject("state").getJSONObject("highestBid");
            stockXPriceDO.setSellNowAmount(Optional.ofNullable(highestBid).map(bid -> bid.getInteger("amount")).orElse(0));
            JSONObject guidance = variant.getJSONObject("pricingGuidance").getJSONObject("marketConsensusGuidance").getJSONObject("standardSellerGuidance");
            stockXPriceDO.setEarnMoreAmount(guidance.getInteger("earnMore"));
            stockXPriceDO.setSellFasterAmount(guidance.getInteger("sellFaster"));
            stockXPriceDO.setModelNo(modelNo);
            result.add(stockXPriceDO);
        }
        return result;
    }

    public boolean queryListing(String batchId) {
        String rawResult = HttpUtil.doGet(StockXConfig.GET_LISTING_STATUS.replace("{batchId}", batchId), buildHeaders());
        if (rawResult == null) {
            return false;
        }
        JSONObject result = JSON.parseObject(rawResult);
        String status = result.getString("status");
        JSONObject itemStatuses = result.getJSONObject("itemStatuses");
        if ("COMPLETED".equals(status)) {
            return true;
        }
        return false;
    }

    public String createListing(List<Pair<String, Integer>> items) {
        List<Map<String, Object>> toCreate = new ArrayList<>();
        for (Pair<String, Integer> item : items) {
            String variantId = item.getKey();
            Integer price = item.getValue();
            Map<String, Object> map = new HashMap<>();
            map.put("variantId", variantId);
            map.put("amount", String.valueOf(price));
            map.put("quantity", 1);
            map.put("expiresAt", expireTime);
            toCreate.add(map);
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("items", toCreate);
        String rawResult = HttpUtil.doPost(StockXConfig.CREATE_LISTING, jsonObject.toJSONString(), buildHeaders());
        if (rawResult == null) {
            return null;
        }
        JSONObject result = JSON.parseObject(rawResult);
        String batchId = result.getString("batchId");
        String totalItems = result.getString("totalItems");
        return batchId;
    }

    public boolean refreshToken() {
        JSONObject params = new JSONObject();
        params.put("grant_type", "refresh_token");
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("audience", "gateway.stockx.com");
        params.put("refresh_token", StockXConfig.CONFIG.getRefreshToken());
        String rawResult = HttpUtil.doPost(StockXConfig.TOKEN, params.toJSONString(), Headers.of("content-type", "application/x-www-form-urlencoded"));
        if (rawResult == null) {
            return false;
        }
        JSONObject result = JSON.parseObject(rawResult);
        if (result.containsKey("error")) {
            log.error("initToken error, msg:{}, description:{}", result.getString("error"), result.getString("error_description"));
            return false;
        }
        Integer expiresIn = result.getInteger("expires_in");
        LocalDateTime time = LocalDateTime.now().plusSeconds(expiresIn);
        StockXConfig.CONFIG.setExpireTime(time.format(TimeUtil.YYYY_MM_DD_HH_MM_SS));
        StockXConfig.CONFIG.setAccessToken(result.getString("access_token"));
        StockXConfig.saveOAuthConfig();
        return true;
    }

    public boolean initToken() {
        JSONObject params = new JSONObject();
        params.put("grant_type", "authorization_code");
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("code", getCode());
        params.put("redirect_uri", redirectUri);
        String rawResult = HttpUtil.doPost(StockXConfig.TOKEN, params.toJSONString(), Headers.of("content-type", "application/x-www-form-urlencoded"));
        if (rawResult == null) {
            return false;
        }
        JSONObject result = JSON.parseObject(rawResult);
        if (result.containsKey("error")) {
            log.error("initToken error, msg:{}, description:{}", result.getString("error"), result.getString("error_description"));
            return false;
        }
        StockXConfig.CONFIG.setAccessToken(result.getString("access_token"));
        StockXConfig.CONFIG.setRefreshToken(result.getString("refresh_token"));
        StockXConfig.CONFIG.setIdToken(result.getString("id_token"));
        Integer expiresIn = result.getInteger("expires_in");
        LocalDateTime time = LocalDateTime.now().plusSeconds(expiresIn);
        StockXConfig.CONFIG.setExpireTime(time.format(TimeUtil.YYYY_MM_DD_HH_MM_SS));
        StockXConfig.saveOAuthConfig();
        Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    Thread.sleep(11 * 60 * 60 * 1000);
                    refreshToken();
                    log.info("refresh token");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        return true;
    }

    public String getCode() {
        return HttpUtil.doGet(StockXConfig.CALLBACK);
    }

    public String getAuthorizeUrl() {
        return StockXConfig.AUTHORIZE.replace("{clientId}", clientId).replace("{redirectUri}", redirectUri).replace("{state}", state);
    }

    public Pair<Integer, List<StockXPriceExcel>> searchItemWithPrice(String query, Integer pageIndex, String sort, String searchType, String country) {
        if (pageIndex == null) {
            pageIndex = 1;
        }
        SearchTypeEnum searchTypeEnum = SearchTypeEnum.from(searchType);
        if (searchTypeEnum == null) {
            return Pair.of(0, Collections.emptyList());
        }
        String finalCountry = country != null ? country : "HK";
        JSONObject jsonObject = queryPro(buildItemSearchRequest(query, pageIndex, sort, finalCountry));
        if (jsonObject == null) {
            return Pair.of(0, Collections.emptyList());
        }
        JSONObject data = jsonObject.getJSONObject("data");
        if (data == null || data.getJSONObject("browse") == null || data.getJSONObject("browse").getJSONObject("results") == null) {
            log.error("searchItemWithPrice unexpected response, query:{}, response:{}", query, jsonObject.toJSONString());
            return Pair.of(0, Collections.emptyList());
        }
        JSONObject results = data.getJSONObject("browse").getJSONObject("results");
        JSONObject pageInfo = results.getJSONObject("pageInfo");
        Integer pageCount = pageInfo.getInteger("pageCount");
        if (pageCount == null) {
            pageCount = (int) Math.ceil(pageInfo.getDouble("total") / pageInfo.getInteger("limit"));
        }
        List<JSONObject> itemList = results.getJSONArray("edges").toJavaList(JSONObject.class);
        List<StockXPriceExcel> result = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(itemList.size());
        for (JSONObject item : itemList) {
            Thread.startVirtualThread(() -> {
                try {
                    JSONObject node = item.getJSONObject("node");
                    String title = node.getString("title");
                    String urlKey = node.getString("urlKey");
                    List<StockXPriceExcel> itemResult = fetchItemDetail(urlKey, title, searchTypeEnum, finalCountry);
                    result.addAll(itemResult);
                } catch (Exception e) {
                    log.error("searchItemWithPrice fetchItemDetail error", e);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Pair.of(pageCount, result);
    }

    private List<StockXPriceExcel> fetchItemDetail(String urlKey, String title, SearchTypeEnum searchTypeEnum, String country) {
        JSONObject[] responses = new JSONObject[2];
        CountDownLatch detailLatch = new CountDownLatch(2);
        Thread.startVirtualThread(() -> {
            try {
                responses[0] = queryPro(buildGetProductRequest(urlKey));
            } finally {
                detailLatch.countDown();
            }
        });
        Thread.startVirtualThread(() -> {
            try {
                responses[1] = queryPro(buildGetMarketDataRequest(urlKey, country));
            } finally {
                detailLatch.countDown();
            }
        });
        try {
            detailLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        JSONObject productResp = responses[0];
        JSONObject marketResp = responses[1];
        if (productResp == null || marketResp == null) {
            log.info("fetchItemDetail response is null, urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        JSONObject productData = productResp.getJSONObject("data");
        JSONObject marketData = marketResp.getJSONObject("data");
        if (productData == null || productData.getJSONObject("product") == null
                || marketData == null || marketData.getJSONObject("product") == null) {
            log.info("fetchItemDetail product is null, urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        JSONObject product = productData.getJSONObject("product");
        JSONObject marketProduct = marketData.getJSONObject("product");
        String modelNo = product.getString("styleId");
        if (StrUtil.isBlank(modelNo)) {
            log.info("缺少货号，urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        JSONArray productVariants = product.getJSONArray("variants");
        JSONArray marketVariants = marketProduct.getJSONArray("variants");
        if (CollectionUtils.isEmpty(productVariants) || CollectionUtils.isEmpty(marketVariants)) {
            log.info("缺少尺码，urlKey:{}", urlKey);
            return Collections.emptyList();
        }
        Map<String, JSONObject> marketVariantMap = new HashMap<>();
        for (JSONObject mv : marketVariants.toJavaList(JSONObject.class)) {
            marketVariantMap.put(mv.getString("id"), mv);
        }
        List<StockXPriceExcel> itemResult = new ArrayList<>();
        for (JSONObject variant : productVariants.toJavaList(JSONObject.class)) {
            String variantId = variant.getString("id");
            JSONObject sizeChart = variant.getJSONObject("sizeChart");
            if (sizeChart == null) {
                continue;
            }
            JSONObject marketVariant = marketVariantMap.get(variantId);
            if (marketVariant == null) {
                continue;
            }
            StockXPriceExcel excel = new StockXPriceExcel();
            excel.setTitle(title);
            excel.setId(variantId);
            excel.setUk(urlKey);
            excel.setModelNo(modelNo);
            Map<String, String> sizeMap = sizeChart.getJSONArray("displayOptions").toJavaList(JSONObject.class)
                    .stream().collect(Collectors.toMap(o -> o.getString("type"), o -> o.getString("size")));
            excel.setEuSize(ShoesUtil.getShoesSizeFrom(sizeMap.get("eu")));
            excel.setUsmSize(getUsSize(searchTypeEnum, sizeMap));
            JSONObject mvMarket = marketVariant.getJSONObject("market");
            if (mvMarket != null && mvMarket.getJSONObject("state") != null) {
                JSONObject state = mvMarket.getJSONObject("state");
                excel.setPrice(Optional.ofNullable(state.getJSONObject("lowestAsk")).map(a -> a.getInteger("amount")).orElse(0));
                excel.setPurchasePrice(Optional.ofNullable(state.getJSONObject("highestBid")).map(b -> b.getInteger("amount")).orElse(0));
            }
            if (mvMarket != null && mvMarket.getJSONObject("salesInformation") != null) {
                excel.setLast72HoursSales(Optional.ofNullable(mvMarket.getJSONObject("salesInformation").getInteger("salesLast72Hours")).orElse(0));
            }
            itemResult.add(excel);
        }
        return itemResult;
    }

    private String getUsSize(SearchTypeEnum searchTypeEnum, Map<String, String> sizeMap) {
        return searchTypeEnum == SearchTypeEnum.SHOES ? getUsShoesSize(sizeMap) : getUsClothesSize(sizeMap);
    }

    private String getUsShoesSize(Map<String, String> sizeMap) {
        String usSize;
        usSize = ShoesUtil.getShoesSizeFrom(sizeMap.get("us"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        usSize = ShoesUtil.getShoesSizeFrom(sizeMap.get("us m"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        return ShoesUtil.getShoesSizeFrom(sizeMap.get("us w"));
    }

    private String getUsClothesSize(Map<String, String> sizeMap) {
        String usSize;
        usSize = ShoesUtil.getClothesSize(sizeMap.get("us"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        usSize = ShoesUtil.getClothesSize(sizeMap.get("us m"));
        if (StrUtil.isNotBlank(usSize)) {
            return usSize;
        }
        return ShoesUtil.getClothesSize(sizeMap.get("us w"));
    }

    public List<BrandDO> queryBrands() {
        JSONObject jsonObject = queryPro(buildBrandQueryRequest("nike", 1, 1));
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        List<JSONObject> quickList = jsonObject.getJSONObject("data").getJSONObject("browse").getJSONObject("filtersConfig").getJSONArray("quick").toJavaList(JSONObject.class);
        JSONObject brandJson = quickList.stream().filter(json -> json.getString("name").equals("BRANDS")).findFirst().orElse(null);
        if (brandJson == null) {
            return Collections.emptyList();
        }
        List<BrandDO> result = new ArrayList<>();
        for (JSONObject option : brandJson.getJSONArray("options").toJavaList(JSONObject.class)) {
            BrandDO brand = new BrandDO();
            brand.setName(option.getString("value"));
            brand.setTotal(option.getInteger("count"));
            brand.setCrawlCnt(Math.min(brand.getTotal(), 1000));
            brand.setNeedCrawl(true);
            brand.setPlatform("stockx");
            result.add(brand);
        }
        return result;
    }

    private String buildItemSearchRequest(String query, Integer index, String sort, String country) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "getDiscoveryData");
        JSONObject variables = new JSONObject();
        variables.put("country", country);
        variables.put("currency", "USD");
        variables.put("flow", "SEARCH_RESULTS");
        variables.put("market", country);
        variables.put("page", Map.of("index", index, "limit", 40));
        variables.put("unifiedDiscoveryEnabled", false);
        variables.put("filters", List.of());
        variables.put("query", query);
        variables.put("sort", Map.of("id", sort));
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject();
        JSONObject persistedQuery = new JSONObject();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "a425201c8e4ccc83ecad211836645be32d6306ad42894b34f2a4b15de3408d20");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);
        return requestJson.toJSONString();
    }

    private String buildGetProductRequest(String urlKey) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "GetProduct");
        JSONObject variables = new JSONObject();
        variables.put("id", urlKey);
        variables.put("skipBreadcrumbs", true);
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject();
        JSONObject persistedQuery = new JSONObject();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "9e0faa98b5745bd79ef47e2479239514b41084c29a86d3f6dacce68543281914");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);
        return requestJson.toJSONString();
    }

    private String buildGetMarketDataRequest(String urlKey, String country) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "GetMarketData");
        JSONObject variables = new JSONObject();
        variables.put("id", urlKey);
        variables.put("currencyCode", "USD");
        variables.put("marketName", country);
        variables.put("viewerContext", "BUYER");
        requestJson.put("variables", variables);
        JSONObject extensions = new JSONObject();
        JSONObject persistedQuery = new JSONObject();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "589955a4e8c0e714c09999d857089ebc54569d0fe216e5e8538cade33572eb16");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);
        return requestJson.toJSONString();
    }

    private String buildBrandQueryRequest(String brand, Integer index, Integer limit) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "getDiscoveryData");
        requestJson.put("query", "fragment FiltersFragment on BrowseFilter {\n  id\n  name\n  type\n  ... on BrowseFilterTree {\n    isCollapsed\n    multiSelectEnabled\n    options {\n      id\n      name\n      count\n      selected\n      children\n      level\n      value\n    }\n  }\n  ... on BrowseFilterList {\n    isCollapsed\n    multiSelectEnabled\n    listFilterStyle: style\n    options {\n      id\n      name\n      count\n      selected\n      value\n    }\n  }\n  ... on BrowseFilterBoolean {\n    id\n    name\n    type\n    selected\n    booleanFilterStyle: style\n  }\n  ... on BrowseFilterRange {\n    id\n    isCollapsed\n    name\n    type\n    minimum {\n      value\n    }\n    maximum {\n      value\n    }\n  }\n  ... on BrowseFilterColor {\n    id\n    isCollapsed\n    name\n    type\n    options {\n      name\n      value\n      count\n      selected\n      swatchColor\n      borderColor\n    }\n  }\n}\n\nquery getDiscoveryData($country: String!, $currency: CurrencyCode, $filters: [BrowseFilterInput], $flow: BrowseFlow, $market: String, $query: String, $sort: BrowseSortInput, $page: BrowsePageInput, $enableOpenSearch: Boolean) {\n  browse(\n    filters: $filters\n    flow: $flow\n    sort: $sort\n    page: $page\n    market: $market\n    query: $query\n    experiments: {ads: {enabled: true}, dynamicFilter: {enabled: true}, dynamicFilterDefinitions: {enabled: true}, multiselect: {enabled: true}, openSearch: {enabled: $enableOpenSearch}}\n  ) {\n    filtersConfig {\n      quick {\n        ...FiltersFragment\n      }\n      advanced {\n        ...FiltersFragment\n      }\n    }\n    results {\n      edges {\n        isAd\n        adIdentifier\n        adServiceLevel\n        adInventoryId\n        objectId\n        node {\n          __typename\n          ... on Variant {\n            id\n            favorite\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            product {\n              id\n              name\n              urlKey\n              title\n              brand\n              gender\n              description\n              model\n              condition\n              productCategory\n              browseVerticals\n              listingType\n              media {\n                thumbUrl\n                smallImageUrl\n              }\n              traits(filterTypes: [RELEASE_DATE]) {\n                name\n                value\n              }\n            }\n            sizeChart {\n              baseSize\n              baseType\n              displayOptions {\n                size\n                type\n              }\n            }\n          }\n          ... on Product {\n            id\n            name\n            urlKey\n            title\n            brand\n            description\n            model\n            condition\n            productCategory\n            browseVerticals\n            listingType\n            favorite\n            media {\n              thumbUrl\n              smallImageUrl\n            }\n            traits(filterTypes: [RELEASE_DATE]) {\n              name\n              value\n            }\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            variants {\n              id\n            }\n          }\n        }\n      }\n      pageInfo {\n        limit\n        page\n        pageCount\n        queryId\n        queryIndex\n        total\n      }\n    }\n    seo {\n      title\n      blurb\n      richBlurb\n      meta {\n        name\n        value\n      }\n    }\n    sort {\n      id\n      name\n      description\n      seoUrlKey\n      short\n    }\n  }\n}");
        JSONObject variables = new JSONObject();
        variables.put("country", "HK");
        variables.put("currency", "USD");
        variables.put("enableOpenSearch", false);
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("category", List.of("shoes", "sneakers")));
        filters.add(new Filter("brand", List.of(brand)));
        variables.put("filters", filters);
        variables.put("flow", "CATEGORY");
        variables.put("market", "HK");
        variables.put("page", Map.of("index", index, "limit", limit));
        variables.put("sort", Map.of("id", "most-active"));
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildPriceQueryRequest(String id) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "ProductVariants");
        requestJson.put("query", "query ProductVariants($id: String!, $currency: CurrencyCode!, $country: String!, $market: String!, $skipFlexEligible: Boolean!, $skipGuidance: Boolean!) {\n  product(id: $id) {\n    id\n    styleId\n    traits {\n      value\n      name\n     }\n    variants {\n      id\n      isFlexEligible @skip(if: $skipFlexEligible)\n      sizeChart {\n        displayOptions {\n          size\n          type\n          }\n        }\n      market(currencyCode: $currency) {\n        state(country: $country, market: $market) {\n          highestBid {\n            amount\n            }\n          }\n        }\n      pricingGuidance(country: $country, market: $market, currencyCode: $currency) @skip(if: $skipGuidance) {\n        marketConsensusGuidance {\n          standardSellerGuidance {\n            sellFaster\n            earnMore\n            }\n          }\n        }\n      }\n      }\n}");
        JSONObject variables = new JSONObject();
        variables.put("id", id);
        variables.put("currency", "USD");
        variables.put("country", "HK");
        variables.put("market", "HK");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", false);
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildOrder(String after) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "ViewerAsks");
        requestJson.put("query", "query ViewerAsks($query: String, $after: String, $pageSize: Int, $currencyCode: CurrencyCode, $state: AsksGeneralState, $filters: AsksFiltersInput, $sort: AsksSortInput, $order: AscDescOrderInput, $country: String!, $market: String!, $skipGuidance: Boolean = true, $skipFlexEligible: Boolean = true, $includeHasAttributedAd: Boolean = false) {\n  viewer {\n    asks(\n      query: $query\n      after: $after\n      first: $pageSize\n      includeHasAttributedAd: $includeHasAttributedAd\n      currencyCode: $currencyCode\n      state: $state\n      filters: $filters\n      sort: $sort\n      order: $order\n    ) {\n      pageInfo {\n        endCursor\n        hasNextPage\n        totalCount\n        __typename\n      }\n      edges {\n        node {\n          ...AskAttributes\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n}\n\nfragment AskAttributes on Ask {\n  id\n  amount\n  created\n  bidAskSpread\n  currentCurrency\n  expires\n  soldOn\n  orderNumber\n  state\n  dateToShipBy\n  authCenter\n  inventoryType\n  shippingExtensionRequested\n  associatedAutomation {\n    status\n    id\n    fields {\n      price\n      __typename\n    }\n    __typename\n  }\n  pricingGuidance(country: $country, market: $market) @skip(if: $skipGuidance) {\n    marketConsensusGuidance {\n      standardSellerGuidance {\n        earnMore\n        sellFaster\n        beatUSPrice\n        marketRange {\n          idealMinPrice\n          idealMaxPrice\n          fairMinPrice\n          fairMaxPrice\n          __typename\n        }\n        __typename\n      }\n      flexSellerGuidance {\n        earnMore\n        sellFaster\n        beatUSPrice\n        marketRange {\n          idealMinPrice\n          idealMaxPrice\n          fairMinPrice\n          fairMaxPrice\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n  shipment {\n    id\n    bulk\n    deleted\n    displayId\n    trackingNumber\n    trackingUrl\n    deliveryDate\n    commercialInvoiceUrl\n    documents {\n      sellerShippingInstructions\n      sellerShippingInstructionsThermal\n      __typename\n    }\n    __typename\n  }\n  productVariant {\n    id\n    isFlexEligible @skip(if: $skipFlexEligible)\n    traits {\n      size\n      sizeDescriptor\n      __typename\n    }\n    sizeChart {\n      displayOptions {\n        size\n        __typename\n      }\n      baseType\n      __typename\n    }\n    market(currencyCode: $currencyCode) {\n      state(country: $country, market: $market) {\n        bidInventoryTypes {\n          standard {\n            highest {\n              amount\n              chainId\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        askServiceLevels {\n          standard {\n            lowest {\n              amount\n              __typename\n            }\n            __typename\n          }\n          expressStandard {\n            lowest {\n              amount\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    product {\n      id\n      name\n      styleId\n      model\n      title\n      productCategory\n      contentGroup\n      browseVerticals\n      primaryCategory\n      minimumBid(currencyCode: $currencyCode)\n      traits {\n        name\n        value\n        __typename\n      }\n      taxInformation {\n        id\n        code\n        __typename\n      }\n      media {\n        thumbUrl\n        __typename\n      }\n      sizeDescriptor\n      hazardousMaterial {\n        lithiumIonBucket\n        __typename\n      }\n      lockSelling\n      listingType\n      __typename\n    }\n    __typename\n  }\n  hasAttributedAd\n  __typename\n}");
        JSONObject variables = new JSONObject();
        variables.put("country", "HK");
        variables.put("market", "HK");
        variables.put("pageSize", 30);
        variables.put("sort", "MATCHED_AT");
        variables.put("order", "DESC");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", true);
        variables.put("includeHasAttributedAd", true);
        variables.put("query", "");
        if (StrUtil.isNotBlank(after)) {
            variables.put("after", after);
        }
        JSONObject filters = new JSONObject();
        filters.put("vertical", Map.of("in", List.of()));
        filters.put("shipmentId", Map.of("in", List.of()));
        filters.put("lowestAsk", new JSONObject());
        filters.put("expired", new JSONObject());
        filters.put("includeBulkShipmentItems", new JSONObject());
        filters.put("statesList", Map.of("in", List.of(410, 411, 415)));
        filters.put("productId", Map.of("in", List.of()));
        filters.put("inventoryType", Map.of("in", List.of("STANDARD")));
        variables.put("filters", filters);
        variables.put("state", "PENDING");
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private JSONObject queryPro(String body) {
        LimiterHelper.limitStockxSecond();
        String rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, body, buildProHeaders());
        if (rawResult == null) {
            return null;
        }
        JSONObject jsonObject;
        try {
            jsonObject = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryPro response非JSON, response:{}", rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        if (jsonObject.containsKey("status")) {
            log.error("queryHotItemsByBrandWithPrice error, msg:{}", jsonObject.getString("message"));
            return null;
        }
        if (jsonObject.containsKey("blockScript")) {
            log.error("queryHotItemsByBrandWithPrice|查询被拦截");
            return null;
        }
        return jsonObject;
    }

    private Headers buildProHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "accept", "application/json",
                "authorization", getAuthorization(),
                "apollographql-client-name", "Iron",
                "apollographql-client-version", "2026.05.03.00",
                "app-platform", "Iron",
                "app-version", "2026.05.03.00",
                "selected-country", "HK",
                "origin", "https://stockx.com",
                "referer", "https://stockx.com/",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0"
        );
    }

    private Headers buildHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "Authorization", getAuthorization(),
                "x-api-key", apiKey
        );
    }

    private String getAuthorization() {
        if (StrUtil.isNotBlank(StockXConfig.CONFIG.getAccessToken())) {
            return StockXConfig.CONFIG.getAccessToken();
        }
        return authorization;
    }

    /**
     * 使用 persisted query 格式查询在售商品（区分库存类型）
     */
    public JSONObject querySellingItemsByInventoryType(String inventoryType, Integer pageNumber) {
        return doQuerySellingItemsByInventoryType(inventoryType, pageNumber, buildViperHeaders(), "US");
    }

    private JSONObject doQuerySellingItemsByInventoryType(String inventoryType, Integer pageNumber, Headers headers, String country) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "SellerListings");

        JSONObject variables = new JSONObject();
        variables.put("skipGuidance", false);
        variables.put("skipFlexEligible", true);
        variables.put("pageSize", 100);
        variables.put("sort", "CREATED_AT");
        variables.put("order", "DESC");
        variables.put("country", country);
        variables.put("market", country);
        variables.put("pageNumber", pageNumber != null ? pageNumber : 1);
        variables.put("currencyCode", "USD");

        JSONObject filters = new JSONObject();
        filters.put("spreadCurrency", "USD");
        filters.put("inventoryType", Map.of("in", List.of(inventoryType)));
        if ("CUSTODIAL".equals(inventoryType)) {
            filters.put("listingStatus", Map.of("in", List.of("ACTIVE", "ON_HOLD")));
        } else {
            filters.put("listingStatus", Map.of("in", List.of("ACTIVE")));
            filters.put("listingType", Map.of("in", List.of("VERIFIED")));
        }
        variables.put("filters", filters);
        requestJson.put("variables", variables);

        JSONObject extensions = new JSONObject();
        JSONObject persistedQuery = new JSONObject();
        persistedQuery.put("version", 1);
        persistedQuery.put("sha256Hash", "ab3842937c803f03d6296570ef963b062e5b01d2e7695ccf3c7aff590a17abef");
        extensions.put("persistedQuery", persistedQuery);
        requestJson.put("extensions", extensions);

        JSONObject jsonObject = queryPro(requestJson.toJSONString(), headers);
        if (jsonObject == null) {
            return null;
        }

        // 检查返回结构
        if ("Unauthorized".equals(jsonObject.getString("message"))) {
            log.error("querySellingItemsByInventoryType|Token已过期或无效，请更新Token");
            JSONObject errorResult = new JSONObject();
            errorResult.put("_unauthorized", true);
            return errorResult;
        }
        JSONObject data = jsonObject.getJSONObject("data");
        if (data == null) {
            log.error("querySellingItemsByInventoryType response has no data field, response:{}", jsonObject.toJSONString());
            return null;
        }
        JSONObject viewer = data.getJSONObject("viewer");
        if (viewer == null) {
            log.error("querySellingItemsByInventoryType response has no viewer field, response:{}", jsonObject.toJSONString());
            return null;
        }
        JSONObject sellerListings = viewer.getJSONObject("sellerListings");
        if (sellerListings == null) {
            log.error("querySellingItemsByInventoryType response has no sellerListings field, response:{}", jsonObject.toJSONString());
            return null;
        }

        JSONObject result = new JSONObject();
        JSONObject pageInfo = sellerListings.getJSONObject("pageInfo");
        result.put("hasMore", pageInfo.getBoolean("hasNextPage"));

        List<JSONObject> items = new ArrayList<>();
        result.put("items", items);
        for (JSONObject edge : sellerListings.getJSONArray("edges").toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            JSONObject productVariant = node.getJSONObject("productVariant");
            if (productVariant == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("id", node.getString("id"));
            item.put("amount", node.getInteger("amount"));
            item.put("isExpired", node.getBoolean("isExpired"));
            item.put("variantId", productVariant.getString("id"));

            JSONObject product = productVariant.getJSONObject("product");
            if (product == null) {
                continue;
            }
            item.put("styleId", product.getString("styleId"));
            item.put("productName", product.getString("model"));

            String size = Optional.ofNullable(productVariant.getJSONObject("traits"))
                    .map(traits -> traits.getString("size"))
                    .orElse(null);
            item.put("size", size);

            Optional<String> euSizeFromChart = Optional.ofNullable(productVariant.getJSONObject("sizeChart"))
                    .map(sc -> sc.getJSONArray("displayOptions"))
                    .map(arr -> arr.toJavaList(JSONObject.class))
                    .flatMap(list -> list.stream()
                            .filter(option -> option.getString("size").startsWith("EU"))
                            .findFirst()
                            .map(euOption -> ShoesUtil.getShoesSizeFrom(euOption.getString("size"))));
            if (euSizeFromChart.isPresent()) {
                item.put("euSize", euSizeFromChart.get());
            } else if (size != null) {
                String primaryCategory = product.getString("primaryCategory");
                String brand = BrandUtil.extractStockXBrand(product.getString("model"));
                if (brand == null) {
                    brand = BrandUtil.extractStockXBrand(primaryCategory);
                }
                if (brand != null) {
                    String euSize = SizeConvertUtil.getStockXEuSize(brand, size);
                    if (euSize != null) {
                        item.put("euSize", euSize);
                    }
                }
            }

            // 解析两种最低价
            Optional.ofNullable(productVariant.getJSONObject("market"))
                    .map(m -> m.getJSONObject("state"))
                    .map(s -> s.getJSONObject("askServiceLevels"))
                    .ifPresent(askLevels -> {
                        Optional.ofNullable(askLevels.getJSONObject("standard"))
                                .map(s -> s.getJSONObject("lowest"))
                                .map(l -> l.getInteger("amount"))
                                .ifPresent(amount -> item.put("standardLowest", amount));
                        Optional.ofNullable(askLevels.getJSONObject("expressStandard"))
                                .map(s -> s.getJSONObject("lowest"))
                                .map(l -> l.getInteger("amount"))
                                .ifPresent(amount -> item.put("expressStandardLowest", amount));
                    });

            items.add(item);
        }
        return result;
    }

    /**
     * V2 API 批量更新 listing 价格
     */
    public String batchUpdateListings(List<Map<String, String>> items) {
        JSONObject body = new JSONObject();
        body.put("items", items);
        String rawResult = HttpUtil.doPost(StockXConfig.BATCH_UPDATE_LISTING, body.toJSONString(), buildHeaders());
        if (rawResult == null) {
            log.error("batchUpdateListings failed, response is null");
            return null;
        }
        JSONObject result;
        try {
            result = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("batchUpdateListings response非JSON, totalItems:{}, response:{}", items.size(), rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        String batchId = result.getString("batchId");
        if (batchId == null) {
            log.warn("batchUpdateListings response无batchId, totalItems:{}, response:{}", items.size(), rawResult);
        } else {
            log.info("batchUpdateListings success, batchId:{}, totalItems:{}", batchId, items.size());
        }
        return batchId;
    }

    /**
     * V2 API 查询批量更新状态
     */
    public JSONObject queryBatchUpdateStatus(String batchId) {
        String url = StockXConfig.BATCH_UPDATE_LISTING_STATUS.replace("{batchId}", batchId);
        String rawResult = HttpUtil.doGet(url, buildHeaders());
        if (rawResult == null) {
            return null;
        }
        try {
            return JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryBatchUpdateStatus response非JSON, batchId:{}, response:{}", batchId, rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
    }

    private JSONObject queryPro(String body, Headers headers) {
        LimiterHelper.limitStockxSecond();
        String rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, body, headers);
        if (rawResult == null) {
            return null;
        }
        JSONObject jsonObject;
        try {
            jsonObject = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryPro response非JSON, response:{}", rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        if ("Unauthorized".equals(jsonObject.getString("message"))) {
            log.error("queryPro|Token已过期或无效，请更新Token");
            return jsonObject;
        }
        if (jsonObject.containsKey("status")) {
            log.error("queryPro error, msg:{}", jsonObject.getString("message"));
            return null;
        }
        if (jsonObject.containsKey("blockScript")) {
            log.error("queryPro|查询被拦截");
            return null;
        }
        return jsonObject;
    }

    private Headers buildViperHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "authorization", getAuthorization(),
                "apollographql-client-name", "Viper",
                "apollographql-client-version", "2026.05.05.00",
                "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
                "origin", "https://pro.stockx.com",
                "referer", "https://pro.stockx.com/"
        );
    }

    // ==================== 多账号支持：带 StockXAccount 参数的方法重载 ====================

    private Headers buildHeaders(StockXAccount account) {
        return Headers.of(
                "Content-Type", "application/json",
                "Authorization", account.getAuthorization().strip(),
                "x-api-key", account.getApiKey().strip()
        );
    }

    private Headers buildViperHeaders(StockXAccount account) {
        return Headers.of(
                "Content-Type", "application/json",
                "authorization", account.getAuthorization().strip(),
                "apollographql-client-name", "Viper",
                "apollographql-client-version", "2026.05.05.00",
                "User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
                "origin", "https://pro.stockx.com",
                "referer", "https://pro.stockx.com/"
        );
    }

    public JSONObject querySellingItemsByInventoryType(String inventoryType, Integer pageNumber, StockXAccount account) {
        String country = account.getCountry() != null ? account.getCountry() : "US";
        return doQuerySellingItemsByInventoryType(inventoryType, pageNumber, buildViperHeaders(account), country);
    }

    public String batchUpdateListings(List<Map<String, String>> items, StockXAccount account) {
        JSONObject body = new JSONObject();
        body.put("items", items);
        String rawResult = HttpUtil.doPost(StockXConfig.BATCH_UPDATE_LISTING, body.toJSONString(), buildHeaders(account));
        if (rawResult == null) {
            log.error("batchUpdateListings[{}] failed, response is null", account.getName());
            return null;
        }
        JSONObject result;
        try {
            result = JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("batchUpdateListings[{}] response非JSON, totalItems:{}, response:{}", account.getName(), items.size(), rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
        String batchId = result.getString("batchId");
        if (batchId == null) {
            log.warn("batchUpdateListings[{}] response无batchId, totalItems:{}, response:{}", account.getName(), items.size(), rawResult);
        } else {
            log.info("batchUpdateListings[{}] success, batchId:{}, totalItems:{}", account.getName(), batchId, items.size());
        }
        return batchId;
    }

    public JSONObject queryBatchUpdateStatus(String batchId, StockXAccount account) {
        String url = StockXConfig.BATCH_UPDATE_LISTING_STATUS.replace("{batchId}", batchId);
        String rawResult = HttpUtil.doGet(url, buildHeaders(account));
        if (rawResult == null) {
            return null;
        }
        try {
            return JSON.parseObject(rawResult);
        } catch (Exception e) {
            log.error("queryBatchUpdateStatus[{}] response非JSON, batchId:{}, response:{}", account.getName(), batchId, rawResult.substring(0, Math.min(200, rawResult.length())));
            return null;
        }
    }

    @Data
    private static class Filter {

        private String id;

        private List<String> selectedValues;

        public Filter(String id, List<String> selectedValues) {
            this.id = id;
            this.selectedValues = selectedValues;
        }
    }
}
