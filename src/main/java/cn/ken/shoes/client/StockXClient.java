package cn.ken.shoes.client;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.model.excel.StockXOrderExcel;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.LimiterHelper;
import cn.ken.shoes.util.ShoesUtil;
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
import java.time.LocalDateTime;
import java.util.*;

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

    private final String expireTime = "2026-04-06T23:22:45+0800";

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
            stockXOrderExcel.setAmount(node.getInteger("amount"));
            stockXOrderExcel.setState(node.getInteger("state"));
            stockXOrderExcel.setSoldOn(TimeUtil.formatISO(node.getString("soldOn")));
            stockXOrderExcel.setDateToShipBy(TimeUtil.formatISO(node.getString("dateToShipBy")));
            JSONObject productVariant = node.getJSONObject("productVariant");
            JSONObject product = productVariant.getJSONObject("product");
            stockXOrderExcel.setTitle(product.getString("title"));
            stockXOrderExcel.setStyleId(product.getString("styleId"));
            List<JSONObject> sizeList = productVariant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class);
            for (JSONObject sizeObject : sizeList) {
                String size = sizeObject.getString("size");
                if (size.contains("US")) {
                    stockXOrderExcel.setUsSize(ShoesUtil.getEuSizeFromPoison(size));
                } else if (size.contains("EU")) {
                    stockXOrderExcel.setEuSize(ShoesUtil.getEuSizeFromPoison(size));
                }
            }
            orders.add(stockXOrderExcel);
        }
        return result;
    }

    public JSONObject queryToDeal(String after) {
        JSONObject jsonObject = queryPro(buildItemsToDealQueryRequest(after));
        if (jsonObject == null) {
            return null;
        }
        JSONObject result = new JSONObject();
        if (jsonObject.getJSONObject("data") == null || jsonObject.getJSONObject("data").getJSONObject("viewer") == null) {
            log.error("queryToDeal error, {}", jsonObject);
            return null;
        }
        JSONObject ask = jsonObject.getJSONObject("data").getJSONObject("viewer").getJSONObject("asks");
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

    public void extendItem(String chainId) {
        JSONObject body = new JSONObject();
        body.put("operationName", "RequestSellerShippingExtension");
        JSONObject variables = new JSONObject();
        body.put("variables", variables);
        JSONObject input = new JSONObject();
        input.put("chainId", chainId);
        variables.put("input", input);
        body.put("query", "mutation RequestSellerShippingExtension($input: SellerShippingExtensionRequestInput) {\n  requestSellerShippingExtension(input: $input) {\n    approved\n    shipByDateExtendedTo\n    __typename\n  }\n}");
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

    public JSONObject querySellingItems(String after, String query) {
        JSONObject jsonObject = queryPro(buildItemsSellingQueryRequest(after, query));
        if (jsonObject == null) {
            return null;
        }
        JSONObject result = new JSONObject();
        JSONObject ask = jsonObject.getJSONObject("data").getJSONObject("viewer").getJSONObject("asks");
        JSONObject pageInfo = ask.getJSONObject("pageInfo");
        result.put("hasMore", pageInfo.getBoolean("hasNextPage"));
        result.put("endCursor", pageInfo.getString("endCursor"));
        List<JSONObject> items = new ArrayList<>();
        result.put("items", items);
        for (JSONObject edge : ask.getJSONArray("edges").toJavaList(JSONObject.class)) {
            JSONObject node = edge.getJSONObject("node");
            JSONObject item = new JSONObject();
            item.put("id", node.getString("id"));
            item.put("amount", node.getInteger("amount"));
            JSONObject productVariant = node.getJSONObject("productVariant");
            item.put("styleId", productVariant.getJSONObject("product").getString("styleId"));
            productVariant.getJSONObject("sizeChart")
                    .getJSONArray("displayOptions")
                    .toJavaList(JSONObject.class)
                    .stream()
                    .filter(option -> option.getString("size").startsWith("EU"))
                    .findFirst()
                    .ifPresent(euOption -> item.put("euSize", ShoesUtil.getEuSizeFromPoison(euOption.getString("size"))));
            items.add(item);
        }
        return result;
    }

    public void deleteItems(List<Pair<String, Integer>> itemList) {
        if (itemList.isEmpty()) {
            return;
        }
        JSONObject body = new JSONObject();
        body.put("operationName", "DeleteAsks");
        JSONObject variables = new JSONObject();
        body.put("variables", variables);
        List<Map<String, Object>> data = new ArrayList<>();
        variables.put("items", data);
        for (Pair<String, Integer> item : itemList) {
            String id = item.getKey();
            Integer amount = item.getValue();
            data.add(Map.of("id", id, "amount", amount, "expires", expireTime, "currencyCode", "USD"));
        }
        body.put("query", "mutation DeleteAsks($items: [BulkDeleteAskInput]) {\n  deleteAsks(input: {items: $items}) {\n    result\n    __typename\n  }\n}");
        JSONObject jsonObject = queryPro(body.toJSONString());
        log.info("deleteItems, result:{}", jsonObject);
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
            stockXPriceDO.setEuSize(ShoesUtil.getEuSizeFromPoison(euOption.getString("size")));
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

    public List<StockXPriceDO> searchSize(String productId) {
        String rawResult = HttpUtil.doGet(StockXConfig.SEARCH_SIZE.replace("{productId}", productId), buildHeaders());
        if (rawResult == null) {
            return Collections.emptyList();
        }
        List<JSONObject> sizeList = JSON.parseArray(rawResult).toJavaList(JSONObject.class);
        List<StockXPriceDO> result = new ArrayList<>();
        for (JSONObject jsonObject : sizeList) {
            StockXPriceDO stockXPrice = new StockXPriceDO();
            String variantId = jsonObject.getString("variantId");
            String euSize = null;
            for (JSONObject json : jsonObject.getJSONObject("sizeChart").getJSONArray("availableConversions").toJavaList(JSONObject.class)) {
                String type = json.getString("type");
                if (!type.equals("eu")) {
                    continue;
                }
                euSize = ShoesUtil.getEuSizeFromPoison(json.getString("size"));
                break;
            }
            stockXPrice.setProductId(productId);
            stockXPrice.setVariantId(variantId);
            stockXPrice.setEuSize(euSize);
            result.add(stockXPrice);
        }
        return result;
    }

    public List<StockXItemDO> searchItems(String query, Integer pageNumber) {
        String rawResult = HttpUtil.doGet(STR."\{StockXConfig.SEARCH_ITEMS}?\{URLUtil.buildQuery(Map.of(
                "query", query,
                "pageNumber", pageNumber,
                "pageSize", 50
        ), Charset.defaultCharset())}", buildHeaders());
        if (rawResult == null) {
            return Collections.emptyList();
        }
        JSONObject jsonObject = JSON.parseObject(rawResult, JSONObject.class);
        if (!jsonObject.containsKey("products")) {
            log.error("searchItems error, result:{}", rawResult);
            return Collections.emptyList();
        }
        List<JSONObject> itemList = jsonObject.getJSONArray("products").toJavaList(JSONObject.class);
        List<StockXItemDO> stockXItems = new ArrayList<>();
        for (JSONObject item : itemList) {
            if (StrUtil.isBlank(item.getString("styleId"))) {
                log.info("no styleId, urlKey:{}", item.getString("urlKey"));
                continue;
            }
            StockXItemDO stockXItemDO = new StockXItemDO();
            stockXItemDO.setProductId(item.getString("productId"));
            stockXItemDO.setBrand(item.getString("brand"));
            stockXItemDO.setProductType(item.getString("productType"));
            stockXItemDO.setTitle(item.getString("title"));
            stockXItemDO.setUrlKey(item.getString("urlKey"));
            stockXItemDO.setModelNo(item.getString("styleId"));
            stockXItems.add(stockXItemDO);
        }
        return stockXItems;
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
        StockXConfig.CONFIG.setExpireTime(time.format(TimeUtil.getFormatter()));
        StockXConfig.CONFIG.setAccessToken(result.getString("access_token"));
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
        StockXConfig.CONFIG.setExpireTime(time.format(TimeUtil.getFormatter()));
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

    public List<StockXPriceDO> queryHotItemsByBrandWithPrice(String brand, Integer pageIndex) {
        JSONObject jsonObject = queryPro(buildItemQueryRequest(brand, pageIndex));
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        List<JSONObject> itemList = jsonObject.getJSONObject("data").getJSONObject("browse").getJSONObject("results").getJSONArray("edges").toJavaList(JSONObject.class);
        List<StockXPriceDO> result = new ArrayList<>();
        for (JSONObject item : itemList) {
            String productId = item.getString("objectId");
            JSONObject node = item.getJSONObject("node");
            String modelNo = node.getString("styleId");
            String urlKey = node.getString("urlKey");
            if (StrUtil.isBlank(modelNo)) {
                log.info("缺少货号，productId:{}，urlKey:{}", productId, urlKey);
                continue;
            }
            JSONArray variants = node.getJSONArray("variants");
            if (CollectionUtils.isEmpty(variants)) {
                log.info("缺少尺码，productId:{}，urlKey:{}", productId, urlKey);
                continue;
            }
            for (JSONObject variant : variants.toJavaList(JSONObject.class)) {
                StockXPriceDO stockXPriceDO = new StockXPriceDO();
                stockXPriceDO.setProductId(productId);
                stockXPriceDO.setModelNo(modelNo);
                String variantId = variant.getString("id");
                stockXPriceDO.setVariantId(variantId);
                JSONObject euOption = variant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class).stream().filter(option -> option.getString("type").equals("eu")).findFirst().orElse(null);
                if (euOption == null) {
                    log.error("queryPrice.euOption is null, productId:{}, variantId:{}, variant:{}", productId, variantId, variant);
                    continue;
                }
                stockXPriceDO.setEuSize(ShoesUtil.getEuSizeFromPoison(euOption.getString("size")));
                JSONObject highestBid = variant.getJSONObject("market").getJSONObject("state").getJSONObject("highestBid");
                stockXPriceDO.setSellNowAmount(Optional.ofNullable(highestBid).map(bid -> bid.getInteger("amount")).orElse(0));
                JSONObject guidance = variant.getJSONObject("pricingGuidance").getJSONObject("marketConsensusGuidance").getJSONObject("standardSellerGuidance");
                stockXPriceDO.setEarnMoreAmount(guidance.getInteger("earnMore"));
                stockXPriceDO.setSellFasterAmount(guidance.getInteger("sellFaster"));
                stockXPriceDO.setModelNo(modelNo);
                result.add(stockXPriceDO);
            }
        }
        return result;
    }

    public List<StockXPriceDO> queryItemWithPrice(String brand, Integer pageIndex) {
        JSONObject jsonObject = queryCustomer(buildItemQueryRequest(brand, pageIndex));
        if (jsonObject == null) {
            return Collections.emptyList();
        }
        List<JSONObject> itemList = jsonObject.getJSONObject("data").getJSONObject("browse").getJSONObject("results").getJSONArray("edges").toJavaList(JSONObject.class);
        List<StockXPriceDO> result = new ArrayList<>();
        for (JSONObject item : itemList) {
            String productId = item.getString("objectId");
            JSONObject node = item.getJSONObject("node");
            String modelNo = node.getString("styleId");
            String urlKey = node.getString("urlKey");
            if (StrUtil.isBlank(modelNo)) {
                log.info("缺少货号，productId:{}，urlKey:{}", productId, urlKey);
                continue;
            }
            JSONArray variants = node.getJSONArray("variants");
            if (CollectionUtils.isEmpty(variants)) {
                log.info("缺少尺码，productId:{}，urlKey:{}", productId, urlKey);
                continue;
            }
            for (JSONObject variant : variants.toJavaList(JSONObject.class)) {
                StockXPriceDO stockXPriceDO = new StockXPriceDO();
                stockXPriceDO.setProductId(productId);
                stockXPriceDO.setModelNo(modelNo);
                String variantId = variant.getString("id");
                stockXPriceDO.setVariantId(variantId);
                JSONObject euOption = variant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class).stream().filter(option -> option.getString("type").equals("eu")).findFirst().orElse(null);
                if (euOption == null) {
                    log.error("queryPrice.euOption is null, productId:{}, variantId:{}, variant:{}", productId, variantId, variant);
                    continue;
                }
                stockXPriceDO.setEuSize(ShoesUtil.getEuSizeFromPoison(euOption.getString("size")));
                JSONObject highestBid = variant.getJSONObject("market").getJSONObject("state").getJSONObject("highestBid");
                stockXPriceDO.setSellNowAmount(Optional.ofNullable(highestBid).map(bid -> bid.getInteger("amount")).orElse(0));
                JSONObject guidance = variant.getJSONObject("pricingGuidance").getJSONObject("marketConsensusGuidance").getJSONObject("standardSellerGuidance");
                stockXPriceDO.setEarnMoreAmount(guidance.getInteger("earnMore"));
                stockXPriceDO.setSellFasterAmount(guidance.getInteger("sellFaster"));
                stockXPriceDO.setModelNo(modelNo);
                result.add(stockXPriceDO);
            }
        }
        return result;
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

    private String buildItemQueryRequest(String brand, Integer index) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "getDiscoveryData");
        requestJson.put("query", "query getDiscoveryData($filters: [BrowseFilterInput], $flow: BrowseFlow, $query: String, $sort: BrowseSortInput, $page: BrowsePageInput, $enableOpenSearch: Boolean, $currency: CurrencyCode!, $country: String!, $market: String!, $skipFlexEligible: Boolean!, $skipGuidance: Boolean!) {\n  browse(\n    filters: $filters\n    flow: $flow\n    sort: $sort\n    page: $page\n    query: $query\n    experiments: {ads: {enabled: true}, dynamicFilter: {enabled: true}, dynamicFilterDefinitions: {enabled: true}, multiselect: {enabled: true}, openSearch: {enabled: $enableOpenSearch}}\n  ) {\n    results {\n      edges {\n        objectId\n        node {\n          ... on Product {\n            urlKey\n            title\n            brand\n            styleId\n            productCategory\n            variants {\n      id\n      isFlexEligible @skip(if: $skipFlexEligible)\n      sizeChart {\n        displayOptions {\n          size\n          type\n          }\n        }\n      market(currencyCode: $currency) {\n        state(country: $country, market: $market) {\n          highestBid {\n            amount\n            }\n          }\n        }\n      pricingGuidance(country: $country, market: $market, currencyCode: $currency) @skip(if: $skipGuidance) {\n        marketConsensusGuidance {\n          standardSellerGuidance {\n            sellFaster\n            earnMore\n            }\n          }\n        }\n      }\n}\n        }\n      }\n      pageInfo {\n        limit\n        page\n        pageCount\n        total\n      }\n    }\n    sort {\n      id\n     }\n  }\n}");
//        requestJson.put("query", "query getDiscoveryData($country: String!, $currency: CurrencyCode, $filters: [BrowseFilterInput], $flow: BrowseFlow, $market: String, $query: String, $sort: BrowseSortInput, $page: BrowsePageInput, $enableOpenSearch: Boolean) {\n  browse(\n    filters: $filters\n    flow: $flow\n    sort: $sort\n    page: $page\n    market: $market\n    query: $query\n   ) {\n    filtersConfig {\n    quick {\n        ...FiltersFragment\n      }\n    }\n    results {\n      edges {\n        isAd\n        adIdentifier\n        adServiceLevel\n        adInventoryId\n        objectId\n        node {\n          __typename\n          ... on Variant {\n            id\n            favorite\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            product {\n              id\n              name\n              urlKey\n              title\n              brand\n              gender\n              description\n              model\n              condition\n              productCategory\n              browseVerticals\n              listingType\n              media {\n                thumbUrl\n                smallImageUrl\n              }\n              traits(filterTypes: [RELEASE_DATE]) {\n                name\n                value\n              }\n            }\n            sizeChart {\n              baseSize\n              baseType\n              displayOptions {\n                size\n                type\n              }\n            }\n          }\n          ... on Product {\n            id\n            name\n            urlKey\n            title\n            brand\n            description\n            model\n            condition\n            productCategory\n            browseVerticals\n            listingType\n            favorite\n            media {\n              thumbUrl\n              smallImageUrl\n            }\n            traits(filterTypes: [RELEASE_DATE]) {\n              name\n              value\n            }\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            variants {\n              id\n            }\n          }\n        }\n      }\n      pageInfo {\n        limit\n        page\n        pageCount\n        queryId\n        queryIndex\n        total\n      }\n    }\n  }\n}");
        JSONObject variables = new JSONObject();
        variables.put("enableOpenSearch", false);
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("category", List.of("shoes", "sneakers")));
        filters.add(new Filter("brand", List.of(brand)));
        variables.put("filters", filters);
//        variables.put("flow", "CATEGORY");
        variables.put("market", "HK");
        variables.put("currency", "USD");
        variables.put("country", "HK");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", false);
        variables.put("page", Map.of("index", index, "limit", 50));
        variables.put("sort", Map.of("id", StockXSwitch.SORT_TYPE.getCode()));
        requestJson.put("variables", variables);
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

    private String buildItemsToDealQueryRequest(String after) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "ViewerAsks");
        requestJson.put("query", "query ViewerAsks($query: String, $after: String, $pageSize: Int, $currencyCode: CurrencyCode, $state: AsksGeneralState, $filters: AsksFiltersInput, $sort: AsksSortInput, $order: AscDescOrderInput) {\n  viewer {\n    asks(\n      query: $query\n      after: $after\n      first: $pageSize\n      currencyCode: $currencyCode\n      state: $state\n      filters: $filters\n      sort: $sort\n      order: $order\n    ) {\n      pageInfo {\n        endCursor\n        hasNextPage\n        totalCount\n        }\n      edges {\n        node {\n          ...AskAttributes\n          }\n        }\n      }\n    }\n}\n\nfragment AskAttributes on Ask {\n  id\n  shippingExtensionRequested\n  orderNumber\n}");
        JSONObject variables = new JSONObject();
        variables.put("pageSize", 1000);
        variables.put("sort", "LISTED_AT");
        variables.put("order", "DESC");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", false);
        if (StrUtil.isNotBlank(after)) {
            variables.put("after", after);
        }
        JSONObject filters = new JSONObject();
        filters.put("statesList", Map.of("in", List.of(410, 411, 415)));
        filters.put("inventoryType", Map.of("in", List.of("STANDARD", "CUSTODIAL")));
        variables.put("filters", filters);
        variables.put("state", "PENDING");
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildOrder(String after) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "ViewerAsks");
        requestJson.put("query", "query ViewerAsks($query: String, $after: String, $pageSize: Int, $currencyCode: CurrencyCode, $state: AsksGeneralState, $filters: AsksFiltersInput, $sort: AsksSortInput, $order: AscDescOrderInput, $country: String!, $market: String!, $skipGuidance: Boolean = true, $skipFlexEligible: Boolean = true, $includeHasAttributedAd: Boolean = false) {\n  viewer {\n    asks(\n      query: $query\n      after: $after\n      first: $pageSize\n      includeHasAttributedAd: $includeHasAttributedAd\n      currencyCode: $currencyCode\n      state: $state\n      filters: $filters\n      sort: $sort\n      order: $order\n    ) {\n      pageInfo {\n        endCursor\n        hasNextPage\n        totalCount\n        __typename\n      }\n      edges {\n        node {\n          ...AskAttributes\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n}\n\nfragment AskAttributes on Ask {\n  id\n  amount\n  created\n  bidAskSpread\n  currentCurrency\n  expires\n  soldOn\n  orderNumber\n  state\n  dateToShipBy\n  authCenter\n  inventoryType\n  associatedAutomation {\n    status\n    id\n    fields {\n      price\n      __typename\n    }\n    __typename\n  }\n  pricingGuidance(country: $country, market: $market) @skip(if: $skipGuidance) {\n    marketConsensusGuidance {\n      standardSellerGuidance {\n        earnMore\n        sellFaster\n        beatUSPrice\n        marketRange {\n          idealMinPrice\n          idealMaxPrice\n          fairMinPrice\n          fairMaxPrice\n          __typename\n        }\n        __typename\n      }\n      flexSellerGuidance {\n        earnMore\n        sellFaster\n        beatUSPrice\n        marketRange {\n          idealMinPrice\n          idealMaxPrice\n          fairMinPrice\n          fairMaxPrice\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n  shipment {\n    id\n    bulk\n    deleted\n    displayId\n    trackingNumber\n    trackingUrl\n    deliveryDate\n    commercialInvoiceUrl\n    documents {\n      sellerShippingInstructions\n      sellerShippingInstructionsThermal\n      __typename\n    }\n    __typename\n  }\n  productVariant {\n    id\n    isFlexEligible @skip(if: $skipFlexEligible)\n    traits {\n      size\n      sizeDescriptor\n      __typename\n    }\n    sizeChart {\n      displayOptions {\n        size\n        __typename\n      }\n      baseType\n      __typename\n    }\n    market(currencyCode: $currencyCode) {\n      state(country: $country, market: $market) {\n        bidInventoryTypes {\n          standard {\n            highest {\n              amount\n              chainId\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        askServiceLevels {\n          standard {\n            lowest {\n              amount\n              __typename\n            }\n            __typename\n          }\n          expressStandard {\n            lowest {\n              amount\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    product {\n      id\n      name\n      styleId\n      model\n      title\n      productCategory\n      contentGroup\n      browseVerticals\n      primaryCategory\n      minimumBid(currencyCode: $currencyCode)\n      traits {\n        name\n        value\n        __typename\n      }\n      taxInformation {\n        id\n        code\n        __typename\n      }\n      media {\n        thumbUrl\n        __typename\n      }\n      sizeDescriptor\n      hazardousMaterial {\n        lithiumIonBucket\n        __typename\n      }\n      lockSelling\n      listingType\n      __typename\n    }\n    __typename\n  }\n  hasAttributedAd\n  __typename\n}");
        JSONObject variables = new JSONObject();
        variables.put("country", "HK");
        variables.put("market", "HK");
        variables.put("pageSize", 1000);
        variables.put("sort", "LISTED_AT");
        variables.put("order", "DESC");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", false);
        if (StrUtil.isNotBlank(after)) {
            variables.put("after", after);
        }
        JSONObject filters = new JSONObject();
        filters.put("statesList", Map.of("in", List.of(410, 411, 415)));
        filters.put("inventoryType", Map.of("in", List.of("STANDARD", "CUSTODIAL")));
        variables.put("filters", filters);
        variables.put("state", "PENDING");
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildItemsSellingQueryRequest(String after, String query) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "ViewerAsks");
        requestJson.put("query", "query ViewerAsks($query: String, $after: String, $pageSize: Int, $currencyCode: CurrencyCode, $state: AsksGeneralState, $filters: AsksFiltersInput, $sort: AsksSortInput, $order: AscDescOrderInput) {\n  viewer {\n    asks(\n      query: $query\n      after: $after\n      first: $pageSize\n      currencyCode: $currencyCode\n      state: $state\n      filters: $filters\n      sort: $sort\n      order: $order\n    ) {\n      pageInfo {\n        endCursor\n        hasNextPage\n        totalCount\n        }\n      edges {\n        node {\n          ...AskAttributes\n          }\n        }\n      }\n    }\n}\n\nfragment AskAttributes on Ask {\n  id\n  amount\n  productVariant {\n    id\n    sizeChart {\n      displayOptions {\n        size\n       }\n      }\n    product {\n      id\n      urlKey\n      styleId\n     }\n    }\n}");
        JSONObject variables = new JSONObject();
        variables.put("pageSize", 1000);
        variables.put("sort", "LISTED_AT");
        variables.put("order", "DESC");
        variables.put("skipFlexEligible", true);
        variables.put("skipGuidance", false);
        variables.put("query", query);
        if (StrUtil.isNotBlank(after)) {
            variables.put("after", after);
        }
        JSONObject filters = new JSONObject();
        filters.put("statesList", Map.of("in", List.of(400)));
        filters.put("inventoryType", Map.of("in", List.of("STANDARD")));
        variables.put("filters", filters);
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private JSONObject queryPro(String body) {
        LimiterHelper.limitStockxSecond();
        String rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, body, buildProHeaders());
        if (rawResult == null) {
            return null;
        }
        JSONObject jsonObject = JSON.parseObject(rawResult);
        if (jsonObject.containsKey("status")) {
            log.error("queryHotItemsByBrandWithPrice error, msg:{}", jsonObject.getString("message"));
            return null;
        }
        if (jsonObject.containsKey("blockScript")) {
            log.error("queryHotItemsByBrandWithPrice|查询被拦截");
            return null;
        }
        return JSON.parseObject(rawResult);
    }

    private JSONObject queryCustomer(String body) {
        LimiterHelper.limitStockxSecond();
        String rawResult = HttpUtil.doPost(StockXConfig.STOCKX_CUSTOMER, body, buildCustomerHeaders());
        if (rawResult == null) {
            return null;
        }
        JSONObject jsonObject = JSON.parseObject(rawResult);
        if (jsonObject.containsKey("status")) {
            log.error("queryHotItemsByBrandWithPrice error, msg:{}", jsonObject.getString("message"));
            return null;
        }
        if (jsonObject.containsKey("blockScript")) {
            log.error("queryHotItemsByBrandWithPrice|查询被拦截");
            return null;
        }
        return JSON.parseObject(rawResult);
    }

    private Headers buildProHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "authorization", getAuthorization(),
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0"
        );
    }

    private Headers buildCustomerHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) chrome/124.0.0.0 safari/537.36",
                "sec-ch-ua", "\"Chromium\";v=\"124\",\"Google Chrome\";v=\"124\",\"Not-A.Brand\";v=\"99\"",
                "apollographql-client-name", "Iron"
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
        if (StockXConfig.CONFIG.getAccessToken() != null) {
            return STR."Bearer \{StockXConfig.CONFIG.getAccessToken()}";
        }
        return authorization;
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
