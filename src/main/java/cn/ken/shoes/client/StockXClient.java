package cn.ken.shoes.client;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.URLUtil;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXItem;
import cn.ken.shoes.model.stockx.StockXPrice;
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

    public String createListing(List<Pair<String, String>> items) {
        List<Map<String, Object>> toCreate = new ArrayList<>();
        for (Pair<String, String> item : items) {
            String variantId = item.getKey();
            String price = item.getValue();
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

    public List<StockXPrice> searchPrice(String productId) {
        String rawResult = HttpUtil.doGet(StockXConfig.SEARCH_PRICE.replace("{productId}", productId), buildHeaders());
        if (rawResult == null) {
            return Collections.emptyList();
        }
        return JSON.parseArray(rawResult).toJavaList(StockXPrice.class);
    }

    public List<StockXPrice> searchSize(String productId) {
        String rawResult = HttpUtil.doGet(StockXConfig.SEARCH_SIZE.replace("{productId}", productId), buildHeaders());
        if (rawResult == null) {
            return Collections.emptyList();
        }
        List<JSONObject> sizeList = JSON.parseArray(rawResult).toJavaList(JSONObject.class);
        List<StockXPrice> result = new ArrayList<>();
        for (JSONObject jsonObject : sizeList) {
            StockXPrice stockXPrice = new StockXPrice();
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

    public List<StockXItem> searchItems(String query, Integer pageNumber) {
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
        List<StockXItem> stockXItems = new ArrayList<>();
        for (JSONObject item : itemList) {
            StockXItem stockXItem = new StockXItem();
            stockXItem.setProductId(item.getString("productId"));
            stockXItem.setBrand(item.getString("brand"));
            stockXItem.setProductType(item.getString("productType"));
            stockXItem.setTitle(item.getString("title"));
            stockXItem.setUrlKey(item.getString("urlKey"));
            stockXItem.setModelNo(item.getString("styleId"));
            stockXItem.setReleaseDate(item.getJSONObject("productAttributes").getString("releaseDate"));
            stockXItems.add(stockXItem);
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

    private Headers buildHeaders() {
        return Headers.of(
                "Content-Type", "application/json",
                "Authorization", STR."Bearer \{StockXConfig.CONFIG.getAccessToken()}",
                "x-api-key", apiKey
        );
    }

    public String queryItemByBrand(String brand) {
        String result = HttpUtil.doPost(StockXConfig.ITEM_LIST, buildItemQueryRequest(brand, 1, 40), Headers.of(
                "Cookie", "_pxvid=1fc01082-d1c0-11ef-8795-3abc93738854; hubspotutk=9cb58cc97bd9521c77f0eccbeae8f871; ajs_anonymous_id=67d1c183-1d89-482f-b62d-45852055baaa; stockx_device_id=e159eca4-3831-4c36-9509-818dcd9263a9; language_code=zh; _fbp=fb.1.1736780864697.696611216395850341; chakra-ui-color-mode=dark; QuantumMetricUserID=30d5ef918f950a73def4d9ce61af797f; ajs_user_id=0e2c1fc0-2930-11ee-8a06-12f12be0eb51; rskxRunCookie=0; rCookie=nk3tad0e0pdvl2sozgcetm5v6jxqg; _cc_id=7c7909b8062bca5e353513823660ecee; pxcts=0832eec5-f1e9-11ef-b05c-3dd4aff88ac3; rbuid=rbos-f7d89850-230a-44db-a365-bfe26f4ced33; stockx_selected_region=US; stockx_ip_region=US; _gcl_au=1.1.833383876.1736780863.293254299.1740316877.1740316968; stockx_selected_currency=USD; panoramaId_expiry=1740921856686; panoramaId=9c82186d95771a4ea13f03f9d917185ca02c9e8f9b3cfa9686e1f17e90f0225b; panoramaIdType=panoDevice; stockx_preferred_market_activity=sales; __hstc=256292052.9cb58cc97bd9521c77f0eccbeae8f871.1736780859605.1736780859605.1740318383711.2; __hssrc=1; is_gdpr=false; stockx_product_visits=2; stockx_session=17ef695a-c9fc-4f51-9ce1-0568dc32282f; stockx_session_id=ec1047dc-ab99-49e0-ae01-bf3377abcca7; cf_clearance=_4KZR247.9fhMBlFMAcpqfEmCgeL4ifkryO0AT4CnW8-1740326980-1.2.1.1-UCfvLqtZsxIb2Tbo61VOOJD.W0Vq8QONM8JSWRsCnLzttVlctdSM7tl0KsixiwcY7KV6nw4D9ULsB96C4dIEDZxm2a.kz4JoJ8J7hMmA2Q2fK6WQigXoM0a1WThm60mGa5rrr5DvSgFsc.W_G3ATxZIX.wfhFYuB_6zBwmzYxlSlUDJiYCO_aWl4rx.eMb2im.EbSfrFi0iUEg6avY82Piy6_mQjZOjGT7lqEW4wnfhDi60UEkpg8pt855ZL8NL33A1hx.HXW6UUwl6wQFs.Y9lOhdowGQFvaXmgNy.UUpo; maId={\"pixlId\":45,\"cid\":\"108518463d663bdc3032e846321f859b\",\"sid\":\"dff2b5f5-2fd0-4ea9-adf3-db94c520f6fd\",\"isSidSaved\":true,\"sessionStart\":\"2025-02-23T16:09:45.000Z\"}; _px3=bfe1805d5c87363452a6384096a111d055fe5e10f1ba80a3445e1518ec835cae:zvjs+YqydgvN7L42/Ll7J1jrVwtMooR1ulMX0NfbGJbIXjPZ56kfp9Es7mjve+Cpbp2wPgm8ohL+RtkvhaVbyg==:1000:l285i4tmClQsQWXnA+IIdginBNzrfOINyOKTUfawjeoaje83dvEHiQJ4hYxhNQr5xLM97yX54ZSR1temY/YaXx2noSeaUz+ix+al8D8V7/JVamfrBAxKEdJQyiOSXlSiE18IQhgyvIpUDEcF7qhZxUKKTkamiCgPaUB6b8nbeTVhqLWAL61rVCFDB0mjlDyZSznZ9rh3koA2sPx8T3KxB/5meGl7npwHWHhO+aBZpsw=; OptanonConsent=isGpcEnabled=0&datestamp=Mon+Feb+24+2025+00%3A10%3A50+GMT%2B0800+(%E4%B8%AD%E5%9B%BD%E6%A0%87%E5%87%86%E6%97%B6%E9%97%B4)&version=202410.1.0&browserGpcFlag=0&isIABGlobal=false&hosts=&consentId=690cb144-5ec4-498b-96bf-021e6dd63b77&interactionCount=0&isAnonUser=1&landingPath=NotLandingPage&groups=C0001%3A1%2CC0002%3A1%2CC0004%3A1%2CC0003%3A1&AwaitingReconsent=false; cto_bundle=imvN8V9QQUY4MzIwNnBObk80MjloMnNQWldLMXJPWjUlMkZ5Z1cwcEFJaXNIeGdhbnNub1pSRmJac2dnSCUyQnJnY3NJQVRJZkt2QkZVbjc3SiUyQk5EbXZhVGJuU2JqbTJMc3A2cyUyQnVtMEQ0YzdPbk9jaWlPY1ZxJTJCeWRoeVgxSWhLQlRxbVo5bXQlMkI2bWE1c2hmbSUyQkFzTEpPRWZTVk5LQSUzRCUzRA; lastRskxRun=1740327067814; _pxde=53009221f39ab3a239f207c337902bfb0399d522b1ac885477f443945a82e015:eyJ0aW1lc3RhbXAiOjE3NDAzMjcwNzc5NzAsImZfa2IiOjB9; __gads=ID=1b62483e0282613b:T=1736781018:RT=1740327080:S=ALNI_MZRxZh_gXpygjvRfKemGzvlcNzQ1w; __gpi=UID=00000fe83ccf495d:T=1736781018:RT=1740327080:S=ALNI_Mbb-QnsFM-MDEq6_ZYph6v4pD2IkQ; __eoi=ID=d77606a997a77c55:T=1736781018:RT=1740327080:S=AA-AfjbZ3DOZUui2kv4K6d1okpcC; _tq_id.TV-5490813681-1.1a3e=fac93f8db0519a72.1736780981.0.1740327083..; _pxhd=y78S66qrbpb3lGGUF5c60744KJNHiPcFPHIbZJyfnbQbqdIav05o72TjzvPDlLS5a2XZYCa/-wdA93zATVsHXA==:wvbKGrgy0zBID/jnAgpliUOVArHsGMia8jkZ3CFYnEAqt9FT2TcJ-69ki4XYD04bNhf61yMi9J3wfrHDe6QvFiK96miEPkQQfpgdhBacl-U=; _uetsid=0c69bbc0f1e911efb5af2366c3b434c3|q66m91|2|fto|0|1880; _uetvid=2496cd20d1c011ef939209b10b6930b3|1b7cbw3|1740327084198|7|1|bat.bing.com/p/insights/c/w; _dd_s=rum=0&expire=1740327986648&logs=1&id=03d7d01e-1ae1-4da0-a1cc-22ff53b9731f&created=1740323705834",
                "x-abtest-ids", "ab_0h8cf_web.true,ab_0xdjj_web.true,ab_12dul_all.neither,ab_1f6iw_web.true,ab_1ncl6_web.true,ab_2ki60_web.true,ab_2lofw_web.true,ab_2m3ew_web.true,ab_36qi2_web.false,ab_3opqo_web.true,ab_3qc6g_web.neither,ab_5wzq0_web.true,ab_82d7l_web.variant1,ab_8dhfg_web.neither,ab_8ht34_web.true,ab_8stea_web.true,ab_8x8am_web.true,ab_8zx87_web.true,ab_90n02_web.true,ab_9rxh7_web.true,ab_9v8xl_web.variant,ab_9zi8a_web.neither,ab_aa_continuous_all.web_b,ab_account_selling_guidance_web.variant,ab_aoxjj_web.true,ab_ayu9e_web.true,ab_checkout_buying_table_redesign_web.variant,ab_checkout_cutoff_date_web.variant,ab_chk_place_order_verbage_web.true,ab_cs_seller_shipping_extension_web.variant,ab_discovery_color_filter_all.false,ab_drc_chk_sell_intra_zone_all_in_support_web.variant,ab_ebguu_web.true,ab_efoch_web.true,ab_ekirh_web.variant_2,ab_enable_3_CTAs_web.variant,ab_epnox_web.true,ab_eqctr_web.true,ab_eu3zm_web.neither,ab_gift_cards_v1_web.true,ab_growth_appsflyer_smart_banner_web.variant_2,ab_growth_ignore_rv_products_in_rfy_v2_web.true,ab_hex3z_web.true,ab_home_as_seen_on_instagram_v2_web.true,ab_home_carousel_current_asks_bids_web.true,ab_home_page_reordering_web.variant_1,ab_home_show_value_props_web.variant_2,ab_hsaxr_web.true,ab_hzpar_all.neither,ab_i9gt9_web.true,ab_k358k_web.neither,ab_k3z78_web.true,ab_kvcr0_web.true,ab_l2afp_all.neither,ab_l74f8_web.false,ab_ljut9_web.true,ab_merchandising_module_pdp_v2_web.variant,ab_mh0wn_web.true,ab_n0kpl_web.true,ab_n87do_web.neither,ab_ncs63_web.true,ab_nsr8y_web.false,ab_nz3j1_web.true,ab_o95do_web.true,ab_og9wl_web.true,ab_ozy5d_web.neither,ab_phx04_web.true,ab_pirate_most_popular_around_you_module_web.neither,ab_pirate_product_cell_favorite_web_v1.true,ab_q2nhm_web.true,ab_q704p_web.true,ab_r84zi_web.variant,ab_sa2jv_web.1.5,ab_search_static_ranking_v5_web.variant,ab_sx1wr_web.true,ab_u13ie_web.true,ab_uaa6m_web.true,ab_ubnt3_web.neither,ab_vhfbq_web.neither,ab_w2cvj_web.true,ab_web_aa_continuous.true,ab_xbqne_web.true,ab_xjcn6_all.neither,ab_xr2kh_web.variant1,ab_y8s2m_web.neither1",
                "x-stockx-device-id", "e159eca4-3831-4c36-9509-818dcd9263a9",
                "x-stockx-session-id", "ec1047dc-ab99-49e0-ae01-bf3377abcca7"
        ));
        System.out.println(result);
        return result;
    }

    private String buildItemQueryRequest(String brand, Integer index, Integer limit) {
        JSONObject requestJson = new JSONObject();
        requestJson.put("operationName", "getDiscoveryData");
        requestJson.put("query", "fragment FiltersFragment on BrowseFilter {\n  id\n  name\n  type\n  ... on BrowseFilterTree {\n    isCollapsed\n    multiSelectEnabled\n    options {\n      id\n      name\n      count\n      selected\n      children\n      level\n      value\n    }\n  }\n  ... on BrowseFilterList {\n    isCollapsed\n    multiSelectEnabled\n    listFilterStyle: style\n    options {\n      id\n      name\n      count\n      selected\n      value\n    }\n  }\n  ... on BrowseFilterBoolean {\n    id\n    name\n    type\n    selected\n    booleanFilterStyle: style\n  }\n  ... on BrowseFilterRange {\n    id\n    isCollapsed\n    name\n    type\n    minimum {\n      value\n    }\n    maximum {\n      value\n    }\n  }\n  ... on BrowseFilterColor {\n    id\n    isCollapsed\n    name\n    type\n    options {\n      name\n      value\n      count\n      selected\n      swatchColor\n      borderColor\n    }\n  }\n}\n\nquery getDiscoveryData($country: String!, $currency: CurrencyCode, $filters: [BrowseFilterInput], $flow: BrowseFlow, $market: String, $query: String, $sort: BrowseSortInput, $page: BrowsePageInput, $enableOpenSearch: Boolean) {\n  browse(\n    filters: $filters\n    flow: $flow\n    sort: $sort\n    page: $page\n    market: $market\n    query: $query\n    experiments: {ads: {enabled: true}, dynamicFilter: {enabled: true}, dynamicFilterDefinitions: {enabled: true}, multiselect: {enabled: true}, openSearch: {enabled: $enableOpenSearch}}\n  ) {\n    filtersConfig {\n      quick {\n        ...FiltersFragment\n      }\n      advanced {\n        ...FiltersFragment\n      }\n    }\n    results {\n      edges {\n        isAd\n        adIdentifier\n        adServiceLevel\n        adInventoryId\n        objectId\n        node {\n          __typename\n          ... on Variant {\n            id\n            favorite\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            product {\n              id\n              name\n              urlKey\n              title\n              brand\n              gender\n              description\n              model\n              condition\n              productCategory\n              browseVerticals\n              listingType\n              media {\n                thumbUrl\n                smallImageUrl\n              }\n              traits(filterTypes: [RELEASE_DATE]) {\n                name\n                value\n              }\n            }\n            sizeChart {\n              baseSize\n              baseType\n              displayOptions {\n                size\n                type\n              }\n            }\n          }\n          ... on Product {\n            id\n            name\n            urlKey\n            title\n            brand\n            description\n            model\n            condition\n            productCategory\n            browseVerticals\n            listingType\n            favorite\n            media {\n              thumbUrl\n              smallImageUrl\n            }\n            traits(filterTypes: [RELEASE_DATE]) {\n              name\n              value\n            }\n            market(currencyCode: $currency) {\n              state(country: $country, market: $market) {\n                highestBid {\n                  amount\n                  updatedAt\n                }\n                lowestAsk {\n                  amount\n                  updatedAt\n                }\n                askServiceLevels {\n                  expressExpedited {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                  expressStandard {\n                    count\n                    lowest {\n                      amount\n                    }\n                  }\n                }\n              }\n              statistics(market: $market) {\n                annual {\n                  averagePrice\n                  volatility\n                  salesCount\n                  pricePremium\n                }\n                last72Hours {\n                  salesCount\n                }\n                lastSale {\n                  amount\n                }\n              }\n            }\n            variants {\n              id\n            }\n          }\n        }\n      }\n      pageInfo {\n        limit\n        page\n        pageCount\n        queryId\n        queryIndex\n        total\n      }\n    }\n    seo {\n      title\n      blurb\n      richBlurb\n      meta {\n        name\n        value\n      }\n    }\n    sort {\n      id\n      name\n      description\n      seoUrlKey\n      short\n    }\n  }\n}");
        JSONObject variables = new JSONObject();
        variables.put("country", "US");
        variables.put("currency", "USD");
        variables.put("enableOpenSearch", false);
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("category", List.of("shoes")));
        filters.add(new Filter("brand", List.of(brand)));
        variables.put("filters", filters);
        variables.put("flow", "CATEGORY");
        variables.put("market", "US");
        variables.put("page", Map.of("index", index, "limit", limit));
        variables.put("sort", Map.of("id", "most-active"));
        requestJson.put("variables", variables);
        return requestJson.toJSONString();
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
