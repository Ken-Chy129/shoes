package cn.ken.shoes.client;

import cn.ken.shoes.common.KickScrewApiConstant;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.model.kickscrew.KickScrewAlgoliaRequest;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.kickscrew.KickScrewUploadItem;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Slf4j
@Component
public class KickScrewClient {

    private static final String AGENT = "Algolia for JavaScript (4.24.0); Browser; instantsearch.js (4.74.0); react (18.3.1); react-instantsearch (7.13.0); react-instantsearch-core (7.13.0); next.js (14.2.10); JS Helper (3.22.4)";

    private static final Integer PAGE_SIZE = 30;

    public KickScrewCategory queryBrand() {
        String result = queryAlgolia(buildAlgoliaBodyForBrand());
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .map(json -> json.getObject("facets", KickScrewCategory.class))
                .orElse(null);
    }

    /**
     * 根据条件（时间、品牌、性别、商品类型等）分页查询kc平台商品
     */
    public List<KickScrewItemDO> queryItemPageV2(KickScrewAlgoliaRequest request) {
        String result = queryAlgolia(buildAlgoliaBodyForItem(request));
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .map(json -> json.getJSONArray("hits"))
                .map(jsonArray -> jsonArray.toJavaList(KickScrewItemDO.class)).orElse(new ArrayList<>());
    }

    /**
     * 根据条件（时间、品牌、性别、商品类型等）查询kc平台商品页数
     */
    public Integer countItemPageV2(KickScrewAlgoliaRequest request) {
        String result = queryAlgolia(buildAlgoliaBodyForItem(request));
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .map(json -> json.getInteger("nbPages")).orElse(1);
    }

    private String queryAlgolia(String bodyString) {
        String url = UriComponentsBuilder.fromUriString(KickScrewApiConstant.ALGOLIA)
                .queryParam("x-algolia-agent", AGENT)
                .toUriString();
        return HttpUtil.doPost(url, bodyString, Headers.of(
                "x-algolia-api-key", "173de9e561a4bc91ca6074d4dc6db17c",
                "x-algolia-application-id", "7CCJSEVCO9"
        ));
    }

    @Deprecated
    public List<KickScrewItemDO> queryItemByBrand(String brand, Integer page) {
        String url = UriComponentsBuilder.fromUriString(KickScrewApiConstant.SEARCH_ITEMS)
                .queryParam("brand", brand)
                .queryParam("page", page)
                .toUriString();
        String result = HttpUtil.doGet(url);
        System.out.println(JSON.toJSONString(result));
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONObject("pageProps"))
                .map(json -> json.getJSONObject("serverState"))
                .map(json -> json.getJSONObject("initialResults"))
                .map(json -> json.getJSONObject("prod_products"))
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .map(json -> json.getJSONArray("hits"))
                .map(jsonArray -> jsonArray.toJavaList(KickScrewItemDO.class)).orElse(new ArrayList<>());
    }

    @Deprecated
    public Integer queryBrandItemPage(String brand) {
        String url = UriComponentsBuilder.fromUriString(KickScrewApiConstant.SEARCH_ITEMS)
                .queryParam("brand", brand)
                .queryParam("page", PAGE_SIZE)
                .toUriString();
        String result = HttpUtil.doGet(url);
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONObject("pageProps"))
                .map(json -> json.getJSONObject("serverState"))
                .map(json -> json.getJSONObject("initialResults"))
                .map(json -> json.getJSONObject("prod_products"))
                .map(json -> json.getJSONArray("results"))
                .map(jsonArray -> jsonArray.getJSONObject(0))
                .map(json -> json.getInteger("nbPages"))
                .orElse(1);
    }

    public List<KickScrewSizePrice> queryItemSizePrice(String handle) {
        String result = HttpUtil.doPost(KickScrewApiConstant.SEARCH_ITEM_SIZE_PRICE,
                JSON.toJSONString(Map.of(
                        "query", "\n  query getProduct(\n    $handle: String!\n    $country: CountryCode!\n    $inHomePage: Boolean = false\n  ) @inContext(country: $country) {\n    product(handle: $handle) {\n      ...product\n    }\n  }\n  \n  fragment product on Product {\n    id\n    vendor\n    ... on Product @skip(if: $inHomePage) {\n      \n  metafields(identifiers: [\n    { namespace: \"product\", key: \"description\" },\n    { namespace: \"product\", key: \"strap_material\" },\n    { namespace: \"product\", key: \"case_material\" },\n    { namespace: \"product\", key: \"watch_caliber\" },\n    { namespace: \"product\", key: \"closure\" },\n    { namespace: \"product\", key: \"upper_material\" },\n    { namespace: \"product\", key: \"version\" },\n    { namespace: \"product\", key: \"thickness\" },\n    { namespace: \"product\", key: \"release_date\" },\n    { namespace: \"product\", key: \"model_no\" },\n    { namespace: \"product\", key: \"style\" },\n    { namespace: \"product\", key: \"colorway\" },\n    { namespace: \"product\", key: \"heel_type\" },\n    { namespace: \"product\", key: \"toe_type\" },\n    { namespace: \"product\", key: \"upper\" },\n    { namespace: \"product\", key: \"sole_material\" },\n    { namespace: \"product\", key: \"functionality\" },\n    { namespace: \"product\", key: \"season\" },\n    { namespace: \"product\", key: \"color\" },\n    { namespace: \"product\", key: \"series\" },\n    { namespace: \"product\", key: \"series_handle\" },\n    { namespace: \"product\", key: \"sub_series\" },\n    { namespace: \"product\", key: \"sub_series_handle\" },\n    { namespace: \"product\", key: \"size_report\" },\n    { namespace: \"product\", key: \"has_brand_direct\" },\n    { namespace: \"product\", key: \"nickname\" },\n    { namespace: \"product\", key: \"model_no_list\" },\n    { namespace: \"product\", key: \"occasion\" },\n    { namespace: \"reviews\", key: \"rating\" },\n    { namespace: \"reviews\", key: \"rating_count\" },\n  ]) {\n    key\n    value\n  }\n\n    }\n    ... on Product @include(if: $inHomePage) {\n      metafields(identifiers: [{ namespace: \"product\", key: \"model_no\" }]) {\n        key\n        value\n      }\n    }\n    handle\n    availableForSale\n    title\n    description @skip(if: $inHomePage)\n    options {\n      id\n      name\n      values\n    }\n    compareAtPriceRange {\n      maxVariantPrice {\n        amount\n        currencyCode\n      }\n      minVariantPrice {\n        amount\n        currencyCode\n      }\n    }\n    priceRange {\n      maxVariantPrice {\n        amount\n        currencyCode\n      }\n      minVariantPrice {\n        amount\n        currencyCode\n      }\n    }\n    productType\n    variants(first: 250) {\n      edges {\n        node {\n          id\n          title\n          sku\n          availableForSale\n          ... on ProductVariant @skip(if: $inHomePage) {\n            barcode\n            selectedOptions {\n              name\n              value\n            }\n            metafields(\n              identifiers: [\n                { namespace: \"price_stock\", key: \"is_instant_ship\" },\n                { namespace: \"campaign\", key: \"name\" },\n              ]\n            ) {\n              key\n              namespace\n              value\n            }\n          }\n          price {\n            amount\n            currencyCode\n          }\n          compareAtPrice {\n            amount\n            currencyCode\n          }\n        }\n      }\n    }\n    featuredImage {\n      ...image\n    }\n    ... on Product @skip(if: $inHomePage) {\n      images(first: 20) {\n        edges {\n          node {\n            ...image\n          }\n        }\n      }\n    }\n    ... on Product @include(if: $inHomePage) {\n      images(first: 1) {\n        edges {\n          node {\n            ...image\n          }\n        }\n      }\n    }\n    seo {\n      ...seo\n    }\n    tags\n    updatedAt\n    onlineStoreUrl\n  }\n  \n  fragment image on Image {\n    url\n    altText\n    width\n    height\n  }\n\n  \n  fragment seo on SEO {\n    description\n    title\n  }\n\n\n",
                        "variables", Map.of(
                                "handle", handle,
                                "country", "US"
                        )
                )),
                Headers.of("x-shopify-storefront-access-token", "43a507be1a455a4018117e16f8969b7e")
        );
        return Optional.ofNullable(result)
                .map(JSON::parseObject)
                .map(json -> json.getJSONObject("data"))
                .map(json -> json.getJSONObject("product"))
                .map(json -> json.getJSONObject("variants"))
                .map(json -> json.getJSONArray("edges"))
                .map(jsonArray -> jsonArray.toJavaList(JSONObject.class))
                .orElse(Collections.emptyList())
                .stream()
                .map(json -> json.getObject("node", KickScrewSizePrice.class))
                .toList();
    }

    public List<Map<String, String>> queryItemSizeChart(String brand, String modelNo) {
        String url = KickScrewApiConstant.SEARCH_ITEM_SIZE
                .replace("{brand}", brand)
                .replace("{modelNo}", modelNo);
        String result = HttpUtil.doGet(url);
        JSONObject parsedResult = JSON.parseObject(result);
        if (parsedResult == null || parsedResult.containsKey("error")) {
            log.error("queryItemSizeChart error, brand:{}, modelNo:{}", brand, modelNo);
            return Collections.emptyList();
        }
        List<String> sizeCodes = parsedResult.getObject("sizeCode", new TypeReference<List<String>>() {});
        List<JSONObject> tables = parsedResult.getObject("tables", new TypeReference<List<JSONObject>>() {});
        List<Map<String, String>> labelSizeMapList = new ArrayList<>();
        for (String size : sizeCodes) {
            Map<String, String> map = new HashMap<>();
            for (JSONObject table : tables) {
                String shortLabel = table.getString("shortLabel");
                String labelSize = table.getJSONObject("sizes").getString(size);
                map.put(shortLabel, labelSize);
            }
            labelSizeMapList.add(map);
        }
        return labelSizeMapList;
    }

    public void batchUploadItems(List<KickScrewUploadItem> items) {
        log.info("batchUploadItems start, cnt{}", items.size());
        String result = HttpUtil.doPost(KickScrewApiConstant.BATCH_UPLOAD_ITEMS,
            JSON.toJSONString(Collections.singletonMap("items", items)),
            Headers.of("x-api-key", KickScrewConfig.API_KEY)
        );
        log.info("batchUploadItems finish: result:{}", result);
    }

    public void deleteAllItems() {
        HttpUtil.doDelete(KickScrewApiConstant.DELETE_ALL_ITEMS,
                "",
                Headers.of("x-api-key", KickScrewConfig.API_KEY)
        );
    }

    private String buildAlgoliaBodyForItem(KickScrewAlgoliaRequest algoliaRequest) {
        JSONObject request = new JSONObject();
        request.put("indexName", "prod_products");
        Map<String, Object> params = new HashMap<>();
        params.put("attributesToSnippet", "[\"description:10\"]");
        params.put("clickAnalytics", "false");
        List<List<String>> facetFilters = new ArrayList<>();
        if (!CollectionUtils.isEmpty(algoliaRequest.getBrands())) {
            facetFilters.add(algoliaRequest.getBrands().stream().map(brand -> "brand:" + brand).toList());
        }
        if (!CollectionUtils.isEmpty(algoliaRequest.getProductTypes())) {
            facetFilters.add(algoliaRequest.getProductTypes().stream().map(type -> "product_type:" + type).toList());
        } else {
            facetFilters.add(List.of("product_type:Shoes", "product_type:Sneakers", "product_type:Slippers"));
        }
        if (!CollectionUtils.isEmpty(algoliaRequest.getGenders())) {
            facetFilters.add(algoliaRequest.getGenders().stream().map(gender -> "gender:" + gender).toList());
        }
        if (!CollectionUtils.isEmpty(algoliaRequest.getReleaseYears())) {
            facetFilters.add(algoliaRequest.getReleaseYears().stream().map(year -> "release_year:" + year).toList());
        }
        params.put("facetFilters", JSON.toJSONString(facetFilters));
        List<String> priceList = new ArrayList<>();
        if (algoliaRequest.getStartPrice() != null) {
            priceList.add("lowest_price>=" + algoliaRequest.getStartPrice());
        }
        if (algoliaRequest.getEndPrice() != null) {
            priceList.add("lowest_price<=" + algoliaRequest.getEndPrice());
        }
        params.put("numericFilters", JSON.toJSONString(priceList));
        params.put("filters", "NOT class: 0");
        params.put("highlightPostTag", "__/ais-highlight__");
        params.put("highlightPreTag", "__ais-highlight__");
        params.put("hitsPerPage", String.valueOf(algoliaRequest.getPageSize()));
        params.put("maxValuesPerFacet", "999");
        params.put("page", String.valueOf(algoliaRequest.getPageIndex()));
        params.put("removeWordsIfNoResults", "allOptional");
        params.put("userToken", "803310494-1734974059");
        String paramString = HttpUtil.buildUrlParams(params);
        request.put("params", paramString);

        JSONObject body = new JSONObject();
        body.put("requests", List.of(request));
        return body.toJSONString();
    }

    private String buildAlgoliaBodyForBrand() {
        JSONObject request = new JSONObject();
        request.put("indexName", "prod_products");
        Map<String, Object> params = new HashMap<>();
        params.put("attributesToSnippet", "[\"description:10\"]");
        params.put("clickAnalytics", "false");
//        params.put("facets", "[\"brand\",\"gender\",\"lowest_price\",\"main_color\",\"product_type\",\"release_year\",\"sizes\"]");
        params.put("facets", "[\"brand\"]");
        params.put("filters", "NOT class: 0");
        params.put("highlightPostTag", "__/ais-highlight__");
        params.put("highlightPreTag", "__ais-highlight__");
        params.put("hitsPerPage", "1");
        params.put("maxValuesPerFacet", "10000");
        params.put("page", "0");
        params.put("removeWordsIfNoResults", "allOptional");
        params.put("userToken", "803310494-1734974059");
        String paramString = HttpUtil.buildUrlParams(params);
        request.put("params", paramString);

        JSONObject body = new JSONObject();
        body.put("requests", List.of(request));
        return body.toJSONString();
    }

}
