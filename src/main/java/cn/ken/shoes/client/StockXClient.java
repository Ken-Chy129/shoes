package cn.ken.shoes.client;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.StockXSwitch;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.StockXItemDO;
import cn.ken.shoes.model.entity.StockXPriceDO;
import cn.ken.shoes.util.HttpUtil;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public List<StockXPriceDO> queryPrice(String productId) {
        String rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, buildPriceQueryRequest(productId), buildProHeaders());
        if (StrUtil.isBlank(rawResult)) {
            return null;
        }
        JSONObject jsonObject = JSON.parseObject(rawResult);
        List<JSONObject> variants = jsonObject.getJSONObject("data").getJSONObject("product").getJSONArray("variants").toJavaList(JSONObject.class);
        String modelNo = jsonObject.getJSONObject("data")
                .getJSONObject("product")
                .getJSONArray("traits")
                .toJavaList(JSONObject.class)
                .stream()
                .filter(trait -> trait.getString("name").equals("Style"))
                .findFirst()
                .map(trait -> trait.getString("value")).orElse(null);
        List<StockXPriceDO> result = new ArrayList<>();
        for (JSONObject variant : variants) {
            StockXPriceDO stockXPriceDO = new StockXPriceDO();
            stockXPriceDO.setProductId(productId);
            stockXPriceDO.setVariantId(variant.getString("id"));
            JSONObject euOption = variant.getJSONObject("sizeChart").getJSONArray("displayOptions").toJavaList(JSONObject.class).stream().filter(option -> option.getString("type").equals("eu")).findFirst().orElse(null);
            if (euOption == null) {
                log.error("queryPrice.euOption is null, variant:{}", variant);
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
            map.put("amount", price);
            map.put("quantity", 1);
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
            stockXItemDO.setReleaseDate(item.getJSONObject("productAttributes").getString("releaseDate"));
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
        return true;
    }

    public String getCode() {
        return HttpUtil.doGet(StockXConfig.CALLBACK);
    }

    public String getAuthorizeUrl() {
        return StockXConfig.AUTHORIZE.replace("{clientId}", clientId).replace("{redirectUri}", redirectUri).replace("{state}", state);
    }

    private Headers buildProHeaders() {
        return Headers.of(
                "authorization", authorization,
                "Content-Type", "application/json",
                "User-Agent", "Apifox/1.0.0 (https://apifox.com)"
        );
    }

    private Headers buildHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "Authorization", STR."Bearer \{StockXConfig.CONFIG.getAccessToken()}",
                "x-api-key", apiKey
        );
    }

    public List<String> queryHotItemsByBrand(String brand, Integer pageIndex) {
        String rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, buildItemQueryRequest(brand, pageIndex), buildProHeaders());
        if (rawResult == null) {
            return Collections.emptyList();
        }
        JSONObject jsonObject = JSON.parseObject(rawResult);
        List<JSONObject> itemList = jsonObject.getJSONObject("data").getJSONObject("browse").getJSONObject("results").getJSONArray("edges").toJavaList(JSONObject.class);
        List<String> result = new ArrayList<>();
        for (JSONObject item : itemList) {
            result.add(item.getString("objectId"));
        }
        return result;
    }

    private String buildItemQueryRequest(String brand, Integer index) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "getDiscoveryData");
        requestJson.put("query", "query getDiscoveryData($query: String, $page: BrowsePageInput) {\n  browse(\n    page: $page\n    query: $query\n ) {\n    results {\n      edges {\n        objectId\n        }\n      pageInfo {\n        limit\n        page\n        pageCount\n        queryId\n        queryIndex\n        total\n      }\n    }\n  }\n}");
//        requestJson.put("query", "query getDiscoveryData($country: String!, $currency: CurrencyCode, $filters: [BrowseFilterInput], $flow: BrowseFlow, $market: String, $query: String, $sort: BrowseSortInput, $page: BrowsePageInput, $enableOpenSearch: Boolean) {\n  browse(\n    filters: $filters\n    flow: $flow\n    sort: $sort\n    page: $page\n    market: $market\n    query: $query\n   ) {\n    filtersConfig {\n    quick {\n        ...FiltersFragment\n      }\n    }\n    results {\n      edges {\n        isAd\n        adIdentifier\n        adServiceLevel\n        adInventoryId\n        objectId\n        node {\n          __typename\n          ... on Variant {\n            id\n            favorite\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            product {\n              id\n              name\n              urlKey\n              title\n              brand\n              gender\n              description\n              model\n              condition\n              productCategory\n              browseVerticals\n              listingType\n              media {\n                thumbUrl\n                smallImageUrl\n              }\n              traits(filterTypes: [RELEASE_DATE]) {\n                name\n                value\n              }\n            }\n            sizeChart {\n              baseSize\n              baseType\n              displayOptions {\n                size\n                type\n              }\n            }\n          }\n          ... on Product {\n            id\n            name\n            urlKey\n            title\n            brand\n            description\n            model\n            condition\n            productCategory\n            browseVerticals\n            listingType\n            favorite\n            media {\n              thumbUrl\n              smallImageUrl\n            }\n            traits(filterTypes: [RELEASE_DATE]) {\n              name\n              value\n            }\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            variants {\n              id\n            }\n          }\n        }\n      }\n      pageInfo {\n        limit\n        page\n        pageCount\n        queryId\n        queryIndex\n        total\n      }\n    }\n  }\n}");
        JSONObject variables = new JSONObject();
        variables.put("country", "US");
        variables.put("currency", "USD");
        variables.put("enableOpenSearch", false);
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("category", List.of("shoes", "sneakers")));
        filters.add(new Filter("brand", List.of(brand)));
        variables.put("filters", filters);
        variables.put("flow", "CATEGORY");
        variables.put("market", "US");
        variables.put("page", Map.of("index", index, "limit", 40));
        variables.put("sort", Map.of("id", StockXSwitch.SORT_TYPE.getCode()));
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildBrandQueryRequest(String brand, Integer index, Integer limit) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "getDiscoveryData");
        requestJson.put("query", "fragment FiltersFragment on BrowseFilter {\n  id\n  name\n  type\n  ... on BrowseFilterTree {\n    isCollapsed\n    multiSelectEnabled\n    options {\n      id\n      name\n      count\n      selected\n      children\n      level\n      value\n    }\n  }\n  ... on BrowseFilterList {\n    isCollapsed\n    multiSelectEnabled\n    listFilterStyle: style\n    options {\n      id\n      name\n      count\n      selected\n      value\n    }\n  }\n  ... on BrowseFilterBoolean {\n    id\n    name\n    type\n    selected\n    booleanFilterStyle: style\n  }\n  ... on BrowseFilterRange {\n    id\n    isCollapsed\n    name\n    type\n    minimum {\n      value\n    }\n    maximum {\n      value\n    }\n  }\n  ... on BrowseFilterColor {\n    id\n    isCollapsed\n    name\n    type\n    options {\n      name\n      value\n      count\n      selected\n      swatchColor\n      borderColor\n    }\n  }\n}\n\nquery getDiscoveryData($country: String!, $currency: CurrencyCode, $filters: [BrowseFilterInput], $flow: BrowseFlow, $market: String, $query: String, $sort: BrowseSortInput, $page: BrowsePageInput, $enableOpenSearch: Boolean) {\n  browse(\n    filters: $filters\n    flow: $flow\n    sort: $sort\n    page: $page\n    market: $market\n    query: $query\n    experiments: {ads: {enabled: true}, dynamicFilter: {enabled: true}, dynamicFilterDefinitions: {enabled: true}, multiselect: {enabled: true}, openSearch: {enabled: $enableOpenSearch}}\n  ) {\n    filtersConfig {\n      quick {\n        ...FiltersFragment\n      }\n      advanced {\n        ...FiltersFragment\n      }\n    }\n    results {\n      edges {\n        isAd\n        adIdentifier\n        adServiceLevel\n        adInventoryId\n        objectId\n        node {\n          __typename\n          ... on Variant {\n            id\n            favorite\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            product {\n              id\n              name\n              urlKey\n              title\n              brand\n              gender\n              description\n              model\n              condition\n              productCategory\n              browseVerticals\n              listingType\n              media {\n                thumbUrl\n                smallImageUrl\n              }\n              traits(filterTypes: [RELEASE_DATE]) {\n                name\n                value\n              }\n            }\n            sizeChart {\n              baseSize\n              baseType\n              displayOptions {\n                size\n                type\n              }\n            }\n          }\n          ... on Product {\n            id\n            name\n            urlKey\n            title\n            brand\n            description\n            model\n            condition\n            productCategory\n            browseVerticals\n            listingType\n            favorite\n            media {\n              thumbUrl\n              smallImageUrl\n            }\n            traits(filterTypes: [RELEASE_DATE]) {\n              name\n              value\n            }\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            variants {\n              id\n            }\n          }\n        }\n      }\n      pageInfo {\n        limit\n        page\n        pageCount\n        queryId\n        queryIndex\n        total\n      }\n    }\n    seo {\n      title\n      blurb\n      richBlurb\n      meta {\n        name\n        value\n      }\n    }\n    sort {\n      id\n      name\n      description\n      seoUrlKey\n      short\n    }\n  }\n}");
        JSONObject variables = new JSONObject();
        variables.put("country", "US");
        variables.put("currency", "USD");
        variables.put("enableOpenSearch", false);
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("category", List.of("shoes", "sneakers")));
        filters.add(new Filter("brand", List.of(brand)));
        variables.put("filters", filters);
        variables.put("flow", "CATEGORY");
        variables.put("market", "US");
        variables.put("page", Map.of("index", index, "limit", limit));
        variables.put("sort", Map.of("id", "most-active"));
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
    }

    private String buildPriceQueryRequest(String id) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "ProductVariants");
        requestJson.put("query", "query ProductVariants($id: String!, $currency: CurrencyCode!, $country: String!, $market: String!, $skipFlexEligible: Boolean!, $skipGuidance: Boolean!) {\n  product(id: $id) {\n    id\n    productCategory\n    primaryCategory\n    title\n    model\n    name\n    media {\n      thumbUrl\n      __typename\n    }\n    traits {\n      value\n      name\n      __typename\n      }\n    variants {\n      id\n      hidden\n      isFlexEligible @skip(if: $skipFlexEligible)\n      sortOrder\n      traits {\n        size\n        sizeDescriptor\n        __typename\n      }\n      sizeChart {\n        displayOptions {\n          size\n          type\n          __typename\n        }\n        baseType\n        __typename\n      }\n      market(currencyCode: $currency) {\n        state(country: $country, market: $market) {\n          highestBid {\n            amount\n            chainId\n            __typename\n          }\n          askServiceLevels {\n            standard {\n              lowest {\n                amount\n                __typename\n              }\n              __typename\n            }\n            expressStandard {\n              lowest {\n                amount\n                __typename\n              }\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        __typename\n      }\n      pricingGuidance(country: $country, market: $market, currencyCode: $currency) @skip(if: $skipGuidance) {\n        sellingGuidance {\n          earnMore\n          sellFaster\n          __typename\n        }\n        marketConsensusGuidance {\n          standardSellerGuidance {\n            sellFaster\n            earnMore\n            beatUSPrice\n            marketRange {\n              idealMinPrice\n              idealMaxPrice\n              fairMinPrice\n              fairMaxPrice\n              __typename\n            }\n            __typename\n          }\n          flexSellerGuidance {\n            sellFaster\n            earnMore\n            beatUSPrice\n            marketRange {\n              idealMinPrice\n              idealMaxPrice\n              fairMinPrice\n              fairMaxPrice\n              __typename\n            }\n            __typename\n          }\n          __typename\n        }\n        __typename\n      }\n      __typename\n    }\n    __typename\n  }\n}");
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

    public List<BrandDO> queryBrands() {
        String rawResult = HttpUtil.doPost(StockXConfig.GRAPHQL, buildBrandQueryRequest("nike", 1, 1), buildProHeaders());
        if (rawResult == null) {
            return Collections.emptyList();
        }
        JSONObject jsonObject = JSON.parseObject(rawResult);
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
